package com.terraquiz;

import java.util.List;

/**
 * Forgiving comparison of what the player typed against a country's names.
 *
 * A guess is accepted when it is close enough to one of the target's spellings
 * AND no other country on the map is a closer match, so "Iraq" is never taken
 * as a typo for "Iran" even though one letter separates them.
 */
final class Names {

    private Names() {
    }

    /** Lower case, accents removed, everything but letters and digits dropped. */
    static String normalise(String s) {
        String flat = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        StringBuilder b = new StringBuilder(flat.length());
        for (int i = 0; i < flat.length(); i++) {
            char c = Character.toLowerCase(flat.charAt(i));
            if (c >= 0x0300 && c <= 0x036F) continue;   // combining accent
            switch (c) {
                case '\u00F8': c = 'o'; break;         // o with stroke
                case '\u0111': c = 'd'; break;         // d with stroke
                case '\u0142': c = 'l'; break;         // l with stroke
                case '\u00FE': c = 't'; break;         // thorn
                case '\u00F0': c = 'd'; break;         // eth
                case '\u00E6': b.append("ae"); continue;
                case '\u0153': b.append("oe"); continue;
                case '\u00DF': b.append("ss"); continue;
                default: break;
            }
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    /** How many single-character edits separate two strings (capped at {@code max}). */
    static int distance(String a, String b, int max) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > max) return max + 1;
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int best = cur[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                cur[j] = v;
                if (v < best) best = v;
            }
            if (best > max) return max + 1;
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }

    /**
     * Edits tolerated for a guess of this length. Short names get a free edit
     * too: the "nobody else is closer" rule in {@link #accepts} is what keeps
     * Iraq and Iran apart, not strictness about spelling.
     */
    static int tolerance(int len) {
        if (len <= 6) return 1;
        if (len <= 12) return 2;
        return 3;
    }

    /** Smallest edit distance between the guess and any spelling of the country. */
    static int score(String guess, Country c) {
        int best = distance(guess, normalise(c.name), 99);
        for (int i = 0; i < c.aliases.length; i++) {
            int d = distance(guess, normalise(c.aliases[i]), best);
            if (d < best) best = d;
        }
        return best;
    }

    /**
     * @return true when the typed answer should count as naming {@code target}.
     */
    static boolean accepts(String typed, Country target, List<Country> others) {
        String guess = normalise(typed);
        if (guess.length() == 0) return false;
        int mine = score(guess, target);
        if (mine == 0) return true;
        if (mine > tolerance(guess.length())) return false;
        for (int i = 0, n = others.size(); i < n; i++) {
            Country o = others.get(i);
            if (o == target) continue;
            if (score(guess, o) <= mine) return false;  // someone else fits as well
        }
        return true;
    }

    /** "France" with 2 letters shown -> "F r · · · ·" */
    static String mask(String name, int revealed) {
        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (b.length() > 0) b.append(' ');
            if (c == ' ' || c == '-') {
                b.append(c == ' ' ? '/' : '-');
            } else if (shown < revealed) {
                b.append(c);
                shown++;
            } else {
                b.append('·');
            }
        }
        return b.toString();
    }

    /** Letters (not spaces) in a name, i.e. how many hints can be given. */
    static int letterCount(String name) {
        int n = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != ' ' && c != '-') n++;
        }
        return n;
    }
}
