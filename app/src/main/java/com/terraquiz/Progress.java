package com.terraquiz;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the player knows, per game.
 *
 * The rule the queue implements: a country you get right first time, with no
 * hints, has its cycle bumped and drops behind every country on a lower cycle -
 * so it only comes back once everything else has been answered first time too.
 * Anything you got wrong stays at the front, hardest first.
 */
final class Progress extends SQLiteOpenHelper {

    static final int MODE_FIND = 0;   // point at the country
    static final int MODE_NAME = 1;   // type the country

    private static Progress sInstance;

    static synchronized Progress get(Context ctx) {
        if (sInstance == null) sInstance = new Progress(ctx.getApplicationContext());
        return sInstance;
    }

    private Progress(Context ctx) {
        super(ctx, "progress.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE p(" +
                "mode INTEGER NOT NULL," +
                "k TEXT NOT NULL," +
                "cycle INTEGER NOT NULL DEFAULT 0," +
                "seen INTEGER NOT NULL DEFAULT 0," +
                "solved INTEGER NOT NULL DEFAULT 0," +
                "firsts INTEGER NOT NULL DEFAULT 0," +
                "misses INTEGER NOT NULL DEFAULT 0," +
                "hints INTEGER NOT NULL DEFAULT 0," +
                "last INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(mode,k))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        db.execSQL("DROP TABLE IF EXISTS p");
        onCreate(db);
    }

    static final class Row {
        String key;
        int cycle, seen, solved, firsts, misses, hints;
        long last;

        /** Wrong answers and hints, weighted so a country you keep missing rises. */
        int struggle() {
            return misses * 2 + hints;
        }
    }

    Map<String, Row> rows(int mode) {
        Map<String, Row> out = new HashMap<String, Row>();
        Cursor c = getReadableDatabase().query("p",
                new String[]{"k", "cycle", "seen", "solved", "firsts", "misses", "hints", "last"},
                "mode=?", new String[]{String.valueOf(mode)}, null, null, null);
        try {
            while (c.moveToNext()) {
                Row r = new Row();
                r.key = c.getString(0);
                r.cycle = c.getInt(1);
                r.seen = c.getInt(2);
                r.solved = c.getInt(3);
                r.firsts = c.getInt(4);
                r.misses = c.getInt(5);
                r.hints = c.getInt(6);
                r.last = c.getLong(7);
                out.put(r.key, r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Countries in the order they should be asked: least-mastered first, and
     * within that the ones that have been missed most.
     */
    List<Country> queue(int mode, List<Country> pool) {
        final Map<String, Row> rows = rows(mode);
        List<Country> out = new ArrayList<Country>(pool);
        Collections.sort(out, new Comparator<Country>() {
            public int compare(Country a, Country b) {
                Row ra = rows.get(a.key()), rb = rows.get(b.key());
                int ca = ra == null ? 0 : ra.cycle, cb = rb == null ? 0 : rb.cycle;
                if (ca != cb) return ca - cb;
                int sa = ra == null ? 0 : ra.struggle(), sb = rb == null ? 0 : rb.struggle();
                if (sa != sb) return sb - sa;
                long la = ra == null ? 0 : ra.last, lb = rb == null ? 0 : rb.last;
                if (la != lb) return la < lb ? -1 : 1;
                return a.name.compareTo(b.name);
            }
        });
        return out;
    }

    /** Records one finished question. */
    void record(int mode, Country c, boolean firstTry, int misses, int hints) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO p(mode,k) VALUES(?,?)",
                new Object[]{mode, c.key()});
        db.execSQL("UPDATE p SET seen=seen+1, solved=solved+1, misses=misses+?, hints=hints+?," +
                        " firsts=firsts+?, cycle=cycle+?, last=? WHERE mode=? AND k=?",
                new Object[]{misses, hints, firstTry ? 1 : 0, firstTry ? 1 : 0,
                        System.currentTimeMillis(), mode, c.key()});
    }

    /** Records a question the player gave up on: it stays at the front of the queue. */
    void recordGiveUp(int mode, Country c, int misses, int hints) {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO p(mode,k) VALUES(?,?)",
                new Object[]{mode, c.key()});
        db.execSQL("UPDATE p SET seen=seen+1, misses=misses+?, hints=hints+?, last=?" +
                        " WHERE mode=? AND k=?",
                new Object[]{Math.max(1, misses), hints, System.currentTimeMillis(),
                        mode, c.key()});
    }

    void reset(int mode) {
        getWritableDatabase().delete("p", "mode=?", new String[]{String.valueOf(mode)});
    }

    void resetAll() {
        getWritableDatabase().delete("p", null, null);
    }

    static final class Summary {
        int known;       // answered first time at least once
        int attempted;
        int firsts;
        int misses;
        int total;
        List<String> weakest = new ArrayList<String>();
    }

    Summary summary(int mode, List<Country> pool) {
        Map<String, Row> rows = rows(mode);
        Summary s = new Summary();
        s.total = pool.size();
        List<Row> hard = new ArrayList<Row>();
        Map<String, String> names = new HashMap<String, String>();
        for (int i = 0; i < pool.size(); i++) {
            Country c = pool.get(i);
            names.put(c.key(), c.name);
            Row r = rows.get(c.key());
            if (r == null) continue;
            s.attempted++;
            s.firsts += r.firsts;
            s.misses += r.misses;
            if (r.cycle > 0) s.known++;
            if (r.struggle() > 0) hard.add(r);
        }
        Collections.sort(hard, new Comparator<Row>() {
            public int compare(Row a, Row b) {
                return b.struggle() - a.struggle();
            }
        });
        for (int i = 0; i < hard.size() && i < 8; i++) {
            Row r = hard.get(i);
            String nm = names.get(r.key);
            if (nm != null) s.weakest.add(nm + " (" + r.misses + " wrong)");
        }
        return s;
    }
}
