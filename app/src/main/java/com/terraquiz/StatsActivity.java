package com.terraquiz;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** How far through the map you are, per game, plus the countries giving trouble. */
public final class StatsActivity extends Activity {

    private LinearLayout body;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 22);
        root.setPadding(pad, pad, pad, pad);
        root.addView(Ui.text(this, "Your progress", 28f, Ui.TEXT, true));

        body = Ui.column(this);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        root.addView(scroll, Ui.lp(Ui.MATCH, 0, 1f));

        Button reset = Ui.button(this, "Start over", Ui.BUTTON, Ui.RED);
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmReset();
            }
        });
        root.addView(reset, Ui.lp(Ui.MATCH, Ui.WRAP));
        setContentView(root);
        Ui.fitSystemWindows(root);
        load();
    }

    private void load() {
        body.removeAllViews();
        body.addView(Ui.text(this, "\nReading the map…", 15f, Ui.DIM, false));
        new Thread(new Runnable() {
            public void run() {
                final World w = World.get(getApplicationContext());
                final Progress p = Progress.get(getApplicationContext());
                final Progress.Summary find = p.summary(Progress.MODE_FIND, w.quiz);
                final Progress.Summary name = p.summary(Progress.MODE_NAME, w.quiz);
                ui.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        body.removeAllViews();
                        section("Find the country", find);
                        section("Name the country", name);
                    }
                });
            }
        }, "stats").start();
    }

    private void section(String title, Progress.Summary s) {
        LinearLayout card = Ui.column(this);
        card.setBackgroundColor(Ui.PANEL);
        int p = Ui.dp(this, 16);
        card.setPadding(p, p, p, p);
        card.addView(Ui.text(this, title, 19f, Ui.ACCENT, true));
        card.addView(line("Known first try", s.known + " of " + s.total + " countries"));
        card.addView(line("Countries met", String.valueOf(s.attempted)));
        card.addView(line("Questions asked", String.valueOf(s.asked)));
        card.addView(line("Answered", String.valueOf(s.solved)));
        card.addView(line("Right first time", String.valueOf(s.firsts)));
        card.addView(line("Wrong answers", String.valueOf(s.misses)));
        if (!s.weakest.isEmpty()) {
            TextView t = Ui.text(this, "Coming back soon: "
                    + android.text.TextUtils.join(", ", s.weakest), 14f, Ui.AMBER, false);
            t.setPadding(0, Ui.dp(this, 10), 0, 0);
            card.addView(t);
        }
        LinearLayout.LayoutParams lp = Ui.lp(Ui.MATCH, Ui.WRAP);
        lp.bottomMargin = Ui.dp(this, 14);
        card.setLayoutParams(lp);
        body.addView(card);
    }

    private View line(String label, String value) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 6), 0, 0);
        TextView l = Ui.text(this, label, 15f, Ui.DIM, false);
        TextView v = Ui.text(this, value, 15f, Ui.TEXT, true);
        row.addView(l, Ui.lp(0, Ui.WRAP, 1f));
        row.addView(v);
        return row;
    }

    private void confirmReset() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Start over?")
                .setMessage("This forgets every country you have answered, in both games.")
                .setNegativeButton("Keep it", null)
                .setPositiveButton("Start over", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        Progress.get(StatsActivity.this).resetAll();
                        load();
                    }
                })
                .show();
    }
}
