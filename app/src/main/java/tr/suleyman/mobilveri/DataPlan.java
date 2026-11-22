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

    DataPlan(long capacity, int renewalDay) {
        this.capacity = capacity;
        this.renewalDay = renewalDay;
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("data_plan", Context.MODE_PRIVATE);
    }

    static DataPlan load(Context context) {
        return new DataPlan(prefs(context).getLong("capacity", 0), prefs(context).getInt("day", 1));
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
        return BigDecimal.valueOf(capacity, 9).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    void save(Context context) {
        prefs(context).edit().putLong("capacity", capacity).putInt("day", renewalDay)
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
        return new Snapshot(start, next, ChronoUnit.DAYS.between(today, next), capacity, used);
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
