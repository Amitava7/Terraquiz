package com.terraquiz;

import android.os.Bundle;

/**
 * Game 1 - a country is named, the player taps it on the map.
 *
 * A wrong tap names whatever was tapped instead and leaves it marked in red, so
 * the near misses stay visible. Hints walk through continent, coastline,
 * neighbours, size, capital and first letter, and the last one draws a circle
 * around the answer.
 */
public final class FindActivity extends GameActivity {

    private final StringBuilder shown = new StringBuilder();

    @Override
    int mode() {
        return Progress.MODE_FIND;
    }

    @Override
    String heading() {
        return "Find the country";
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        map.setOnTapListener(new MapView.OnTapListener() {
            public void onTap(Country c) {
                onMapTap(c);
            }
        });
    }

    @Override
    void startQuestion() {
        shown.setLength(0);
        title.setText(target.name);
        say("Tap it on the map. Pinch to zoom.", Ui.DIM);
        map.flyHome(true);
    }

    private void onMapTap(Country c) {
        if (answered || target == null) return;
        if (c == null) {
            say("That is open water - tap on land.", Ui.DIM);
            return;
        }
        if (c == target || isTerritoryOf(c, target)) {
            map.clearMarks();
            map.addMark(target, Ui.GREEN_FILL, Ui.GREEN, target.name, Ui.GREEN);
            map.flyTo(target.mainBounds, 4f, true);
            solved();
        } else {
            misses++;
            map.addMark(c, Ui.RED_FILL, Ui.RED, c.display(), Ui.RED);
            say("That is " + c.display() + ". Try again.", Ui.RED);
        }
    }

    /** Tapping Greenland counts when the question was Denmark. */
    private static boolean isTerritoryOf(Country tapped, Country owner) {
        if (tapped.sovereign.length() == 0) return false;
        String a = Names.normalise(tapped.sovereign), b = Names.normalise(owner.name);
        return a.length() > 0 && b.length() > 0 && (a.startsWith(b) || b.startsWith(a));
    }

    @Override
    boolean showHint(int index) {
        if (index < target.hints.length) {
            if (shown.length() > 0) shown.append('\n');
            shown.append("• ").append(target.hints[index]);
            showInfo(shown.toString(), Ui.AMBER);
            say("Hint " + (index + 1), Ui.AMBER);
            return true;
        }
        if (index == target.hints.length) {
            map.showHintCircle(target, 11f);
            if (shown.length() > 0) shown.append('\n');
            shown.append("• It is somewhere inside the circle on the map.");
            showInfo(shown.toString(), Ui.AMBER);
            say("Last hint", Ui.AMBER);
            return true;
        }
        say("That is every hint - try \"Give up\" to see it.", Ui.DIM);
        return false;
    }

    @Override
    void revealAnswer() {
        map.clearMarks();
        map.addMark(target, Ui.AMBER_FILL, Ui.AMBER, target.name, Ui.AMBER);
        map.flyTo(target.mainBounds, 4f, true);
    }
}
