package tr.suleyman.mobilveri;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class SessionMeter extends SQLiteOpenHelper {
    static final int TOTAL = Integer.MIN_VALUE;

    SessionMeter(Context context) {
        super(context, "sessions.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY CHECK(id = 1), active INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE point (elapsed INTEGER PRIMARY KEY, wall INTEGER NOT NULL,"
                + " mobile INTEGER NOT NULL, apps TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        throw new IllegalStateException("Sayaç kaydı sürümü tanınmadı");
    }

    void begin(Point point) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("point", null, null);
            insert(db, point);
            db.execSQL("INSERT OR REPLACE INTO session(id, active) VALUES(1, 1)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void record(Point point) {
        insert(getWritableDatabase(), point);
    }

    void end(Point point) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("point", "elapsed >= ?", new String[]{Long.toString(point.elapsed)});
            insert(db, point);
            db.execSQL("UPDATE session SET active = 0 WHERE id = 1");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    State read() {
        List<Point> points = new ArrayList<>();
        boolean active;
        SQLiteDatabase db = getReadableDatabase();
        db.beginTransactionNonExclusive();
        try {
            try (Cursor row = db.rawQuery("SELECT active FROM session WHERE id = 1", null)) {
                active = row.moveToFirst() && row.getInt(0) != 0;
            }
            try (Cursor rows = db.rawQuery("SELECT elapsed, wall, mobile, apps FROM point ORDER BY elapsed", null)) {
                while (rows.moveToNext()) {
                    points.add(new Point(rows.getLong(0), rows.getLong(1), rows.getLong(2),
                            rows.isNull(3) ? null : decode(rows.getString(3))));
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new State(points, active);
    }

    private static void insert(SQLiteDatabase db, Point point) {
        ContentValues values = new ContentValues();
        values.put("elapsed", point.elapsed);
        values.put("wall", point.wall);
        values.put("mobile", point.mobile);
        if (point.apps != null) values.put("apps", encode(point.apps));
        long row = db.insertWithOnConflict("point", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (row == -1) throw new IllegalStateException("Sayaç örneği kaydedilemedi");
    }

    private static String encode(Map<Integer, Long> counts) {
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<Integer, Long> count : counts.entrySet()) {
                json.put(Integer.toString(count.getKey()), count.getValue());
            }
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return json.toString();
    }

    private static Map<Integer, Long> decode(String json) {
        Map<Integer, Long> counts = new HashMap<>();
        try {
            JSONObject entries = new JSONObject(json);
            for (Iterator<String> keys = entries.keys(); keys.hasNext();) {
                String key = keys.next();
                counts.put(Integer.parseInt(key), entries.getLong(key));
            }
        } catch (JSONException | NumberFormatException error) {
            throw new IllegalStateException("Sayaç örneği okunamadı", error);
        }
        return counts;
    }

    static String duration(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return seconds < 3600 ? String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    static final class Point {
        final long elapsed;
        final long wall;
        final long mobile;
        final Map<Integer, Long> apps;

        Point(long elapsed, long wall, long mobile, Map<Integer, Long> apps) {
            this.elapsed = elapsed;
            this.wall = wall;
            this.mobile = mobile;
            this.apps = apps;
        }

        static Point capture() {
            return new Point(SystemClock.elapsedRealtime(), System.currentTimeMillis(), Usage.mobileCounter(), null);
        }

        long value(int uid) {
            if (uid == TOTAL) return mobile;
            return apps == null ? -1 : apps.getOrDefault(uid, 0L);
        }
    }

    static final class State {
        final List<Point> points;
        final boolean storedActive;

        State(List<Point> points, boolean storedActive) {
            this.points = points;
            this.storedActive = storedActive;
        }

        long duration() {
            return points.isEmpty() ? 0 : last().elapsed - points.get(0).elapsed;
        }

        Point last() {
            return points.get(points.size() - 1);
        }

        long amount(int uid) {
            long value = 0;
            List<Segment> series = series(uid);
            for (Segment segment : series) value += segment.bytes;
            return series.isEmpty() ? -1 : value;
        }

        Map<Integer, Long> appAmounts() {
            Map<Integer, Long> result = new HashMap<>();
            Map<Integer, Long> before = null;
            for (Point point : points) {
                if (point.apps == null) continue;
                if (before != null) {
                    for (Map.Entry<Integer, Long> entry : point.apps.entrySet()) {
                        int uid = entry.getKey();
                        long gain = entry.getValue() - before.getOrDefault(uid, 0L);
                        if (uid != Usage.HOTSPOT && gain > 0) result.merge(uid, gain, Long::sum);
                    }
                }
                before = point.apps;
            }
            return result;
        }

        List<Segment> series(int uid) {
            List<Segment> result = new ArrayList<>();
            if (points.size() < 2) return result;
            if (uid == Usage.HOTSPOT && points.stream().noneMatch(point ->
                    point.apps != null && point.apps.containsKey(Usage.HOTSPOT))) return result;
            long origin = points.get(0).elapsed;
            long stride = Math.max(15_000L, (duration() + 11) / 12);
            Point before = null;
            long groupStart = 0;
            long groupEnd = 0;
            long bytes = 0;
            for (Point point : points) {
                long value = point.value(uid);
                if (value < 0) continue;
                if (before != null) {
                    long gain = value - before.value(uid);
                    if (gain >= 0) {
                        if (groupStart == groupEnd) groupStart = before.elapsed - origin;
                        groupEnd = point.elapsed - origin;
                        bytes += gain;
                    }
                    if (groupEnd > groupStart && (gain < 0 || groupEnd - groupStart >= stride)) {
                        result.add(new Segment(groupStart, groupEnd, bytes));
                        groupStart = groupEnd;
                        bytes = 0;
                    }
                }
                before = point;
            }
            if (groupEnd > groupStart) result.add(new Segment(groupStart, groupEnd, bytes));
            return result;
        }
    }

    static final class Segment {
        final long start;
        final long end;
        final long bytes;

        Segment(long start, long end, long bytes) {
            this.start = start;
            this.end = end;
            this.bytes = bytes;
        }
    }
}
