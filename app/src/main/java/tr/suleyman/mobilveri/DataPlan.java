package tr.suleyman.mobilveri;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;

final class DataPlan {
    final long capacity;
    final int renewalDay;
    final long offset;
    final long offsetCycle;

    DataPlan(long capacity, int renewalDay) {
        this(capacity, renewalDay, 0, Long.MIN_VALUE);
    }

    private DataPlan(long capacity, int renewalDay, long offset, long offsetCycle) {
        this.capacity = capacity;
        this.renewalDay = renewalDay;
        this.offset = offset;
        this.offsetCycle = offsetCycle;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("data_plan", Context.MODE_PRIVATE);
    }

    static DataPlan load(Context context) {
        SharedPreferences settings = prefs(context);
        return new DataPlan(settings.getLong("capacity", 0), settings.getInt("day", 1),
                settings.getLong("usage_offset", 0), settings.getLong("offset_cycle", Long.MIN_VALUE));
    }

    static DataPlan parse(String capacity, String day) {
        String value = capacity.trim();
        if (!value.matches("[0-9]{1,4}([,.][0-9]{1,3})?")) throw new IllegalArgumentException();
        long bytes = new BigDecimal(value.replace(',', '.')).movePointRight(9).longValueExact();
        int renewal = Integer.parseInt(day.trim());
        if (bytes <= 0 || bytes > 1000 * AlertRule.BYTES_PER_GB || renewal < 1 || renewal > 31) {
            throw new IllegalArgumentException();
        }
        return new DataPlan(bytes, renewal);
    }

    String gigabytes() {
        return decimal(capacity);
    }

    private static String decimal(long bytes) {
        char separator = java.text.DecimalFormatSymbols.getInstance().getDecimalSeparator();
        return BigDecimal.valueOf(bytes, 9).stripTrailingZeros().toPlainString().replace('.', separator);
    }

    String offsetGigabytes(LocalDate today) {
        return decimal(offsetCycle == start(today).toEpochDay() ? offset : 0);
    }

    DataPlan calibrate(String adjustment, LocalDate today) {
        String value = adjustment.trim();
        if (value.isEmpty()) value = "0";
        if (!value.matches("[+-]?[0-9]{1,4}([,.][0-9]{1,3})?")) throw new IllegalArgumentException();
        long bytes = new BigDecimal(value.replace(',', '.')).movePointRight(9).longValueExact();
        if (Math.abs(bytes) > 1000 * AlertRule.BYTES_PER_GB) throw new IllegalArgumentException();
        return new DataPlan(capacity, renewalDay, bytes, start(today).toEpochDay());
    }

    long adjustedUsage(long used, LocalDate cycle) {
        long adjustment = offsetCycle == cycle.toEpochDay() ? offset : 0;
        if (adjustment > 0 && used > Long.MAX_VALUE - adjustment) return Long.MAX_VALUE;
        return Math.max(0, used + adjustment);
    }

    void save(Context context) {
        prefs(context).edit().putLong("capacity", capacity).putInt("day", renewalDay)
                .putLong("usage_offset", offset).putLong("offset_cycle", offsetCycle)
                .remove("notified_key").remove("notified_level").apply();
    }

    LocalDate cutoff(YearMonth month) {
        return month.atDay(Math.min(renewalDay, month.lengthOfMonth()));
    }

    LocalDate start(LocalDate today) {
        YearMonth month = YearMonth.from(today);
        LocalDate thisMonth = cutoff(month);
        return today.isBefore(thisMonth) ? cutoff(month.minusMonths(1)) : thisMonth;
    }

    Snapshot read(Context context, ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDate start = start(today);
        LocalDate next = cutoff(YearMonth.from(start).plusMonths(1));
        long used = Usage.total(context, Usage.startOf(start, now.getZone()), now.toInstant().toEpochMilli());
        return new Snapshot(start, next, ChronoUnit.DAYS.between(today, next), capacity, adjustedUsage(used, start));
    }

    static final class Snapshot {
        final LocalDate start;
        final LocalDate next;
        final long days;
        final long capacity;
        final long used;

        Snapshot(LocalDate start, LocalDate next, long days, long capacity, long used) {
            this.start = start;
            this.next = next;
            this.days = days;
            this.capacity = capacity;
            this.used = used;
        }

        long remaining() { return Math.max(0, capacity - used); }
        long perDay() { return remaining() / Math.max(1, days); }
        int level() { return used >= capacity * 95 / 100 ? 95 : used >= capacity * 80 / 100 ? 80 : 0; }
        int percent() { return capacity <= 0 ? 0 : (int) Math.min(100d, 100d * used / capacity); }
    }
}
