package com.terraquiz;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Menu. Starts unpacking the map straight away so the games open instantly. */
public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        World.preload(this);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 28);
        root.setPadding(pad, pad, pad, pad);

        TextView t = Ui.text(this, "Terraquiz", 40f, Ui.TEXT, true);
        TextView sub = Ui.text(this,
                "Two ways to learn the map. The countries you already know drop "
                        + "to the back of the queue, the ones you miss come back sooner.",
                15f, Ui.DIM, false);
        sub.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 28));
        root.addView(t);
        root.addView(sub);

        root.addView(bigButton("Find the country",
                "A name, and you point at it on the world map.", FindActivity.class));
        root.addView(gap());
        root.addView(bigButton("Name the country",
                "A country lights up, and you type what it is called.", NameActivity.class));
        root.addView(gap());

        Button stats = Ui.button(this, "Your progress", Ui.BUTTON, Ui.TEXT);
        stats.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, StatsActivity.class));
            }
        });
        root.addView(stats, Ui.lp(Ui.MATCH, Ui.WRAP));

        setContentView(root);
        Ui.fitSystemWindows(root);
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(Ui.lp(Ui.MATCH, Ui.dp(this, 14)));
        return v;
    }

    private View bigButton(String label, String detail, final Class<?> target) {
        LinearLayout card = Ui.column(this);
        card.setBackgroundColor(Ui.PANEL);
        int p = Ui.dp(this, 18);
        card.setPadding(p, p, p, p);
        card.addView(Ui.text(this, label, 21f, Ui.ACCENT, true));
        TextView d = Ui.text(this, detail, 14f, Ui.DIM, false);
        d.setPadding(0, Ui.dp(this, 4), 0, 0);
        card.addView(d);
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, target));
            }
        });
        card.setLayoutParams(Ui.lp(Ui.MATCH, Ui.WRAP));
        return card;
    }
}
