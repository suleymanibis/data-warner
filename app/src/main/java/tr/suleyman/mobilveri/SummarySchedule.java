package tr.suleyman.mobilveri;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

final class SummarySchedule {
    static final SummarySchedule DEFAULT = new SummarySchedule(6, 0);
    final int hours;
    final int minuteOfDay;

    SummarySchedule(int hours, int minuteOfDay) {
        this.hours = hours == 12 || hours == 24 ? hours : 6;
        this.minuteOfDay = this.hours == 24 && minuteOfDay >= 0 && minuteOfDay < 1440 ? minuteOfDay : 0;
    }

    static SummarySchedule load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
        return new SummarySchedule(prefs.getInt("report_hours", DEFAULT.hours), prefs.getInt("report_minute", 0));
    }

    void save(Context context) {
        context.getSharedPreferences("monitor", Context.MODE_PRIVATE).edit()
                .putInt("report_hours", hours).putInt("report_minute", minuteOfDay).apply();
        Monitor.start(context);
    }

    long next(ZonedDateTime now) {
        LocalDate date = now.toLocalDate();
        if (hours == 24) {
            LocalTime time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
            ZonedDateTime today = date.atTime(time).atZone(now.getZone());
            return (today.isAfter(now) ? today : date.plusDays(1).atTime(time).atZone(now.getZone()))
                    .toInstant().toEpochMilli();
        }
        for (int hour = 0; hour < 24; hour += hours) {
            ZonedDateTime slot = date.atTime(hour, 0).atZone(now.getZone());
            if (slot.isAfter(now)) return slot.toInstant().toEpochMilli();
        }
        return date.plusDays(1).atStartOfDay(now.getZone()).toInstant().toEpochMilli();
    }
}
