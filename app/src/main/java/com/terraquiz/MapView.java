package com.terraquiz;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

/**
 * The world map: pan, pinch zoom, double-tap zoom, and a tap that reports which
 * country was hit.
 *
 * Everything is drawn from vector outlines in map units (x = longitude,
 * y = -latitude), so borders stay a crisp hairline at every zoom level: the
 * stroke width is divided by the zoom before drawing.
 */
final class MapView extends View {

    interface OnTapListener {
        /** @param c the country under the finger, or null for open water. */
        void onTap(Country c);
    }

    /** A country painted in a colour, optionally with its name written on it. */
    static final class Mark {
        Country country;
        int fill;
        int outline;
        String label;
        int labelColor;

        Mark(Country c, int fill, int outline, String label, int labelColor) {
            this.country = c;
            this.fill = fill;
            this.outline = outline;
            this.label = label;
            this.labelColor = labelColor;
        }
    }

    private World world;
    private OnTapListener tapListener;

    private float cx, cy;        // map point at the centre of the view
    private float scale = 1f;    // pixels per degree
    private float minScale = 1f;
    // Pixels per degree. The ceiling is high on purpose: at 2000 a phone screen
    // covers well under a degree, which is what it takes to put a finger on
    // Monaco or San Marino.
    private float maxScale = 2000f;

    private final List<Mark> marks = new ArrayList<Mark>();
    private float hintX, hintY, hintRadius;   // "somewhere in here" circle, degrees

    private final Paint land = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF visible = new RectF();
    private final RectF pill = new RectF();
    // reused every frame: panning should not make work for the collector
    private final List<Country> shown = new ArrayList<Country>(64);
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();

    private RectF pendingBox;     // a flyTo asked for before the view had a size
    private float pendingPad;

    private GestureDetector gestures;
    private ScaleGestureDetector pinch;
    private OverScroller scroller;
    private ValueAnimator flight;
    private final float density;

    MapView(Context ctx) {
        super(ctx);
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(Ui.OCEAN);

        land.setStyle(Paint.Style.FILL);
        land.setColor(Ui.LAND);
        border.setStyle(Paint.Style.STROKE);
        border.setColor(Ui.BORDER);
        border.setStrokeJoin(Paint.Join.ROUND);
        border.setStrokeCap(Paint.Cap.ROUND);
        markFill.setStyle(Paint.Style.FILL);
        markLine.setStyle(Paint.Style.STROKE);
        markLine.setStrokeJoin(Paint.Join.ROUND);
        hint.setStyle(Paint.Style.STROKE);
        hint.setColor(Ui.HINT);
        hint.setStrokeWidth(2f * density);
        labelBg.setColor(0xCC0B1622);
        labelText.setTextSize(14f * density);
        labelText.setFakeBoldText(true);
        labelText.setTextAlign(Paint.Align.CENTER);

        scroller = new OverScroller(ctx);
        gestures = new GestureDetector(ctx, new Gestures());
        pinch = new ScaleGestureDetector(ctx, new Pinch());
    }

    void setWorld(World w) {
        world = w;
        if (w != null && getWidth() > 0) fitAll();
        invalidate();
    }

    void setOnTapListener(OnTapListener l) {
        tapListener = l;
    }

    // ------------------------------------------------------------- highlights

    void clearMarks() {
        marks.clear();
        hintRadius = 0;
        invalidate();
    }

    void addMark(Country c, int fill, int outline, String label, int labelColor) {
        for (int i = marks.size() - 1; i >= 0; i--) {
            if (marks.get(i).country == c) marks.remove(i);
        }
        marks.add(new Mark(c, fill, outline, label, labelColor));
        invalidate();
    }

    void showHintCircle(Country c, float radiusDegrees) {
        hintX = c.labelX;
        hintY = c.labelY;
        hintRadius = radiusDegrees;
        invalidate();
    }

    // ------------------------------------------------------------ positioning

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (world == null) return;
        RectF e = world.extent;
        minScale = Math.min(w / e.width(), h / e.height());
        if (ow == 0) {
            fitAll();
        } else {
            constrain();
        }
        if (pendingBox != null) {
            RectF box = pendingBox;
            pendingBox = null;
            flyTo(box, pendingPad, false);
        }
    }

    /** Pulls back to the whole world. */
    void flyHome(boolean animate) {
        if (world == null) return;
        if (getWidth() == 0) {
            pendingBox = new RectF(world.extent);
            pendingPad = 1f;
            return;
        }
        RectF e = world.extent;
        minScale = Math.min(getWidth() / e.width(), getHeight() / e.height());
        moveTo(e.centerX(), e.centerY(), minScale, animate);
    }

    void fitAll() {
        if (world == null || getWidth() == 0) return;
        RectF e = world.extent;
        minScale = Math.min(getWidth() / e.width(), getHeight() / e.height());
        scale = minScale;
        cx = e.centerX();
        cy = e.centerY();
        invalidate();
    }

    /** Frames a rectangle of the map, leaving room around it for context. */
    void flyTo(RectF box, float pad, boolean animate) {
        if (getWidth() == 0 || getHeight() == 0) {
            pendingBox = new RectF(box);   // replay it once we know our size
            pendingPad = pad;
            return;
        }
        float w = Math.max(box.width(), 0.35f) * pad;
        float h = Math.max(box.height(), 0.35f) * pad;
        float target = Math.min(getWidth() / w, getHeight() / h);
        target = clamp(target, minScale, maxScale);
        moveTo(box.centerX(), box.centerY(), target, animate);
    }

    void moveTo(float nx, float ny, float nscale, boolean animate) {
        stopMotion();
        if (!animate) {
            cx = nx;
            cy = ny;
            scale = nscale;
            constrain();
            invalidate();
            return;
        }
        final float fx = cx, fy = cy, fs = scale;
        final float tx = nx, ty = ny, ts = nscale;
        flight = ValueAnimator.ofFloat(0f, 1f);
        flight.setDuration(420);
        flight.setInterpolator(new DecelerateInterpolator());
        flight.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float t = a.getAnimatedFraction();
                cx = fx + (tx - fx) * t;
                cy = fy + (ty - fy) * t;
                // zoom geometrically so the movement feels even
                scale = (float) (fs * Math.pow(ts / fs, t));
                constrain();
                invalidate();
            }
        });
        flight.start();
    }

    private void stopMotion() {
        if (flight != null) {
            flight.cancel();
            flight = null;
        }
        scroller.forceFinished(true);
    }

    private void constrain() {
        if (world == null || getWidth() == 0) return;
        scale = clamp(scale, minScale, maxScale);
        RectF e = world.extent;
        float halfW = getWidth() / (2 * scale);
        float halfH = getHeight() / (2 * scale);
        cx = (halfW * 2 >= e.width()) ? e.centerX()
                : clamp(cx, e.left + halfW, e.right - halfW);
        cy = (halfH * 2 >= e.height()) ? e.centerY()
                : clamp(cy, e.top + halfH, e.bottom - halfH);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    float mapX(float screenX) {
        return (screenX - getWidth() / 2f) / scale + cx;
    }

    float mapY(float screenY) {
        return (screenY - getHeight() / 2f) / scale + cy;
    }

    private float screenX(float mx) {
        return (mx - cx) * scale + getWidth() / 2f;
    }

    private float screenY(float my) {
        return (my - cy) * scale + getHeight() / 2f;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Ui.OCEAN);
        if (world == null) return;

        visible.set(mapX(0), mapY(0), mapX(getWidth()), mapY(getHeight()));
        boolean lod = scale < 14f;

        canvas.save();
        canvas.translate(getWidth() / 2f, getHeight() / 2f);
        canvas.scale(scale, scale);
        canvas.translate(-cx, -cy);

        border.setStrokeWidth(1.1f * density / scale);
        if (lod) {
            // Zoomed out everything is on screen, so culling buys nothing and
            // the whole world goes down as two draw calls.
            canvas.drawPath(world.landCoarse, land);
            drawMarkFills(canvas);
            canvas.drawPath(world.borderCoarse, border);
        } else {
            shown.clear();
            for (int i = 0, n = world.countries.size(); i < n; i++) {
                Country c = world.countries.get(i);
                if (RectF.intersects(c.bounds, visible)) shown.add(c);
            }
            for (int i = 0; i < shown.size(); i++) {
                canvas.drawPath(shown.get(i).path, land);
            }
            drawMarkFills(canvas);
            // borders last, so no neighbour's fill paints over them
            for (int i = 0; i < shown.size(); i++) {
                canvas.drawPath(shown.get(i).path, border);
            }
        }
        markLine.setStrokeWidth(2.4f * density / scale);
        for (int i = 0; i < marks.size(); i++) {
            Mark m = marks.get(i);
            markLine.setColor(m.outline);
            canvas.drawPath(m.country.path, markLine);
        }
        canvas.restore();

        if (hintRadius > 0) {
            canvas.drawCircle(screenX(hintX), screenY(hintY), hintRadius * scale, hint);
        }
        for (int i = 0; i < marks.size(); i++) {
            Mark m = marks.get(i);
            if (m.label != null) drawLabel(canvas, m);
        }
    }

    private void drawMarkFills(Canvas canvas) {
        for (int i = 0; i < marks.size(); i++) {
            Mark m = marks.get(i);
            markFill.setColor(m.fill);
            canvas.drawPath(m.country.path, markFill);
        }
    }

    private void drawLabel(Canvas canvas, Mark m) {
        float x = clamp(screenX(m.country.labelX), 8 * density, getWidth() - 8 * density);
        float y = clamp(screenY(m.country.labelY), 24 * density, getHeight() - 8 * density);
        float w = labelText.measureText(m.label);
        float padX = 8 * density, padY = 5 * density;
        labelText.getFontMetrics(metrics);
        pill.set(x - w / 2 - padX, y + metrics.top - padY,
                x + w / 2 + padX, y + metrics.bottom + padY);
        canvas.drawRoundRect(pill, 6 * density, 6 * density, labelBg);
        labelText.setColor(m.labelColor);
        canvas.drawText(m.label, x, y, labelText);
    }

    // --------------------------------------------------------------- gestures

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean handled = pinch.onTouchEvent(e);
        handled |= gestures.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return handled || super.onTouchEvent(e);
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            cx = scroller.getCurrX() / 1000f;
            cy = scroller.getCurrY() / 1000f;
            constrain();
            postInvalidateOnAnimation();
        }
    }

    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            stopMotion();
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent down, MotionEvent now, float dx, float dy) {
            cx += dx / scale;
            cy += dy / scale;
            constrain();
            invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent down, MotionEvent now, float vx, float vy) {
            if (world == null) return false;
            RectF e = world.extent;
            float halfW = getWidth() / (2 * scale), halfH = getHeight() / (2 * scale);
            int minX = (int) ((halfW * 2 >= e.width() ? e.centerX() : e.left + halfW) * 1000);
            int maxX = (int) ((halfW * 2 >= e.width() ? e.centerX() : e.right - halfW) * 1000);
            int minY = (int) ((halfH * 2 >= e.height() ? e.centerY() : e.top + halfH) * 1000);
            int maxY = (int) ((halfH * 2 >= e.height() ? e.centerY() : e.bottom - halfH) * 1000);
            scroller.fling((int) (cx * 1000), (int) (cy * 1000),
                    (int) (-vx / scale * 1000), (int) (-vy / scale * 1000),
                    Math.min(minX, maxX), Math.max(minX, maxX),
                    Math.min(minY, maxY), Math.max(minY, maxY));
            postInvalidateOnAnimation();
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (world == null || tapListener == null) return false;
            float mx = mapX(e.getX()), my = mapY(e.getY());
            // A finger covers about 20dp, so allow that much slack for the very
            // small states - but never more than a degree and a half, or a tap
            // in the middle of an ocean would snap to a distant coast.
            float tol = Math.min(20 * density / scale, 1.5f);
            tapListener.onTap(world.near(mx, my, tol));
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            float target = scale >= maxScale * 0.5f ? minScale : scale * 4f;
            float fx = mapX(e.getX()), fy = mapY(e.getY());
            moveTo((cx + fx) / 2, (cy + fy) / 2, clamp(target, minScale, maxScale), true);
            return true;
        }
    }

    private final class Pinch extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector d) {
            float focusX = d.getFocusX(), focusY = d.getFocusY();
            float beforeX = mapX(focusX), beforeY = mapY(focusY);
            scale = clamp(scale * d.getScaleFactor(), minScale, maxScale);
            // keep the point under the fingers pinned
            cx = beforeX - (focusX - getWidth() / 2f) / scale;
            cy = beforeY - (focusY - getHeight() / 2f) / scale;
            constrain();
            invalidate();
            return true;
        }
    }
}
