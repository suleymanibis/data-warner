package tr.suleyman.mobilveri;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SessionService extends Service {
    static final int IDLE = 0;
    static final int STARTING = 1;
    static final int RUNNING = 2;
    static final int STOPPING = 3;
    static final String OPEN_SESSION = "tr.suleyman.mobilveri.OPEN_SESSION";
    private static final String CHANNEL = "session";
    private static final String START = "start_measurement";
    private static final String STOP = "stop_measurement";
    private static final AtomicInteger REVISION = new AtomicInteger();
    private static volatile int phase = IDLE;
    private static volatile String error;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private long anchor;
    private long started;

    static int phase() { return phase; }
    static int revision() { return REVISION.get(); }
    static String error() { return error; }

    static void toggle(Context context) {
        int before = phase;
        if (before != IDLE && before != RUNNING) return;
        boolean start = before == IDLE;
        SessionMeter.Point point = SessionMeter.Point.capture();
        Intent command = new Intent(context, SessionService.class).setAction(start ? START : STOP)
                .putExtra("elapsed", point.elapsed).putExtra("wall", point.wall).putExtra("mobile", point.mobile);
        error = null;
        updatePhase(start ? STARTING : STOPPING);
        try {
            if (start) context.startForegroundService(command);
            else context.startService(command);
        } catch (RuntimeException failure) {
            error = "Android sayaç komutunu başlatamadı.";
            Log.w("DataWarner", error, failure);
            updatePhase(before);
        }
    }

    @Override public int onStartCommand(Intent command, int flags, int startId) {
        if (command == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        SessionMeter.Point point = new SessionMeter.Point(command.getLongExtra("elapsed", 0),
                command.getLongExtra("wall", 0), command.getLongExtra("mobile", -1), null);
        if (START.equals(command.getAction())) {
            try {
                foreground();
                worker.execute(() -> begin(point));
            } catch (RuntimeException failure) {
                fail(failure);
            }
        } else if (STOP.equals(command.getAction())) {
            worker.execute(() -> finish(point, startId));
        }
        return START_NOT_STICKY;
    }

    private void foreground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Veri sayacı", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, Dashboard.class).setAction(OPEN_SESSION)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent click = PendingIntent.getActivity(this, 3, open, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_data).setContentTitle("Veri sayacı çalışıyor")
                .setContentText("Ölçümü görmek veya bitirmek için dokun.").setContentIntent(click)
                .setOngoing(true).setOnlyAlertOnce(true).build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(3, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(3, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
        }
    }

    private void begin(SessionMeter.Point point) {
        anchor = Math.max(0, point.wall - 24 * Usage.HOUR);
        started = point.elapsed;
        try (SessionMeter meter = new SessionMeter(this)) {
            meter.begin(withApps(point));
            updatePhase(RUNNING);
            nextSample();
        } catch (RuntimeException failure) {
            fail(failure);
        }
    }

    private void nextSample() {
        long age = SystemClock.elapsedRealtime() - started;
        long interval = age < 15 * 60_000L ? 15 : age < Usage.HOUR ? 60 : 300;
        if (phase == RUNNING) worker.schedule(this::sample, interval, TimeUnit.SECONDS);
    }

    private void sample() {
        if (phase != RUNNING) return;
        try (SessionMeter meter = new SessionMeter(this)) {
            meter.record(withApps(SessionMeter.Point.capture()));
            REVISION.incrementAndGet();
            nextSample();
        } catch (RuntimeException failure) {
            fail(failure);
        }
    }

    private void finish(SessionMeter.Point point, int startId) {
        try (SessionMeter meter = new SessionMeter(this)) {
            meter.end(withApps(point));
            REVISION.incrementAndGet();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        } catch (RuntimeException failure) {
            fail(failure);
        }
    }

    private SessionMeter.Point withApps(SessionMeter.Point point) {
        Map<Integer, Long> counts = null;
        try {
            if (point.wall > anchor && Usage.allowed(this)) counts = Usage.apps(this, anchor, point.wall);
        } catch (RuntimeException failure) {
            Log.w("DataWarner", "Android uygulama istatistiğini vermedi", failure);
        }
        return new SessionMeter.Point(point.elapsed, point.wall, point.mobile, counts);
    }

    private void fail(RuntimeException failure) {
        error = "Sayaç ölçüm kaydını tamamlayamadı.";
        Log.e("DataWarner", error, failure);
        stopSelf();
    }

    private static void updatePhase(int value) {
        phase = value;
        REVISION.incrementAndGet();
    }

    @Override public void onDestroy() {
        worker.shutdownNow();
        updatePhase(IDLE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
