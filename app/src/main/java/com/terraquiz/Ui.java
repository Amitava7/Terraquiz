package com.terraquiz;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours, a few view factories, and window-inset handling. No XML layouts. */
final class Ui {

    static final int OCEAN = 0xFF07141F;
    static final int LAND = 0xFF23384A;
    static final int BORDER = 0xFF7AA1BE;
    static final int BG = 0xFF0B1118;
    static final int PANEL = 0xFF121C26;
    static final int TEXT = 0xFFE7EFF6;
    static final int DIM = 0xFF9BB0C2;
    static final int GREEN = 0xFF2FBF71;
    static final int GREEN_FILL = 0xAA2FBF71;
    static final int RED = 0xFFE5544B;
    static final int RED_FILL = 0xAAE5544B;
    static final int AMBER = 0xFFF0B429;
    static final int AMBER_FILL = 0x99F0B429;
    static final int HINT = 0xCCF0B429;
    static final int ACCENT = 0xFF3D9BE9;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    static Button button(Context c, String label, int bg, int fg) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        b.setTextColor(fg);
        b.setBackground(pill(bg));
        b.setPadding(dp(c, 18), dp(c, 10), dp(c, 18), dp(c, 10));
        b.setStateListAnimator(null);
        b.setMinimumHeight(dp(c, 48));
        return b;
    }

    private static StateListDrawable pill(int bg) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, round(dim(bg)));
        sl.addState(new int[]{-android.R.attr.state_enabled}, round(dim(bg)));
        sl.addState(new int[0], round(bg));
        return sl;
    }

    private static GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(999f);
        return g;
    }

    private static int dim(int c) {
        return Color.argb(Color.alpha(c), Color.red(c) * 2 / 3, Color.green(c) * 2 / 3,
                Color.blue(c) * 2 / 3);
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    /**
     * Apps targeting Android 15 draw behind the system bars, so pad the root
     * view by whatever the bars and the keyboard cover.
     */
    static void fitSystemWindows(final View root) {
        final int l = root.getPaddingLeft(), t = root.getPaddingTop();
        final int r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(l + bars.left, t + bars.top, r + bars.right,
                        b + Math.max(bars.bottom, ime.bottom));
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    static void hideKeyboard(Activity a, View v) {
        InputMethodManager imm = a.getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    static void showKeyboard(Activity a, View v) {
        v.requestFocus();
        InputMethodManager imm = a.getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
    }
}
