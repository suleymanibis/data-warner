package tr.suleyman.mobilveri;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.Process;
import android.os.RemoteException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Usage {
    // Android 11's UID_TETHERING pseudo-UID represents all tethering.
    static final int HOTSPOT = -5;
    static final long HOUR = 3_600_000L;
    static final Locale TR = Locale.forLanguageTag("tr-TR");
    private static final int MOBILE = ConnectivityManager.TYPE_MOBILE;

    static boolean allowed(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    static long startOf(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    static long mobileCounter() {
        long received = TrafficStats.getMobileRxBytes();
        long sent = TrafficStats.getMobileTxBytes();
        return received < 0 || sent < 0 ? -1 : received + sent;
    }

    static long total(Context context, long start, long end) {
        if (end <= start) return 0;
        NetworkStatsManager manager = context.getSystemService(NetworkStatsManager.class);
        try {
            NetworkStats.Bucket bucket = manager.querySummaryForDevice(MOBILE, null, start, end);
            if (bucket == null) throw new IllegalStateException("Mobil veri sayacı okunamadı");
            return bytes(bucket);
        } catch (RemoteException error) {
            throw new IllegalStateException("Mobil veri sayacı okunamadı", error);
        }
    }

    static Map<Integer, Long> apps(Context context, long start, long end) {
        Map<Integer, Long> counts = new HashMap<>();
        if (end <= start) return counts;
        NetworkStatsManager manager = context.getSystemService(NetworkStatsManager.class);
        NetworkStats.Bucket bucket = new NetworkStats.Bucket();
        try (NetworkStats stats = manager.querySummary(MOBILE, null, start, end)) {
            if (stats == null) throw new IllegalStateException("Uygulama verileri okunamadı");
            while (stats.hasNextBucket() && stats.getNextBucket(bucket)) {
                counts.merge(bucket.getUid(), bytes(bucket), Long::sum);
            }
        } catch (RemoteException error) {
            throw new IllegalStateException("Uygulama verileri okunamadı", error);
        }
        return counts;
    }

    static List<App> rankedUserApps(Context context, Map<Integer, Long> counts) {
        List<App> apps = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        counts.forEach((uid, bytes) -> {
            if (uid >= 0 && bytes > 0 && !Thread.currentThread().isInterrupted()) {
                App app = describeUserApp(pm, uid, bytes);
                if (app != null) apps.add(app);
            }
        });
        apps.sort((first, second) -> Long.compare(second.bytes, first.bytes));
        return apps;
    }

    private static App describeUserApp(PackageManager pm, int uid, long bytes) {
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null || packages.length == 0) return null;
        int systemFlags = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        android.content.Intent launcher = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        List<String> labels = new ArrayList<>();
        Drawable icon = null;
        for (String name : packages) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(name, 0);
                if ((info.flags & systemFlags) != 0
                        && pm.queryIntentActivities(launcher.setPackage(name), 0).isEmpty()) continue;
                String label = pm.getApplicationLabel(info).toString();
                if (!labels.contains(label)) labels.add(label);
                if (icon == null) icon = info.loadIcon(pm);
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        if (labels.isEmpty()) return null;
        return new App(uid, String.join(" / ", labels), bytes, icon);
    }

    static String format(long value) {
        if (value < 0) return "—";
        if (value >= 1_000_000_000L) return String.format(TR, "%.2f GB", value / 1e9);
        if (value >= 1_000_000L) return String.format(TR, "%.1f MB", value / 1e6);
        if (value >= 1_000L) return String.format(TR, "%.1f KB", value / 1e3);
        return value + " B";
    }

    private static long bytes(NetworkStats.Bucket bucket) {
        return bucket.getRxBytes() + bucket.getTxBytes();
    }

    static final class App {
        final int uid;
        final String name;
        final long bytes;
        final Drawable icon;

        App(int uid, String name, long bytes) {
            this(uid, name, bytes, null);
        }

        App(int uid, String name, long bytes, Drawable icon) {
            this.uid = uid;
            this.name = name;
            this.bytes = bytes;
            this.icon = icon;
        }
    }
}
