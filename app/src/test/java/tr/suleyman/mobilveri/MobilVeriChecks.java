package tr.suleyman.mobilveri;

import android.content.res.Configuration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class MobilVeriChecks {
    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        ZoneId zone = ZoneId.of("Europe/Istanbul");
        LocalDate date = LocalDate.of(2026, 11, 20);
        for (int hour = 0; hour < 24; hour++) {
            ZonedDateTime now = date.atTime(hour, 22).atZone(zone);
            ZonedDateTime next = Instant.ofEpochMilli(Monitor.nextReport(now)).atZone(zone);
            check(next.isAfter(now) && next.getHour() == ((hour / 6 + 1) * 6) % 24
                    && next.getMinute() == 0 && now.until(next, java.time.temporal.ChronoUnit.HOURS) < 6,
                    "notification slot " + hour);
        }
        Method periods = Dashboard.class.getDeclaredMethod("periods", LocalDate.class, ZoneId.class, int.class, int.class);
        periods.setAccessible(true);
        for (int mode = 0; mode < 3; mode++) {
            List<?> buckets = (List<?>) periods.invoke(null, date, zone, mode, 0);
            long start = field(buckets.get(0), "start");
            long expected = Usage.startOf(mode == 0 ? date : date.minusDays(29), zone);
            check(start == expected, "chart starts at the expected day");
            check(field(buckets.get(buckets.size() - 1), "end") == Usage.startOf(date.plusDays(1), zone),
                    "chart ends at midnight");
            check(mode == 0 ? buckets.size() == 24 : mode == 1 ? buckets.size() == 30 :
                    buckets.size() == 5 || buckets.size() == 6, "number of chart buckets");
            for (int i = 1; i < buckets.size(); i++) {
                check(field(buckets.get(i - 1), "end") == field(buckets.get(i), "start"), "contiguous chart buckets");
            }
        }
        Method delta = Monitor.class.getDeclaredMethod("delta", long.class, long.class);
        delta.setAccessible(true);
        check((Long) delta.invoke(null, 3_200_000_000L, 100_000_000L) == 3_100_000_000L, "rollover-safe threshold arithmetic");
        check((Long) delta.invoke(null, 44L, 88L) == -1L, "counter restart");
        check((Long) delta.invoke(null, 44L, -1L) == -1L, "unsupported baseline");
        check(Usage.format(3_000_000_000L).equals("3,00 GB"), "Turkish units");
        checkActiveBucketAttribution();
        checkRollingWindowBounds();
        checkSummarySelections(date);
        checkAppearance();
        checkSessionData();
        checkCustomAlert();
        checkDataPlan();
        checkSessionRate();
        check(TabSwipeLayout.direction(-80, 10, 56) == 1, "left swipe opens next tab");
        check(TabSwipeLayout.direction(80, -10, 56) == -1, "right swipe opens previous tab");
        check(TabSwipeLayout.direction(25, 0, 56) == 0, "short drag does not change tabs");
        check(TabSwipeLayout.direction(60, 80, 56) == 0, "vertical scroll does not change tabs");
        System.out.println("Schedule, chart periods, counter, formatting and swipe checks passed.");
    }

    private static void checkDataPlan() {
        DataPlan plan = DataPlan.parse("12,5", "31");
        check(plan.capacity == 12_500_000_000L, "decimal data plan size");
        check(plan.start(LocalDate.of(2027, 2, 27)).equals(LocalDate.of(2027, 1, 31)),
                "previous month before short-month cutoff");
        check(plan.start(LocalDate.of(2027, 2, 28)).equals(LocalDate.of(2027, 2, 28)),
                "cutoff clamps to last day of a short month");
        check(plan.cutoff(java.time.YearMonth.of(2028, 2)).getDayOfMonth() == 29, "leap year cutoff");
        LocalDate start = LocalDate.of(2026, 11, 1);
        DataPlan.Snapshot usage = new DataPlan.Snapshot(start, start.plusMonths(1), 10, 1000, 800);
        check(usage.level() == 80 && usage.remaining() == 200 && usage.perDay() == 20, "80 percent and daily budget");
        check(new DataPlan.Snapshot(start, start.plusMonths(1), 1, 1000, 950).level() == 95, "95 percent boundary");
        check(new DataPlan.Snapshot(start, start.plusMonths(1), 1, 1000, 1100).remaining() == 0, "exhausted plan");
    }

    private static void checkSessionRate() {
        SessionMeter.State state = new SessionMeter.State(java.util.Arrays.asList(
                new SessionMeter.Point(1000, 1000, 0, null),
                new SessionMeter.Point(301000, 301000, 250_000_000L, null)), false);
        check(state.hourlyEstimate() == 3_000_000_000L, "250 MB in five minutes projects to 3 GB/hour");
    }

    private static void checkCustomAlert() {
        AlertRule custom = AlertRule.parse("1,25", "90");
        check(custom.bytes == 1_250_000_000L && custom.duration() == 90 * 60_000L, "custom alert values");
        Monitor.Window window = new Monitor.Window(3_000_000_000L, custom);
        window.add(80 * 60_000L, 1_500_000_000L);
        window.add(92 * 60_000L, 1_000_000_000L);
        check(window.lower == 1_500_000_000L && window.upper == 2_000_000_000L && !window.belowLimit(),
                "custom window changes inclusion and rearm threshold");
        try {
            AlertRule.parse("1", "29");
            throw new AssertionError("short alert duration accepted");
        } catch (IllegalArgumentException expected) {
            // Background sample cadence sets the minimum.
        }
    }

    private static void checkSessionData() {
        int app = 10123;
        Map<Integer, Long> baseline = new HashMap<>();
        baseline.put(app, 10L);
        baseline.put(Usage.HOTSPOT, 25L);
        Map<Integer, Long> finalCounts = new HashMap<>();
        finalCounts.put(app, 90L);
        finalCounts.put(Usage.HOTSPOT, 70L);
        SessionMeter.State run = new SessionMeter.State(Arrays.asList(
                new SessionMeter.Point(1000, 50000, 50, baseline),
                new SessionMeter.Point(16000, 65000, 120, null),
                new SessionMeter.Point(34000, 83000, 180, finalCounts)), false);
        check(run.duration() == 33000 && run.amount(SessionMeter.TOTAL) == 130, "exact session endpoints");
        check(run.amount(Usage.HOTSPOT) == 45 && run.appAmounts().get(app) == 80L,
                "app and hotspot deltas from matching baseline");
        check(run.series(app).get(0).end == 33000, "missing sample is not fabricated into smaller bins");
        check(!run.appAmounts().containsKey(Usage.HOTSPOT), "hotspot has its own chart");
        SessionMeter.State noHotspot = new SessionMeter.State(Arrays.asList(
                new SessionMeter.Point(1000, 0, 100, Collections.emptyMap()),
                new SessionMeter.Point(2000, 0, 200, Collections.emptyMap())), false);
        check(noHotspot.amount(Usage.HOTSPOT) == -1, "unknown hotspot is not reported as zero");
        java.util.ArrayList<SessionMeter.Point> many = new java.util.ArrayList<>();
        for (int i = 0; i <= 100; i++) many.add(new SessionMeter.Point(i * 15000L, 0, i, null));
        SessionMeter.State longer = new SessionMeter.State(many, false);
        check(longer.series(SessionMeter.TOTAL).size() <= 12 && longer.amount(SessionMeter.TOTAL) == 100,
                "longer session adapts chart intervals without changing the total");
    }

    private static void checkActiveBucketAttribution() {
        List<Dashboard.Period> buckets = Arrays.asList(
                new Dashboard.Period(0, 60, "0", "0"),
                new Dashboard.Period(60, 120, "1", "1"),
                new Dashboard.Period(120, 180, "2", "2"),
                new Dashboard.Period(180, 240, "3", "3"));
        Long total = Dashboard.populatePeriods(buckets, 150, (start, end) -> {
            check(end == 150, "every chart query has the same end time");
            return start == 0 ? 820L : 500L;
        });
        check(total == 820L, "chart total");
        check(buckets.get(0).bytes == 320L && buckets.get(1).bytes == 0L
                && buckets.get(2).bytes == 500L, "active bucket counted once");
        check(buckets.get(3).bytes == -1, "future chart slot is unmeasured");
        check(Dashboard.availableBar(buckets, 2) == 2, "measured chart bar can be selected");
        check(Dashboard.availableBar(buckets, 3) == -1, "future chart bar cannot replace the summary");
        check(Dashboard.availableBar(buckets, -1) == -1, "selection can return to the overall summary");
    }

    private static void checkSummarySelections(LocalDate date) {
        check(Dashboard.summaryTitle(0, false, 0, 0) == R.string.today_mobile, "today title");
        check(Dashboard.summaryTitle(0, false, 1, 0) == R.string.last_30_mobile, "daily chart span title");
        check(Dashboard.summaryTitle(2, false, 2, 0) == R.string.last_30_hotspot, "weekly hotspot span title");
        check(Dashboard.summaryTitle(0, false, 0, 1) == R.string.mobile_data, "historical day title");
        check(Dashboard.summaryTitle(2, false, 0, 1) == R.string.hotspot_tethering, "hotspot title");
        check(Dashboard.summaryTitle(1, true, 0, 1) == R.string.selected_total,
                "app detail title");
    }

    private static void checkAppearance() {
        int night = Configuration.UI_MODE_NIGHT_YES;
        int day = Configuration.UI_MODE_NIGHT_NO;
        check(Appearance.useLight(Appearance.SYSTEM, day), "follow system light");
        check(!Appearance.useLight(Appearance.SYSTEM, night), "follow system dark");
        check(Appearance.useLight(Appearance.LIGHT, night), "force light in a dark system");
        check(!Appearance.useLight(Appearance.DARK, day), "force dark in a light system");
    }

    private static void checkRollingWindowBounds() {
        Monitor.Window rolling = new Monitor.Window(5_500_000_000L);
        rolling.add(30 * 60_000L, 2_400_000_000L);
        rolling.add(50 * 60_000L, 2_300_000_000L);
        rolling.add(70 * 60_000L, 1_100_000_000L);
        check(rolling.lower == 3_200_000_000L && rolling.upper == 4_400_000_000L,
                "conservative bounds around 60 minutes");
        check(!rolling.belowLimit(), "ongoing usage does not rearm warning");
        Monitor.Window sparse = new Monitor.Window(5_500_000_000L);
        sparse.add(15 * 60_000L, 5_400_000_000L);
        check(!sparse.belowLimit(), "short measurement cannot rearm warning");
        sparse.add(65 * 60_000L, 4_000_000_000L);
        check(sparse.belowLimit(), "rearm once upper bound falls below threshold");
        Monitor.Window reset = new Monitor.Window(5_500_000_000L);
        reset.add(15 * 60_000L, 5_000_000_000L);
        reset.add(30 * 60_000L, 7_000_000_000L);
        reset.add(45 * 60_000L, 100L);
        check(reset.lower == 500_000_000L, "older sample cannot create a false alert across a counter reset");
    }

    private static long field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
