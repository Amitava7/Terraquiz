package com.terraquiz;

import android.content.Context;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole map and every word the app says about it, unpacked from the single
 * assets/world.bin blob written by tools/build_data.py.
 *
 * Loading happens once on a background thread; {@link #get} blocks until it is
 * done. Outlines are shared arcs, so a border between two countries is stored
 * once and both sides trace the identical line - no slivers at any zoom.
 */
final class World {

    private static World sWorld;
    private static Throwable sError;
    private static final Object LOCK = new Object();

    final List<Country> countries = new ArrayList<Country>();
    final List<Country> quiz = new ArrayList<Country>();
    /** Full extent of the map in map units (x = lon, y = -lat). */
    final RectF extent = new RectF();

    static void preload(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            if (sWorld != null || sError != null) return;
        }
        new Thread(new Runnable() {
            public void run() {
                World w = null;
                Throwable err = null;
                try {
                    w = load(app);
                } catch (Throwable t) {
                    err = t;
                }
                synchronized (LOCK) {
                    if (w != null) sWorld = w;
                    else sError = err;
                    LOCK.notifyAll();
                }
            }
        }, "world-loader").start();
    }

    /** Blocks until the map is ready. Never call from the UI thread first. */
    static World get(Context ctx) {
        preload(ctx);
        synchronized (LOCK) {
            while (sWorld == null && sError == null) {
                try {
                    LOCK.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (sWorld == null) throw new RuntimeException("world.bin", sError);
            return sWorld;
        }
    }

    static boolean isReady() {
        synchronized (LOCK) {
            return sWorld != null;
        }
    }

    // ----------------------------------------------------------------- lookup

    Country at(float x, float y) {
        for (int i = 0, n = countries.size(); i < n; i++) {
            Country c = countries.get(i);
            if (c.contains(x, y)) return c;
        }
        return null;
    }

    /** Nearest outline within {@code tol} map units - lets tiny states be tapped. */
    Country near(float x, float y, float tol) {
        Country hit = at(x, y);
        if (hit != null) return hit;
        Country best = null;
        float bestD = tol * tol;
        for (int i = 0, n = countries.size(); i < n; i++) {
            Country c = countries.get(i);
            if (x < c.bounds.left - tol || x > c.bounds.right + tol
                    || y < c.bounds.top - tol || y > c.bounds.bottom + tol) continue;
            float d = c.distance2(x, y);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    Country byKey(String key) {
        for (int i = 0, n = countries.size(); i < n; i++) {
            if (countries.get(i).key().equals(key)) return countries.get(i);
        }
        return null;
    }

    // ------------------------------------------------------------------ parse

    private static World load(Context ctx) throws IOException {
        byte[] data = readAsset(ctx, "world.bin");
        Reader r = new Reader(data);
        if (r.u8() != 'T' || r.u8() != 'Q' || r.u8() != 'D' || r.u8() != 2) {
            throw new IOException("world.bin has the wrong header");
        }
        double sx = r.f64(), sy = r.f64(), tx = r.f64(), ty = r.f64();

        int arcCount = r.uvar();
        float[][] arcs = new float[arcCount][];
        for (int a = 0; a < arcCount; a++) {
            int n = r.uvar();
            float[] pts = new float[n * 2];
            int qx = 0, qy = 0;
            for (int i = 0; i < n; i++) {
                qx += r.svar();
                qy += r.svar();
                pts[i * 2] = (float) (qx * sx + tx);        // longitude
                pts[i * 2 + 1] = (float) -(qy * sy + ty);   // -latitude
            }
            arcs[a] = pts;
        }

        World w = new World();
        int count = r.uvar();
        w.extent.set(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (int i = 0; i < count; i++) {
            Country c = new Country();
            c.name = r.text();
            int flags = r.u8();
            c.quizzable = (flags & 1) != 0;
            c.fame = r.u8();
            c.code = r.text();
            c.sovereign = r.text();
            c.aliases = new String[r.uvar()];
            for (int j = 0; j < c.aliases.length; j++) c.aliases[j] = r.text();
            c.labelX = (float) (r.svar() * sx + tx);
            c.labelY = (float) -(r.svar() * sy + ty);
            int bx = r.svar(), by = r.svar(), bw = r.svar(), bh = r.svar();
            setBox(c.bounds, bx, by, bx + bw, by + bh, sx, sy, tx, ty);
            int mx = bx + r.svar(), my = by + r.svar();
            setBox(c.mainBounds, mx, my, mx + r.svar(), my + r.svar(), sx, sy, tx, ty);

            int polyCount = r.uvar();
            List<float[]> rings = new ArrayList<float[]>();
            Path path = new Path();
            path.setFillType(Path.FillType.EVEN_ODD);
            Path coarse = new Path();
            coarse.setFillType(Path.FillType.EVEN_ODD);
            for (int p = 0; p < polyCount; p++) {
                int ringCount = r.uvar();
                for (int g = 0; g < ringCount; g++) {
                    for (float[] ring : splitAtDateline(assemble(arcs, r))) {
                        rings.add(ring);
                        addRing(path, ring, 0f);
                        addRing(coarse, ring, 0.22f);
                    }
                }
            }
            c.rings = rings.toArray(new float[rings.size()][]);
            c.path = path;
            c.coarse = coarse;
            c.facts = new String[r.uvar()];
            for (int j = 0; j < c.facts.length; j++) c.facts[j] = r.text();
            c.hints = new String[r.uvar()];
            for (int j = 0; j < c.hints.length; j++) c.hints[j] = r.text();

            w.countries.add(c);
            if (c.quizzable) w.quiz.add(c);
            w.extent.union(c.bounds);
        }
        return w;
    }

    private static void setBox(RectF out, int x0, int y0, int x1, int y1,
                               double sx, double sy, double tx, double ty) {
        float lon0 = (float) (x0 * sx + tx), lon1 = (float) (x1 * sx + tx);
        float lat0 = (float) -(y0 * sy + ty), lat1 = (float) -(y1 * sy + ty);
        out.set(Math.min(lon0, lon1), Math.min(lat0, lat1),
                Math.max(lon0, lon1), Math.max(lat0, lat1));
    }

    /** Ring = a list of signed arc indices; a negative index means "reversed". */
    private static float[] assemble(float[][] arcs, Reader r) {
        int n = r.uvar();
        int[] idx = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            idx[i] = r.svar();
            int len = arcs[idx[i] < 0 ? ~idx[i] : idx[i]].length / 2;
            total += (i == 0) ? len : len - 1;  // arcs share their end points
        }
        float[] out = new float[total * 2];
        int w = 0;
        for (int i = 0; i < n; i++) {
            float[] a = arcs[idx[i] < 0 ? ~idx[i] : idx[i]];
            int len = a.length / 2;
            if (idx[i] < 0) {
                for (int k = len - 1 - (i == 0 ? 0 : 1); k >= 0; k--) {
                    out[w++] = a[k * 2];
                    out[w++] = a[k * 2 + 1];
                }
            } else {
                for (int k = (i == 0 ? 0 : 1); k < len; k++) {
                    out[w++] = a[k * 2];
                    out[w++] = a[k * 2 + 1];
                }
            }
        }
        return out;
    }

    /**
     * Russia, Fiji and Antarctica run past 180 degrees, and the source data
     * wraps their longitudes back to the other end of the scale. Left alone,
     * the step from +179.9 to -180 draws a line clean across the map and makes
     * the country "contain" everything between. So cut every ring where it
     * wraps, insert the point where it meets the edge of the map, and close
     * each piece along that edge.
     */
    private static List<float[]> splitAtDateline(float[] ring) {
        List<float[]> out = new ArrayList<float[]>(1);
        int n = ring.length;
        boolean wraps = false;
        for (int i = 0; i + 3 < n; i += 2) {
            if (Math.abs(ring[i + 2] - ring[i]) > 180f) {
                wraps = true;
                break;
            }
        }
        if (!wraps) {
            out.add(ring);
            return out;
        }

        List<List<Float>> pieces = new ArrayList<List<Float>>();
        List<Float> cur = new ArrayList<Float>();
        cur.add(ring[0]);
        cur.add(ring[1]);
        for (int i = 0; i + 3 < n; i += 2) {
            float x0 = ring[i], y0 = ring[i + 1], x1 = ring[i + 2], y1 = ring[i + 3];
            if (Math.abs(x1 - x0) > 180f) {
                float edge = x0 > 0 ? 180f : -180f;
                float unwrapped = x1 + (x0 > 0 ? 360f : -360f);
                float span = unwrapped - x0;
                float t = span == 0f ? 0f : (edge - x0) / span;
                if (!(t >= 0f && t <= 1f)) t = 0f;
                float yc = y0 + t * (y1 - y0);
                cur.add(edge);
                cur.add(yc);
                pieces.add(cur);
                cur = new ArrayList<Float>();
                cur.add(-edge);
                cur.add(yc);
            }
            cur.add(x1);
            cur.add(y1);
        }
        pieces.add(cur);

        // The ring is a loop, so its last piece runs into its first one.
        if (pieces.size() > 1) {
            List<Float> last = pieces.remove(pieces.size() - 1);
            List<Float> first = pieces.get(0);
            last.addAll(first.subList(2, first.size()));
            pieces.set(0, last);
        }
        for (int i = 0; i < pieces.size(); i++) {
            List<Float> p = pieces.get(i);
            if (p.size() < 6) continue;            // nothing to fill
            float[] arr = new float[p.size()];
            for (int k = 0; k < arr.length; k++) arr[k] = p.get(k);
            out.add(arr);
        }
        if (out.isEmpty()) out.add(ring);
        return out;
    }

    /**
     * Adds a ring, optionally dropping points closer together than {@code skip}
     * degrees. The decimated copy is what gets drawn when the whole world is on
     * screen, where the detail is far below one pixel anyway.
     */
    private static void addRing(Path path, float[] ring, float skip) {
        int n = ring.length;
        if (n < 6) return;
        path.moveTo(ring[0], ring[1]);
        float lx = ring[0], ly = ring[1];
        int drawn = 1;
        for (int i = 2; i < n; i += 2) {
            float x = ring[i], y = ring[i + 1];
            if (skip > 0 && i + 2 < n) {
                float dx = x - lx, dy = y - ly;
                if (dx * dx + dy * dy < skip * skip) continue;
            }
            path.lineTo(x, y);
            lx = x;
            ly = y;
            drawn++;
        }
        if (drawn < 3) path.lineTo(ring[n - 2], ring[n - 1]);
        path.close();
    }

    private static byte[] readAsset(Context ctx, String name) throws IOException {
        InputStream in = ctx.getAssets().open(name);
        try {
            byte[] buf = new byte[1 << 16];
            int size = 0;
            byte[] out = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (size + n > out.length) {
                    byte[] bigger = new byte[Math.max(out.length * 2, size + n)];
                    System.arraycopy(out, 0, bigger, 0, size);
                    out = bigger;
                }
                System.arraycopy(buf, 0, out, size, n);
                size += n;
            }
            byte[] exact = new byte[size];
            System.arraycopy(out, 0, exact, 0, size);
            return exact;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Big-endian primitives plus the varints build_data.py writes. */
    private static final class Reader {
        private final byte[] d;
        private int i;

        Reader(byte[] d) {
            this.d = d;
        }

        int u8() {
            return d[i++] & 0xFF;
        }

        double f64() {
            long v = 0;
            for (int k = 0; k < 8; k++) v = (v << 8) | (d[i++] & 0xFFL);
            return Double.longBitsToDouble(v);
        }

        int uvar() {
            int out = 0, shift = 0, b;
            do {
                b = d[i++] & 0xFF;
                out |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return out;
        }

        int svar() {
            int v = uvar();
            return (v & 1) == 0 ? (v >>> 1) : -((v + 1) >>> 1);
        }

        String text() {
            int n = uvar();
            String s = new String(d, i, n, java.nio.charset.StandardCharsets.UTF_8);
            i += n;
            return s;
        }
    }
}
