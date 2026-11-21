package tr.suleyman.mobilveri;

import android.content.Context;
import android.content.SharedPreferences;

import java.math.BigDecimal;

final class AlertRule {
    static final long BYTES_PER_GB = 1_000_000_000L;
    static final long DEFAULT_BYTES = 3 * BYTES_PER_GB;
    static final int DEFAULT_MINUTES = 60;
    static final int MIN_MINUTES = 30;
    static final int MAX_MINUTES = 24 * 60;
    final long bytes;
    final int minutes;

    AlertRule(long bytes, int minutes) {
        this.bytes = bytes;
        this.minutes = minutes;
    }

    static AlertRule load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
        return new AlertRule(prefs.getLong("alert_bytes", DEFAULT_BYTES),
                prefs.getInt("alert_minutes", DEFAULT_MINUTES));
    }

    void save(Context context) {
        context.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit()
                .putLong("alert_bytes", bytes).putInt("alert_minutes", minutes)
                .putBoolean("high", false).apply();
    }

    static AlertRule parse(String gigabytes, String minutes) {
        String number = gigabytes.trim();
        if (!number.matches("[0-9]{1,4}([,.][0-9]{1,3})?")) throw new IllegalArgumentException();
        long bytes = new BigDecimal(number.replace(',', '.')).movePointRight(9).longValueExact();
        int duration = Integer.parseInt(minutes.trim());
        if (bytes < 100_000_000L || bytes > 1000 * BYTES_PER_GB
                || duration < MIN_MINUTES || duration > MAX_MINUTES) {
            throw new IllegalArgumentException();
        }
        return new AlertRule(bytes, duration);
    }

    long duration() {
        return minutes * 60_000L;
    }

    String gigabytes() {
        return BigDecimal.valueOf(bytes, 9).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    String limit() {
        return gigabytes() + " GB / " + (minutes % 60 == 0 ? minutes / 60 + " saat" : minutes + " dk");
    }
}
