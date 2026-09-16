package com.terraquiz;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Game 2 - a country is highlighted, the player types its name.
 *
 * Spelling is forgiving: near misses count as correct as long as no other
 * country is a closer match. Each hint uncovers one more letter from the left.
 */
public final class NameActivity extends GameActivity {

    private EditText input;
    private Button check;
    private int revealed;

    @Override
    int mode() {
        return Progress.MODE_NAME;
    }

    @Override
    String heading() {
        return "Name the country";
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout row = Ui.row(this);
        input = new EditText(this);
        input.setHint("Type the country…");
        input.setHintTextColor(Ui.DIM);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_DONE
                        || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    submit();
                    return true;
                }
                return false;
            }
        });
        check = Ui.button(this, "Check", Ui.GREEN, 0xFF06121C);
        check.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                submit();
            }
        });
        row.addView(input, Ui.lp(0, Ui.WRAP, 1f));
        row.addView(space(8));
        row.addView(check, Ui.lp(Ui.WRAP, Ui.WRAP));
        controls.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP));
    }

    @Override
    void startQuestion() {
        revealed = 0;
        title.setText("Which country is this?");
        say("Type its name. Spelling can be a bit off.", Ui.DIM);
        input.setText("");
        input.setEnabled(true);
        check.setEnabled(true);
        map.clearMarks();
        map.addMark(target, Ui.AMBER_FILL, Ui.AMBER, null, 0);
        map.flyTo(target.mainBounds, 5f, true);
    }

    private void submit() {
        if (answered || target == null) return;
        Editable e = input.getText();
        String typed = e == null ? "" : e.toString().trim();
        if (typed.length() == 0) return;
        if (Names.accepts(typed, target, world.quiz)) {
            map.clearMarks();
            map.addMark(target, Ui.GREEN_FILL, Ui.GREEN, target.name, Ui.GREEN);
            input.setText(target.name);
            input.setEnabled(false);
            check.setEnabled(false);
            Ui.hideKeyboard(this, input);
            solved();
        } else {
            misses++;
            say("Not " + typed + ". Try again.", Ui.RED);
        }
    }

    @Override
    boolean showHint(int index) {
        int letters = Names.letterCount(target.name);
        if (revealed >= letters - 1) {
            say("That is as much as the hints will give away.", Ui.DIM);
            return false;
        }
        revealed++;
        showInfo(Names.mask(target.name, revealed), Ui.AMBER);
        say("Hint " + revealed + " of " + (letters - 1), Ui.AMBER);
        return true;
    }

    @Override
    void revealAnswer() {
        map.clearMarks();
        map.addMark(target, Ui.AMBER_FILL, Ui.AMBER, target.name, Ui.AMBER);
        input.setText(target.name);
        input.setEnabled(false);
        check.setEnabled(false);
        Ui.hideKeyboard(this, input);
    }
}
