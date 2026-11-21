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
    private final android.content.SharedPreferences display;

    SessionMeter(Context context) {
        super(context, "sessions.db", null, 2);
        display = context.getSharedPreferences("session_display", Context.MODE_PRIVATE);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY AUTOINCREMENT, active INTEGER NOT NULL,"
                + " title TEXT NOT NULL DEFAULT '', started INTEGER NOT NULL, completed INTEGER)");
        db.execSQL("CREATE TABLE point (session_id INTEGER NOT NULL, elapsed INTEGER NOT NULL, wall INTEGER NOT NULL,"
                + " mobile INTEGER NOT NULL, apps TEXT, PRIMARY KEY(session_id, elapsed))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from != 1 || to != 2) throw new IllegalStateException("Sayaç kaydı sürümü tanınmadı");
        db.execSQL("ALTER TABLE session RENAME TO old_session");
        db.execSQL("ALTER TABLE point RENAME TO old_point");
        onCreate(db);
        db.execSQL("INSERT INTO session(id, active, started, completed)"
                + " SELECT 1, active, (SELECT wall FROM old_point ORDER BY elapsed LIMIT 1),"
                + " CASE WHEN active = 0 THEN (SELECT wall FROM old_point ORDER BY elapsed DESC LIMIT 1) END"
                + " FROM old_session WHERE EXISTS(SELECT 1 FROM old_point)");
        db.execSQL("INSERT INTO point SELECT 1, elapsed, wall, mobile, apps FROM old_point");
        db.execSQL("DROP TABLE old_point");
        db.execSQL("DROP TABLE old_session");
    }

    long begin(Point point) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("UPDATE session SET active = 0 WHERE active = 1");
            ContentValues row = new ContentValues();
            row.put("active", 1);
            row.put("started", point.wall);
            long id = db.insertOrThrow("session", null, row);
            insert(db, id, point);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    void record(long id, Point point) {
        insert(getWritableDatabase(), id, point);
    }

    void end(long id, Point point) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("point", "session_id = ? AND elapsed >= ?", new String[]{Long.toString(id), Long.toString(point.elapsed)});
            insert(db, id, point);
            db.execSQL("UPDATE session SET active = 0, completed = ? WHERE id = ?", new Object[]{point.wall, id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    State read() {
        return read(0);
    }

    State read(long wanted) {
        List<Point> points = new ArrayList<>();
        boolean active = false;
        boolean interrupted = false;
        long id = 0;
        String title = "";
        SQLiteDatabase db = getReadableDatabase();
        db.beginTransactionNonExclusive();
        try {
            try (Cursor row = db.rawQuery("SELECT id, active, title, completed FROM session "
                    + (wanted > 0 ? "WHERE id = ? " : "WHERE id > ? ") + "ORDER BY id DESC LIMIT 1",
                    new String[]{Long.toString(wanted > 0 ? wanted : display.getLong("reset_through", 0))})) {
                if (row.moveToFirst()) {
                    id = row.getLong(0);
                    active = row.getInt(1) != 0;
                    title = row.getString(2);
                    interrupted = !active && row.isNull(3);
                }
            }
            try (Cursor rows = db.rawQuery("SELECT elapsed, wall, mobile, apps FROM point WHERE session_id = ? ORDER BY elapsed",
                    new String[]{Long.toString(id)})) {
                while (rows.moveToNext()) {
                    points.add(new Point(rows.getLong(0), rows.getLong(1), rows.getLong(2),
                            rows.isNull(3) ? null : decode(rows.getString(3))));
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new State(points, active, id, title, interrupted);
    }

    List<Entry> history() {
        List<Entry> entries = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().rawQuery(
                "SELECT id, title, started FROM session WHERE active = 0 ORDER BY id DESC", null)) {
            while (rows.moveToNext()) entries.add(new Entry(rows.getLong(0), rows.getString(1), rows.getLong(2)));
        }
        return entries;
    }

    void rename(long id, String title) {
        ContentValues value = new ContentValues();
        value.put("title", title);
        getWritableDatabase().update("session", value, "id = ?", new String[]{Long.toString(id)});
    }

    void resetDisplay(long id) {
        getWritableDatabase().execSQL("UPDATE session SET active = 0 WHERE id = ? AND active = 1", new Object[]{id});
        display.edit().putLong("reset_through", Math.max(id, display.getLong("reset_through", 0))).apply();
    }

    void deleteHistory(long id) {
        SQLiteDatabase db = getWritableDatabase();
        String selection = id == 0 ? "active = 0" : "active = 0 AND id = ?";
        String[] arguments = id == 0 ? null : new String[]{Long.toString(id)};
        db.beginTransaction();
        try {
            db.delete("point", "session_id IN (SELECT id FROM session WHERE " + selection + ")", arguments);
            db.delete("session", selection, arguments);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void insert(SQLiteDatabase db, long id, Point point) {
        ContentValues values = new ContentValues();
        values.put("session_id", id);
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
        final long id;
        final String title;
        final boolean interrupted;

        State(List<Point> points, boolean storedActive) {
            this(points, storedActive, 0, "", false);
        }

        State(List<Point> points, boolean storedActive, long id, String title, boolean interrupted) {
            this.points = points;
            this.storedActive = storedActive;
            this.id = id;
            this.title = title;
            this.interrupted = interrupted;
        }

        long hourlyEstimate() {
            List<Segment> segments = series(TOTAL);
            long covered = 0;
            long used = 0;
            for (Segment part : segments) {
                covered += part.end - part.start;
                used += part.bytes;
            }
            return covered < 60_000L ? -1 : Math.round(used * (double) Usage.HOUR / covered);
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

    static final class Entry {
        final long id;
        final String title;
        final long started;

        Entry(long id, String title, long started) {
            this.id = id;
            this.title = title;
            this.started = started;
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
