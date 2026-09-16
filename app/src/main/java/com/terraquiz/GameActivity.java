package com.terraquiz;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shared scaffolding for both games: load the map, keep a queue of countries to
 * ask about, run one question at a time and write the result to the progress
 * database.
 */
abstract class GameActivity extends Activity {

    World world;
    Progress progress;
    MapView map;

    private LinearLayout root;
    private TextView kicker;
    TextView title;       // the country being asked about, or the question
    TextView status;      // green / red feedback line
    TextView info;        // hints, then the fact
    LinearLayout controls;
    Button hintBtn;
    Button revealBtn;
    Button nextBtn;

    Country target;
    int misses;
    int hintsUsed;
    boolean answered;

    private int asked, firstTries;
    private final List<String> recent = new ArrayList<String>();
    final Random random = new Random();
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Progress.MODE_FIND or Progress.MODE_NAME. */
    abstract int mode();

    abstract String heading();

    /** Set up the map and prompt for a fresh question. */
    abstract void startQuestion();

    /** Show hint number {@code index}; return false when there are none left. */
    abstract boolean showHint(int index);

    /** Reveal the answer because the player gave up. */
    abstract void revealAnswer();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        progress = Progress.get(this);
        buildUi();
        loadWorld();
    }

    private void buildUi() {
        root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 16);

        LinearLayout header = Ui.column(this);
        header.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        kicker = Ui.text(this, heading(), 13f, Ui.DIM, false);
        title = Ui.text(this, "…", 24f, Ui.TEXT, true);
        status = Ui.text(this, "", 15f, Ui.DIM, false);
        status.setPadding(0, Ui.dp(this, 4), 0, 0);
        header.addView(kicker);
        header.addView(title);
        header.addView(status);
        root.addView(header, Ui.lp(Ui.MATCH, Ui.WRAP));

        map = new MapView(this);
        root.addView(map, Ui.lp(Ui.MATCH, 0, 1f));

        LinearLayout panel = Ui.column(this);
        panel.setBackgroundColor(Ui.PANEL);
        panel.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));

        info = Ui.text(this, "", 15f, Ui.TEXT, false);
        info.setLineSpacing(Ui.dp(this, 3), 1f);
        info.setVisibility(View.GONE);
        // The panel grows with the hints and the map, which carries the
        // weight, gives up the room; past a few hints the panel scrolls.
        ScrollView scroll = new ScrollView(this);
        scroll.addView(info);
        panel.addView(scroll, Ui.lp(Ui.MATCH, Ui.WRAP, 0f));
        scroll.setScrollbarFadingEnabled(true);

        controls = Ui.column(this);
        panel.addView(controls, Ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout buttons = Ui.row(this);
        buttons.setPadding(0, Ui.dp(this, 10), 0, 0);
        hintBtn = Ui.button(this, "Hint", Ui.PANEL, Ui.AMBER);
        revealBtn = Ui.button(this, "Give up", Ui.PANEL, Ui.DIM);
        nextBtn = Ui.button(this, "Next ›", Ui.ACCENT, 0xFF06121C);
        hintBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onHintPressed();
            }
        });
        revealBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                giveUp();
            }
        });
        nextBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                nextQuestion();
            }
        });
        buttons.addView(hintBtn, Ui.lp(0, Ui.WRAP, 1f));
        buttons.addView(space(8));
        buttons.addView(revealBtn, Ui.lp(0, Ui.WRAP, 1f));
        buttons.addView(space(8));
        buttons.addView(nextBtn, Ui.lp(0, Ui.WRAP, 1.2f));
        panel.addView(buttons, Ui.lp(Ui.MATCH, Ui.WRAP));

        root.addView(panel, Ui.lp(Ui.MATCH, Ui.WRAP));
        setContentView(root);
        Ui.fitSystemWindows(root);
        setButtonsEnabled(false);
    }

    View space(int dp) {
        View v = new View(this);
        v.setLayoutParams(Ui.lp(Ui.dp(this, dp), 1));
        return v;
    }

    private void loadWorld() {
        title.setText("Loading the world…");
        new Thread(new Runnable() {
            public void run() {
                final World w = World.get(getApplicationContext());
                ui.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        world = w;
                        map.setWorld(w);
                        nextQuestion();
                    }
                });
            }
        }, "game-load").start();
    }

    // ------------------------------------------------------------- question

    void nextQuestion() {
        target = pick();
        misses = 0;
        hintsUsed = 0;
        answered = false;
        info.setVisibility(View.GONE);
        info.setText("");
        status.setText("");
        status.setTextColor(Ui.DIM);
        map.clearMarks();
        setButtonsEnabled(true);
        nextBtn.setEnabled(false);
        revealBtn.setText("Give up");
        startQuestion();
    }

    private Country pick() {
        List<Country> queue = progress.queue(mode(), world.quiz);
        for (int i = 0; i < queue.size(); i++) {
            Country c = queue.get(i);
            if (!recent.contains(c.key())) {
                remember(c);
                return c;
            }
        }
        Country c = queue.get(0);
        remember(c);
        return c;
    }

    private void remember(Country c) {
        recent.add(c.key());
        while (recent.size() > 8) recent.remove(0);
    }

    void setButtonsEnabled(boolean on) {
        hintBtn.setEnabled(on);
        revealBtn.setEnabled(on);
        nextBtn.setEnabled(on);
    }

    private void onHintPressed() {
        if (answered) return;
        if (showHint(hintsUsed)) {
            hintsUsed++;
        }
    }

    /** Called by subclasses once the player has named the country. */
    void solved() {
        answered = true;
        asked++;
        boolean firstTry = misses == 0 && hintsUsed == 0;
        if (firstTry) firstTries++;
        progress.record(mode(), target, firstTry, misses, hintsUsed);
        status.setTextColor(Ui.GREEN);
        status.setText(firstTry ? "Right first time ✓" : "Correct ✓");
        showFact();
        hintBtn.setEnabled(false);
        revealBtn.setEnabled(false);
        nextBtn.setEnabled(true);
        updateScore();
    }

    private void giveUp() {
        if (answered) return;
        answered = true;
        asked++;
        progress.recordGiveUp(mode(), target, misses, hintsUsed);
        revealAnswer();
        status.setTextColor(Ui.AMBER);
        status.setText("It was " + target.name + " - it will come round again.");
        showFact();
        hintBtn.setEnabled(false);
        revealBtn.setEnabled(false);
        nextBtn.setEnabled(true);
        updateScore();
    }

    private void showFact() {
        String fact = target.facts.length > 0
                ? target.facts[random.nextInt(target.facts.length)] : "";
        info.setText(target.name + " · " + fact);
        info.setTextColor(Ui.TEXT);
        info.setVisibility(View.VISIBLE);
    }

    void say(String msg, int color) {
        status.setTextColor(color);
        status.setText(msg);
    }

    void showInfo(String msg, int color) {
        info.setText(msg);
        info.setTextColor(color);
        info.setVisibility(View.VISIBLE);
    }

    private void updateScore() {
        kicker.setText(heading() + "  ·  " + asked + " asked  ·  "
                + firstTries + " right first time");
    }

    int askedCount() {
        return asked;
    }

    int firstTryCount() {
        return firstTries;
    }
}
