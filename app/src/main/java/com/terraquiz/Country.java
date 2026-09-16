package com.terraquiz;

import android.graphics.Path;
import android.graphics.RectF;

/**
 * One feature of the world map. Coordinates are "map units": x = longitude,
 * y = -latitude, so y grows downwards like screen pixels do.
 */
final class Country {
    String name;
    String code;          // ISO alpha-2, "" for features without one
    String sovereign;     // non-empty when this is a territory of someone
    String[] aliases;     // spellings accepted when typing the name
    String[] facts;
    String[] hints;
    boolean quizzable;    // sovereign states we ask about
    int fame;             // 0 = hardest to miss, 255 = not asked about

    float labelX, labelY;
    final RectF bounds = new RectF();      // everything, incl. far-flung islands
    final RectF mainBounds = new RectF();  // the largest single landmass

    float[][] rings;      // flat x,y pairs, one array per ring
    Path path;            // full detail; the zoomed-out map is drawn from
                          // World.landCoarse instead

    /** Key used in the progress database. Stable across data rebuilds. */
    String key() {
        return code.length() == 2 ? code : name;
    }

    String display() {
        return sovereign.length() > 0 ? name + " (" + sovereign + ")" : name;
    }

    boolean contains(float x, float y) {
        if (!bounds.contains(x, y)) return false;
        boolean in = false;
        for (float[] r : rings) {
            if (ringCrossings(r, x, y)) in = !in;
        }
        return in;
    }

    /** True when a ray to +x crosses this ring an odd number of times. */
    private static boolean ringCrossings(float[] r, float px, float py) {
        boolean in = false;
        int n = r.length;
        float jx = r[n - 2], jy = r[n - 1];
        for (int i = 0; i < n; i += 2) {
            float ix = r[i], iy = r[i + 1];
            if ((iy > py) != (jy > py) && px < (jx - ix) * (py - iy) / (jy - iy) + ix) {
                in = !in;
            }
            jx = ix;
            jy = iy;
        }
        return in;
    }

    /** Squared distance from a point to the outline, for near-miss taps on tiny states. */
    float distance2(float px, float py) {
        float best = Float.MAX_VALUE;
        for (float[] r : rings) {
            for (int i = 0; i + 3 < r.length; i += 2) {
                float d = seg2(px, py, r[i], r[i + 1], r[i + 2], r[i + 3]);
                if (d < best) best = d;
            }
        }
        return best;
    }

    private static float seg2(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay;
        float len = dx * dx + dy * dy;
        float t = len == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len;
        if (t < 0) t = 0;
        else if (t > 1) t = 1;
        float qx = ax + t * dx - px, qy = ay + t * dy - py;
        return qx * qx + qy * qy;
    }
}
