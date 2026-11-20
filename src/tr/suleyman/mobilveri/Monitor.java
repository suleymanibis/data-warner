package tr.suleyman.mobilveri;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Monitor extends BroadcastReceiver {
    private static final String SAMPLE = "tr.suleyman.mobilveri.SAMPLE";
    private static final String REPORT = "tr.suleyman.mobilveri.REPORT";
    private static final String WINDOW = "report_time";
    static final String SUMMARY_CHANNEL = "summary";
    static final String ALERT_CHANNEL = "threshold";
    private static final int FLAGS = PendingIntent.FLAG_IMMUTABLE;
    private static final long SAMPLE_INTERVAL = 15 * 60_000L;
    private static final long MIN_SAMPLE_INTERVAL = 60_000L;
    private static final long HISTORY = 2 * AlertRule.MAX_MINUTES * 60_000L;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    static void start(Context context) {
        channels(context);
        if (!Usage.allowed(context)) return;
        if (existing(context, SAMPLE) == null) scheduleSample(context);
        // A PendingIntent can outlive an exact alarm canceled after a permission change.
        scheduleReport(context);
        Context app = context.getApplicationContext();
        WORK.execute(() -> safely(() -> sample(app, false)));
    }

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Context app = context.getApplicationContext();
        if (!Usage.allowed(app)) {
            cancel(app, SAMPLE);
            cancel(app, REPORT);
            return;
        }
        channels(app);
        if (REPORT.equals(action)) {
            scheduleReport(app);
        } else if (SAMPLE.equals(action)) {
            scheduleSample(app);
        } else {
            cancel(app, SAMPLE);
            cancel(app, REPORT);
            scheduleSample(app);
            scheduleReport(app);
        }
        PendingResult result = goAsync();
        WORK.execute(() -> {
            try {
                if (REPORT.equals(action)) {
                    safely(() -> report(app, intent.getLongExtra(WINDOW, 0)));
                }
                safely(() -> sample(app, true));
            } finally {
                result.finish();
            }
        });
    }

    private static void safely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException failure) {
            Log.w("MobilVeri", "Arka plan ölçümü tamamlanamadı", failure);
        }
    }

    private static PendingIntent existing(Context context, String action) {
        return pending(context, action, 0, PendingIntent.FLAG_NO_CREATE);
    }

    private static PendingIntent pending(Context context, String action, long time, int flag) {
        Intent intent = new Intent(context, Monitor.class).setAction(action);
        if (REPORT.equals(action)) intent.putExtra(WINDOW, time);
        return PendingIntent.getBroadcast(context, 0, intent, FLAGS | PendingIntent.FLAG_ONE_SHOT | flag);
    }

    private static void cancel(Context context, String action) {
        PendingIntent old = existing(context, action);
        if (old != null) {
            context.getSystemService(AlarmManager.class).cancel(old);
            old.cancel();
        }
    }

    private static void scheduleSample(Context context) {
        context.getSystemService(AlarmManager.class).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + SAMPLE_INTERVAL,
                pending(context, SAMPLE, 0, PendingIntent.FLAG_UPDATE_CURRENT));
    }

    private static void scheduleReport(Context context) {
        long next = nextReport(ZonedDateTime.now());
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent report = pending(context, REPORT, next, PendingIntent.FLAG_UPDATE_CURRENT);
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, report);
                return;
            } catch (SecurityException ignored) {
                // The device can revoke exact alarm access while scheduling.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, report);
    }

    static long nextReport(ZonedDateTime now) {
        long hour = (now.getHour() / 6 + 1) * 6L;
        ZonedDateTime next = now.toLocalDate().atStartOfDay().plusHours(hour).atZone(now.getZone());
        if (!next.isAfter(now)) next = next.toLocalDateTime().plusHours(6).atZone(now.getZone());
        return next.toInstant().toEpochMilli();
    }

    private static void report(Context context, long scheduled) {
        long now = System.currentTimeMillis();
        if (scheduled == 0 || now - scheduled >= 6 * Usage.HOUR || now < scheduled) return;
        SharedPreferences prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
        if (prefs.getLong("last_report", 0) == scheduled) return;
        ZonedDateTime slot = Instant.ofEpochMilli(scheduled).atZone(ZoneId.systemDefault());
        boolean midnight = slot.getHour() == 0;
        long start = Usage.startOf(midnight ? slot.toLocalDate().minusDays(1) : slot.toLocalDate(), slot.getZone());
        long bytes = Usage.total(context, start, midnight ? scheduled : now);
        if (notify(context, 1, SUMMARY_CHANNEL, midnight ? "Dünün mobil veri kullanımı" : "Bugünkü mobil veri kullanımı",
                Usage.format(bytes) + " • İnternet paylaşımı dahil")) {
            prefs.edit().putLong("last_report", scheduled).apply();
        }
    }

    private static void sample(Context context, boolean background) {
        long elapsed = SystemClock.elapsedRealtime();
        long wall = System.currentTimeMillis();
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        try (Samples samples = new Samples(context)) {
            SQLiteDatabase db = samples.getWritableDatabase();
            if (boot == -1) {
                db.delete("samples", "elapsed > ? OR wall < ?",
                        new String[]{Long.toString(elapsed), Long.toString(wall - elapsed - 5000)});
            }
            db.delete("samples", "boot != ? OR elapsed < ? OR elapsed > ?",
                    new String[]{Integer.toString(boot), Long.toString(elapsed - HISTORY), Long.toString(elapsed)});
            try (Cursor latest = db.rawQuery("SELECT elapsed FROM samples ORDER BY elapsed DESC LIMIT 1", null)) {
                if (latest.moveToFirst() && elapsed - latest.getLong(0) < MIN_SAMPLE_INTERVAL) return;
            }
            long mobile = Usage.mobileCounter();
            AlertRule rule = AlertRule.load(context);
            long android = -1;
            try {
                // This independent counter includes tethering collected by Android.
                // Compare deltas independently to avoid adding transferred bytes twice.
                android = Usage.total(context, 0, wall);
            } catch (RuntimeException unavailable) {
                Log.d("MobilVeri", "Sistem sayacı geçici olarak kullanılamıyor", unavailable);
            }
            Window live = new Window(mobile, rule);
            Window system = new Window(android, rule);
            try (Cursor rows = db.query("samples", new String[]{"elapsed", "wall", "mobile", "android"},
                    null, null, null, null, "elapsed DESC")) {
                while (rows.moveToNext()) {
                    long age = elapsed - rows.getLong(0);
                    live.add(age, rows.getLong(2));
                    if (Math.abs(wall - rows.getLong(1) - age) > 2 * MIN_SAMPLE_INTERVAL) {
                        system.discontinuous = true;
                    }
                    system.add(age, rows.getLong(3));
                }
            }
            if (mobile < 0 && android < 0) return;
            ContentValues values = new ContentValues();
            values.put("elapsed", elapsed);
            values.put("wall", wall);
            values.put("boot", boot);
            values.put("mobile", mobile);
            values.put("android", android);
            db.insertWithOnConflict("samples", null, values, SQLiteDatabase.CONFLICT_REPLACE);

            SharedPreferences prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
            AlertRule latestRule = AlertRule.load(context);
            if (latestRule.bytes != rule.bytes || latestRule.minutes != rule.minutes) return;
            boolean active = prefs.getInt("alert_boot", Integer.MIN_VALUE) == boot
                    && prefs.getLong("alert_sent_bytes", AlertRule.DEFAULT_BYTES) == rule.bytes
                    && prefs.getInt("alert_sent_minutes", AlertRule.DEFAULT_MINUTES) == rule.minutes
                    && prefs.getBoolean("high", false);
            boolean measured = live.lower > rule.bytes;
            if (!active && (measured || system.lower > rule.bytes)) {
                String title = measured ? "Mobil veri sınırı aşıldı" : "Tahmini mobil veri sınırı aşıldı";
                String amount = Usage.format(measured ? live.lower : system.lower);
                String detail = " • Sınır " + rule.limit();
                if (notify(context, 2, ALERT_CHANNEL, title, amount + detail)) {
                    prefs.edit().putInt("alert_boot", boot).putLong("alert_sent_bytes", rule.bytes)
                            .putInt("alert_sent_minutes", rule.minutes).putBoolean("high", true).apply();
                }
            } else if (active && live.belowLimit() && system.belowLimit()) {
                prefs.edit().putBoolean("high", false).apply();
            }
        }
        if (background && existing(context, REPORT) == null) scheduleReport(context);
    }

    private static long delta(long current, long previous) {
        return previous < 0 || current < previous ? -1 : current - previous;
    }

    static final class Window {
        final long current;
        final AlertRule rule;
        long newest;
        long lower = -1;
        long upper = -1;
        boolean discontinuous;

        Window(long current) {
            this(current, new AlertRule(AlertRule.DEFAULT_BYTES, AlertRule.DEFAULT_MINUTES));
        }

        Window(long current, AlertRule rule) {
            this.current = current;
            this.rule = rule;
            newest = current;
        }

        void add(long age, long previous) {
            if (discontinuous || current < 0 || previous < 0) return;
            if (previous > newest) {
                discontinuous = true;
                return;
            }
            newest = previous;
            long change = delta(current, previous);
            if (age <= rule.duration()) lower = Math.max(lower, change);
            if (age >= rule.duration() && upper < 0) upper = change;
        }

        boolean belowLimit() {
            return current < 0 || (upper >= 0 && upper <= rule.bytes);
        }
    }

    private static void channels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(SUMMARY_CHANNEL,
                "Günlük kullanım özeti", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(ALERT_CHANNEL,
                "Mobil veri sınırı uyarısı", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static boolean notify(Context context, int id, String channel, String title, String body) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()
                || manager.getNotificationChannel(channel).getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return false;
        }
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, Dashboard.class), FLAGS);
        Notification notification = new Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_data).setContentTitle(title).setContentText(body)
                .setContentIntent(open).setAutoCancel(true).build();
        manager.notify(id, notification);
        return true;
    }

    private static final class Samples extends SQLiteOpenHelper {
        Samples(Context context) {
            super(context, "measurements.db", null, 1);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE samples (elapsed INTEGER PRIMARY KEY, wall INTEGER NOT NULL,"
                    + " boot INTEGER NOT NULL, mobile INTEGER NOT NULL, android INTEGER NOT NULL)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
            throw new IllegalStateException("Bilinmeyen veri sürümü");
        }
    }
}
