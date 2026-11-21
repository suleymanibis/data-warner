package tr.suleyman.mobilveri;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class Dashboard extends Activity {
    private static final int SESSION_TAB = 3;
    private static final int ALERT_TAB = 4;
    private static final String[] MODES = {"Saatlik", "Günlük", "Haftalık"};
    private static final String[] RANGES = {"1 sa", "3 sa", "1 gün", "3 gün", "1 hafta", "1 ay"};
    private static final long[] RANGE_HOURS = {1, 3, 24, 72, 168, 720};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", Usage.TR);
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("d MMMM, EEEE", Usage.TR);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Usage.TR);
    private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm:ss", Usage.TR);

    private final ExecutorService queries = Executors.newSingleThreadExecutor();
    private Future<?> request;
    private Appearance colors;
    private FrameLayout root;
    private FrameLayout drawer;
    private ImageButton settingsButton;
    private boolean drawerOpen;
    private LinearLayout main;
    private LinearLayout content;
    private LinearLayout hero;
    private int tab;
    private int mode;
    private int range = 2;
    private int daysBack;
    private int selectedBar = -1;
    private Usage.App app;
    private int revision;
    private boolean visible;
    private int sessionMetric = SessionMeter.TOTAL;
    private long historyId;
    private long alertEnd;
    private long alertDuration;

    @Override protected void onCreate(Bundle saved) {
        colors = new Appearance(Appearance.useLight(Appearance.mode(this), getResources().getConfiguration().uiMode));
        setTheme(colors.light ? R.style.DataWarnerLight : R.style.DataWarnerDark);
        super.onCreate(saved);
        if (saved != null) {
            tab = saved.getInt("tab");
            mode = saved.getInt("mode");
            range = saved.getInt("range", 2);
            daysBack = saved.getInt("daysBack");
            selectedBar = saved.getInt("selectedBar", -1);
            sessionMetric = saved.getInt("sessionMetric", SessionMeter.TOTAL);
            historyId = saved.getLong("historyId");
            alertEnd = saved.getLong("alertEnd");
            alertDuration = saved.getLong("alertDuration");
            drawerOpen = saved.getBoolean("drawerOpen");
            if (saved.containsKey("app_uid")) {
                app = new Usage.App(saved.getInt("app_uid"), saved.getString("app_name"), 0);
            }
        }
        selectSessionIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        selectSessionIntent(intent);
        if (visible) render();
    }

    private void selectSessionIntent(Intent intent) {
        if (intent != null && SessionService.OPEN_SESSION.equals(intent.getAction())) {
            tab = SESSION_TAB;
            historyId = 0;
            drawerOpen = false;
            app = null;
            intent.setAction(null);
        }
        if (intent != null && Monitor.OPEN_ALERT.equals(intent.getAction())) {
            long end = intent.getLongExtra(Monitor.ALERT_END, 0);
            long duration = intent.getLongExtra(Monitor.ALERT_DURATION, 0);
            long now = System.currentTimeMillis();
            if (end > now - 31L * 24 * Usage.HOUR && end <= now && duration >= 60_000 && duration <= 24 * Usage.HOUR) {
                tab = ALERT_TAB;
                alertEnd = end;
                alertDuration = duration;
                drawerOpen = false;
                app = null;
            }
            intent.setAction(null);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        Monitor.start(this);
        PlanWidget.requestUpdate(this);
        render();
    }

    @Override protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab", tab);
        state.putInt("mode", mode);
        state.putInt("range", range);
        state.putInt("daysBack", daysBack);
        state.putInt("selectedBar", selectedBar);
        state.putInt("sessionMetric", sessionMetric);
        state.putLong("historyId", historyId);
        state.putLong("alertEnd", alertEnd);
        state.putLong("alertDuration", alertDuration);
        state.putBoolean("drawerOpen", drawerOpen);
        if (app != null) {
            state.putInt("app_uid", app.uid);
            state.putString("app_name", app.name);
        }
    }

    @Override protected void onDestroy() {
        revision++;
        queries.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (drawerOpen) {
            closeDrawer();
        } else if (tab == SESSION_TAB && historyId > 0) {
            historyId = 0;
            sessionMetric = SessionMeter.TOTAL;
            render();
        } else if (app != null) {
            app = null;
            selectedBar = -1;
            render();
        } else if (tab != 0) {
            tab = 0;
            selectedBar = -1;
            render();
        } else {
            super.onBackPressed();
        }
    }

    private void render() {
        if (request != null) request.cancel(true);
        int token = ++revision;
        root = new FrameLayout(this);
        drawer = null;
        main = column();
        main.setBackgroundColor(colors.background);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        LinearLayout header = row();
        header.setPadding(dp(16), dp(10), dp(13), dp(10));
        settingsButton = new ImageButton(this);
        settingsButton.setImageResource(R.mipmap.ic_launcher);
        settingsButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        settingsButton.setPadding(dp(2), dp(2), dp(2), dp(2));
        settingsButton.setContentDescription("Ayarlar panelini aç");
        settingsButton.setBackground(interactive(0x00000000, 14, 0));
        settingsButton.setOnClickListener(view -> openDrawer());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout identity = column();
        identity.setPadding(dp(10), 0, dp(5), 0);
        identity.addView(label(getString(R.string.app_name), 22, colors.text, true));
        identity.addView(label("Mobil veri takibi", 12, colors.secondary, false));
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(action("Yenile", this::render));
        main.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = column();
        content.setPadding(dp(16), dp(2), dp(16), dp(20));
        scroll.addView(content);
        main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (drawerOpen) openDrawer();
        if (!Usage.allowed(this)) {
            LinearLayout permission = card();
            permission.addView(label("Kullanım erişimi gerekli", 23, colors.text, true));
            paragraph(permission, "Mobil veri istatistiklerini okumak için Android ayarlarında Data Warner’a kullanım erişimi ver.");
            permission.addView(chip("Kullanım erişimini aç", true, this::openUsageSettings),
                    new LinearLayout.LayoutParams(-1, dp(48)));
            return;
        }
        addNavigation();
        if (tab == ALERT_TAB) {
            showAlert(token);
            return;
        }
        if (tab == SESSION_TAB) {
            new SessionPanel(token).show();
            return;
        }
        if (tab == 0 && !getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            LinearLayout notifications = card();
            notifications.addView(label("Bildirimler kapalı", 15, colors.warning, true));
            notifications.addView(action("Bildirimleri aç", this::enableNotifications));
        }

        ZonedDateTime snapshot = ZonedDateTime.now();
        LocalDate today = snapshot.toLocalDate();
        long now = snapshot.toInstant().toEpochMilli();
        ZoneId zone = snapshot.getZone();
        if (tab == 1) {
            if (app != null) content.addView(action("‹ Uygulamalara dön", () -> {
                app = null;
                selectedBar = -1;
                render();
            }));
            TextView heading = label(app == null ? "Uygulama kullanımı" : app.name, 22, colors.text, true);
            heading.setPadding(dp(4), dp(8), dp(4), dp(8));
            content.addView(heading);
            addSelector(RANGES, range, value -> {
                if (range != value) selectedBar = -1;
                range = value;
                render();
            });
        } else {
            hero = card();
            setHeroStyle(hero, tab);
            hero.addView(label(summaryTitle(tab, false, mode, daysBack, today), 14, colors.text, true));
            hero.addView(label("—", 42, tab == 2 ? colors.hotspot : colors.accent, true));
            if (tab == 0) addPlanCard(token, snapshot);
            addSelector(MODES, mode, value -> {
                if (mode != value) selectedBar = -1;
                mode = value;
                render();
            });
            if (mode == 0) addDatePicker(today, zone);
        }
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminateTintList(ColorStateList.valueOf(colors.accent));
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(dp(28), dp(28));
        progressLayout.gravity = Gravity.CENTER_HORIZONTAL;
        progressLayout.setMargins(0, dp(24), 0, dp(16));
        content.addView(progress, progressLayout);

        int selectedTab = tab;
        int selectedMode = mode;
        int selectedRange = range;
        int selectedDay = daysBack;
        Usage.App selectedApp = app;
        request = queries.submit(() -> {
            try {
                long todayStart = Usage.startOf(today, zone);
                if (selectedTab == 1 && selectedApp == null) {
                    long start = now - RANGE_HOURS[selectedRange] * Usage.HOUR;
                    Map<Integer, Long> counts = Usage.apps(this, start, now);
                    counts.remove(Usage.HOTSPOT);
                    List<Usage.App> rows = Usage.ranked(this, counts);
                    updated(token, progress, () -> showApps(rows, selectedRange));
                    return;
                }
                int uid = selectedTab == 2 ? Usage.HOTSPOT : selectedApp == null ? Integer.MIN_VALUE : selectedApp.uid;
                List<Period> periods;
                if (selectedApp != null) {
                    long start = now - RANGE_HOURS[selectedRange] * Usage.HOUR;
                    periods = rollingPeriods(start, now, zone, selectedRange);
                } else {
                    periods = periods(today, zone, selectedMode, selectedDay);
                }
                Long spanTotal = populatePeriods(periods, now, (start, end) -> read(uid, start, end));
                if (spanTotal == null || Thread.currentThread().isInterrupted()) return;
                long total = selectedApp != null || selectedMode == 0
                        ? spanTotal : read(uid, todayStart, now);
                updated(token, progress, () -> showChart(periods, total, selectedTab, selectedApp, snapshot));
            } catch (RuntimeException error) {
                android.util.Log.w("MobilVeri", "Kullanım verileri okunamadı", error);
                updated(token, progress, () -> paragraph(card(),
                        "Mobil veri kayıtları okunamadı. Kullanım erişimini kontrol edip Yenile’ye dokun."));
            }
        });
    }

    private void addPlanCard(int token, ZonedDateTime now) {
        LinearLayout panel = card();
        LinearLayout heading = row();
        heading.addView(label("Mobil veri paketi", 17, colors.text, true), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(action("Ayarla", this::editPlan));
        panel.addView(heading);
        DataPlan plan = DataPlan.load(this);
        TextView details = label(plan.capacity == 0 ? "Paket boyutunu ve yenilenme gününü gir." : "Paket hesaplanıyor…",
                14, colors.secondary, false);
        panel.addView(details);
        if (plan.capacity == 0) return;
        queries.submit(() -> {
            try {
                DataPlan.Snapshot value = plan.read(this, now);
                runOnUiThread(() -> {
                    if (token != revision || isDestroyed()) return;
                    details.setText("Kalan " + Usage.format(value.remaining()) + " / " + Usage.format(value.capacity));
                    details.setTextColor(colors.accent);
                    details.setTextSize(23);
                    ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                    progress.setProgressTintList(ColorStateList.valueOf(value.level() > 0 ? colors.warning : colors.accent));
                    progress.setProgress(value.percent());
                    panel.addView(progress);
                    paragraph(panel, value.next.format(DATE) + " yenileme · " + value.days + " gün kaldı"
                            + "\nGünlük bütçe " + Usage.format(value.perDay()) + " · Android ölçümü");
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (token == revision) details.setText("Paket verisi şu anda okunamadı.");
                });
            }
        });
    }

    private void editPlan() {
        DataPlan existing = DataPlan.load(this);
        LinearLayout inputs = column();
        inputs.setPadding(dp(24), dp(8), dp(24), 0);
        EditText amount = alertInput(inputs, "Paket boyutu (GB)", existing.capacity == 0 ? "" : existing.gigabytes(), true);
        EditText day = alertInput(inputs, "Yenilenme günü (1–31)", Integer.toString(existing.renewalDay), false);
        TextView message = label("Kısa aylarda son gün kullanılır. Uyarı eşikleri %80 ve %95.", 13, colors.secondary, false);
        inputs.addView(message);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Paket takibi").setView(inputs)
                .setNegativeButton("Vazgeç", null).setPositiveButton("Kaydet", null)
                .setNeutralButton(existing.capacity > 0 ? "Kapat" : null, (d, w) -> savePlan(new DataPlan(0, 1))).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                DataPlan plan = DataPlan.parse(amount.getText().toString(), day.getText().toString());
                dialog.dismiss();
                savePlan(plan);
            } catch (IllegalArgumentException invalid) {
                message.setText("0’dan büyük, en fazla 1000 GB ve 1–31 arasında bir gün gir.");
                message.setTextColor(colors.warning);
            }
        }));
        dialog.show();
    }

    private void savePlan(DataPlan plan) {
        plan.save(this);
        PlanWidget.requestUpdate(this);
        render();
    }

    private void showAlert(int token) {
        LinearLayout card = card();
        card.addView(label("Veri sınırı özeti", 23, colors.text, true));
        long end = alertEnd;
        long start = end - alertDuration;
        ZoneId zone = ZoneId.systemDefault();
        paragraph(card, Instant.ofEpochMilli(start).atZone(zone).format(SESSION_TIME) + " – "
                + Instant.ofEpochMilli(end).atZone(zone).format(SESSION_TIME));
        TextView detail = label("Android kayıtları okunuyor…", 16, colors.text, true);
        card.addView(detail);
        request = queries.submit(() -> {
            try {
                List<Period> periods = new ArrayList<>();
                long step = Math.max(60_000, (end - start + 11) / 12);
                for (long time = start; time < end; time += step) {
                    long until = Math.min(end, time + step);
                    String from = Instant.ofEpochMilli(time).atZone(zone).format(CLOCK);
                    String to = Instant.ofEpochMilli(until).atZone(zone).format(CLOCK);
                    periods.add(new Period(time, until, from, from + "–" + to));
                }
                Long total = populatePeriods(periods, end, (a, b) -> Usage.total(this, a, b));
                if (total == null || Thread.currentThread().isInterrupted()) return;
                Map<Integer, Long> counts = Usage.apps(this, start, end);
                counts.remove(Usage.HOTSPOT);
                List<Usage.App> apps = Usage.ranked(this, counts);
                runOnUiThread(() -> {
                    if (token != revision || isDestroyed()) return;
                    detail.setText("Android kaydı · " + Usage.format(total));
                    BarChart chart = new BarChart(periods, colors.accent, -1);
                    chart.selection = index -> {
                        if (index >= 0) detail.setText(periodText(periods.get(index)));
                    };
                    card.addView(chart, new LinearLayout.LayoutParams(-1, dp(186)));
                    paragraph(card, "Android kayıtları gecikebilir. Bu özet bildirimdeki canlı ölçümden farklı olabilir.");
                    LinearLayout breakdown = card();
                    breakdown.addView(label("En çok veri kullanan uygulamalar", 16, colors.text, true));
                    if (apps.isEmpty()) paragraph(breakdown, "Bu aralık için henüz uygulama kaydı yok.");
                    else addAppRows(breakdown, apps.subList(0, Math.min(5, apps.size())), null);
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (token == revision) detail.setText("Bu aralığın verileri okunamadı.");
                });
            }
        });
    }

    private void addNavigation() {
        LinearLayout nav = row();
        nav.setBackgroundColor(colors.surface);
        nav.setPadding(dp(13), dp(8), dp(13), dp(8));
        String[] names = {"Genel", "Uygulamalar", "Hotspot", "Sayaç"};
        for (int i = 0; i < names.length; i++) {
            int target = i;
            TextView view = chip(names[i], tab == i, () -> {
                if (tab != target || app != null) selectedBar = -1;
                tab = target;
                historyId = 0;
                app = null;
                render();
            });
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, i == 1 ? 1.4f : 1f);
            view.setMinHeight(dp(48));
            layout.setMargins(dp(2), 0, dp(2), 0);
            nav.addView(view, layout);
        }
        main.addView(nav);
    }

    private void openDrawer() {
        if (drawer != null) return;
        boolean animate = !drawerOpen;
        drawerOpen = true;
        drawer = new FrameLayout(this);
        drawer.setAccessibilityPaneTitle("Ayarlar");
        View scrim = new View(this);
        scrim.setBackgroundColor(0x99090d1a);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.setOnClickListener(view -> closeDrawer());
        drawer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        ScrollView panel = new ScrollView(this);
        panel.setBackgroundColor(colors.surface);
        panel.setElevation(dp(18));
        panel.setFillViewport(true);
        panel.setClickable(true);
        LinearLayout settings = column();
        settings.setPadding(dp(18), dp(16), dp(18), dp(24));
        panel.addView(settings);
        FrameLayout.LayoutParams dimensions = new FrameLayout.LayoutParams(
                Math.min(dp(328), Math.max(dp(216), getResources().getDisplayMetrics().widthPixels - dp(38))),
                -1, Gravity.LEFT);
        drawer.addView(panel, dimensions);

        LinearLayout heading = row();
        TextView title = label("Ayarlar", 25, colors.text, true);
        title.setAccessibilityHeading(true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = action("×", this::closeDrawer, true, "Ayarlar panelini kapat");
        heading.addView(close);
        settings.addView(heading);
        settings.addView(drawerAction("Veri Sayacı ›", () -> {
            closeDrawer();
            tab = SESSION_TAB;
            historyId = 0;
            app = null;
            selectedBar = -1;
            render();
        }));

        drawerHeading(settings, "Bildirim Ayarları");
        boolean notifications = getSystemService(NotificationManager.class).areNotificationsEnabled();
        settings.addView(drawerAction("Tüm bildirimler · " + (notifications ? "Açık" : "Kapalı"), this::enableNotifications));
        addNotificationChannel(settings, "Günlük kullanım özeti", Monitor.SUMMARY_CHANNEL);
        addNotificationChannel(settings, "Veri sınırı bildirimi", Monitor.ALERT_CHANNEL);
        addNotificationChannel(settings, "Aylık paket uyarısı", Monitor.PLAN_CHANNEL);
        settings.addView(drawerAction("Sınır: " + AlertRule.load(this).limit() + " ›", this::editAlertRule));
        settings.addView(drawerAction("Aylık paket takibi ›", this::editPlan));

        drawerHeading(settings, "Görünüş Ayarları");
        RadioGroup modes = new RadioGroup(this);
        int mode = Appearance.mode(this);
        int checked = View.NO_ID;
        for (int i = 0; i < Appearance.LABELS.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setTag(i);
            option.setText(Appearance.LABELS[i]);
            option.setTextSize(15);
            option.setTextColor(colors.text);
            option.setMinHeight(dp(48));
            option.setButtonTintList(ColorStateList.valueOf(colors.accent));
            option.setBackground(interactive(colors.surface, 10, 0));
            modes.addView(option);
            if (mode == i) checked = option.getId();
        }
        modes.check(checked);
        modes.setOnCheckedChangeListener((group, id) -> {
            View option = group.findViewById(id);
            if (option != null && (int) option.getTag() != mode) {
                Appearance.setMode(this, (int) option.getTag());
                recreate();
            }
        });
        settings.addView(modes);

        main.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        main.setDescendantFocusability(LinearLayout.FOCUS_BLOCK_DESCENDANTS);
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        close.requestFocus();
        if (animate) {
            scrim.setAlpha(0);
            panel.setTranslationX(-dimensions.width);
            scrim.animate().alpha(1).setDuration(180).start();
            panel.animate().translationX(0).setDuration(220).start();
        }
    }

    private void editAlertRule() {
        AlertRule rule = AlertRule.load(this);
        LinearLayout inputs = column();
        inputs.setPadding(dp(24), dp(8), dp(24), 0);
        EditText amount = alertInput(inputs, "Veri sınırı (GB)", rule.gigabytes(), true);
        EditText duration = alertInput(inputs, "Zaman aralığı (dakika)", Integer.toString(rule.minutes), false);
        TextView message = label("Aralık: 30 dakika–24 saat. Android arka plan kontrolünü geciktirebilir.",
                13, colors.secondary, false);
        message.setPadding(0, dp(12), 0, dp(8));
        inputs.addView(message);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Mobil veri sınırı")
                .setView(inputs).setNegativeButton("Vazgeç", null)
                .setNeutralButton("Varsayılan", null).setPositiveButton("Kaydet", null).create();
        dialog.setOnShowListener(unused -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                amount.setText("3");
                duration.setText(Integer.toString(AlertRule.DEFAULT_MINUTES));
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    AlertRule.parse(amount.getText().toString(), duration.getText().toString()).save(this);
                    dialog.dismiss();
                    render();
                } catch (IllegalArgumentException invalid) {
                    message.setText("0,1–1000 GB ve 30–1440 dakika arasında bir değer gir.");
                    message.setTextColor(colors.warning);
                }
            });
        });
        dialog.show();
    }

    private EditText alertInput(LinearLayout parent, String title, String value, boolean decimal) {
        TextView caption = label(title, 14, colors.text, true);
        caption.setPadding(0, dp(8), 0, 0);
        parent.addView(caption);
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        input.setText(value);
        parent.addView(input);
        return input;
    }

    private void closeDrawer() {
        if (drawer != null) root.removeView(drawer);
        drawer = null;
        drawerOpen = false;
        main.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        main.setDescendantFocusability(LinearLayout.FOCUS_BEFORE_DESCENDANTS);
        settingsButton.requestFocus();
    }

    private void drawerHeading(LinearLayout parent, String text) {
        TextView title = label(text, 15, colors.secondary, true);
        title.setPadding(0, dp(21), 0, dp(8));
        title.setAccessibilityHeading(true);
        parent.addView(title);
    }

    private TextView drawerAction(String text, Runnable callback) {
        TextView link = action(text, callback);
        link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return link;
    }

    private void addNotificationChannel(LinearLayout parent, String title, String id) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(id);
        String status = manager.areNotificationsEnabled() && channel != null
                && channel.getImportance() != NotificationManager.IMPORTANCE_NONE ? "Açık" : "Kapalı";
        parent.addView(drawerAction(title + " · " + status, () -> openNotificationSettings(id)));
    }

    private void openNotificationSettings(String channel) {
        Intent settings = new Intent(channel == null ? Settings.ACTION_APP_NOTIFICATION_SETTINGS
                : Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        if (channel != null) settings.putExtra(Settings.EXTRA_CHANNEL_ID, channel);
        try {
            startActivity(settings);
        } catch (ActivityNotFoundException unavailable) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", getPackageName(), null)));
        }
    }

    private void enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                || !getPreferences(MODE_PRIVATE).getBoolean("asked_notifications", false))) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        openNotificationSettings(null);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1) render();
    }

    private void updated(int token, View progress, Runnable show) {
        runOnUiThread(() -> {
            if (token == revision && !isFinishing() && !isDestroyed()) {
                content.removeView(progress);
                show.run();
            }
        });
    }

    private void openUsageSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (ActivityNotFoundException unavailable) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private long read(int uid, long start, long end) {
        if (uid == Integer.MIN_VALUE) return Usage.total(this, start, end);
        return Usage.apps(this, start, end).getOrDefault(uid, 0L);
    }

    interface PeriodReader { long bytes(long start, long end); }

    static Long populatePeriods(List<Period> periods, long now, PeriodReader reader) {
        long end = Math.min(now, periods.get(periods.size() - 1).end);
        long previous = 0;
        for (int i = periods.size() - 1; i >= 0; i--) {
            if (Thread.currentThread().isInterrupted()) return null;
            Period period = periods.get(i);
            if (period.start >= end) {
                period.bytes = -1;
                continue;
            }
            // Use a shared end time to avoid counting Android's active bucket in several bars.
            long suffix = Math.max(previous, reader.bytes(period.start, end));
            period.bytes = suffix - previous;
            previous = suffix;
        }
        return previous;
    }

    private void addDatePicker(LocalDate today, ZoneId zone) {
        LinearLayout dateRow = row();
        dateRow.addView(action("‹", () -> {
            daysBack++;
            selectedBar = -1;
            render();
        }, daysBack < 29, "Önceki gün"));
        LocalDate selected = today.minusDays(daysBack);
        String dateLabel = daysBack == 0 ? "Bugün" : selected.format(FULL_DATE);
        TextView day = action(dateLabel + "  ▾", () -> {
            DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                LocalDate date = LocalDate.of(year, month + 1, dayOfMonth);
                daysBack = Math.toIntExact(today.toEpochDay() - date.toEpochDay());
                selectedBar = -1;
                render();
            }, selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth());
            dialog.getDatePicker().setMaxDate(Usage.startOf(today.plusDays(1), zone) - 1);
            dialog.getDatePicker().setMinDate(Usage.startOf(today.minusDays(29), zone));
            dialog.show();
        }, true, "Tarih seç. " + dateLabel);
        dateRow.addView(day, new LinearLayout.LayoutParams(0, dp(48), 1));
        dateRow.addView(action("›", () -> {
            daysBack--;
            selectedBar = -1;
            render();
        }, daysBack > 0, "Sonraki gün"));
        content.addView(dateRow);
    }

    private interface Selection { void select(int value); }

    private void addSelector(String[] items, int current, Selection callback) {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setFillViewport(true);
        LinearLayout switches = row();
        for (int i = 0; i < items.length; i++) {
            int index = i;
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, dp(43), 1);
            layout.setMargins(dp(2), dp(3), dp(2), dp(3));
            switches.addView(chip(items[i], i == current, () -> callback.select(index)), layout);
        }
        horizontal.addView(switches);
        content.addView(horizontal);
        View chosen = switches.getChildAt(current);
        horizontal.post(() -> {
            if (chosen.getRight() > horizontal.getWidth()) {
                horizontal.scrollTo(chosen.getRight() - horizontal.getWidth() + dp(4), 0);
            }
        });
    }

    static String summaryTitle(int selectedTab, boolean appDetail, int mode, int daysBack, LocalDate today) {
        if (appDetail) return "Seçilen aralıktaki toplam";
        if (mode == 0 && daysBack > 0) {
            String metric = selectedTab == 2 ? "İnternet paylaşımı" : "Mobil veri";
            return metric + " · " + today.minusDays(daysBack).format(FULL_DATE);
        }
        return selectedTab == 2 ? "Bugünkü internet paylaşımı" : "Bugünkü mobil veri";
    }

    private void setHeroStyle(LinearLayout view, int selectedTab) {
        boolean hotspot = selectedTab == 2;
        int top = colors.light ? (hotspot ? 0xffd8f3ea : 0xffdce5ff) : (hotspot ? 0xff143c3c : 0xff28355f);
        int bottom = colors.light ? (hotspot ? 0xfff4fcf8 : 0xfff5f7ff) : (hotspot ? 0xff16262e : 0xff1d203b);
        int stroke = colors.light ? (hotspot ? 0xffa8d7c9 : 0xffb6c5ee) : (hotspot ? 0xff346660 : 0xff485581);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{top, bottom});
        gradient.setCornerRadius(dp(22));
        gradient.setStroke(dp(1), stroke);
        view.setBackground(gradient);
        view.setPadding(dp(19), dp(19), dp(19), dp(17));
    }

    private void showChart(List<Period> periods, long total, int selectedTab, Usage.App selectedApp,
                           ZonedDateTime snapshot) {
        int accent = selectedTab == 2 ? colors.hotspot : colors.accent;
        LinearLayout usage = selectedApp == null ? hero : card();
        usage.removeAllViews();
        setHeroStyle(usage, selectedTab);
        String totalTitle = summaryTitle(selectedTab, selectedApp != null, mode, daysBack, snapshot.toLocalDate());
        TextView heading = label(totalTitle, 14, colors.text, true);
        heading.setAccessibilityHeading(true);
        usage.addView(heading);
        TextView amount = label(Usage.format(total), 44, accent, true);
        amount.setFontFeatureSettings("tnum");
        amount.setPadding(0, dp(4), 0, dp(6));
        usage.addView(amount);
        boolean nowDay = mode != 0 || daysBack == 0;
        String totalHint = selectedApp != null ? "Uygulamanın mobil veri kullanımı"
                : selectedTab == 0 ? (nowDay ? "00:00’dan beri • Hotspot dahil" : "Gün toplamı • Hotspot dahil")
                : total > 0 ? "Mobil veri paylaşımı" : "Android’de bu gün için paylaşım kaydı yok";
        TextView usageHint = label(totalHint, 13, colors.secondary, false);
        usage.addView(usageHint);

        LinearLayout graph = card();
        LinearLayout chartHeader = row();
        TextView chartTitle = label("Kullanım grafiği", 18, colors.text, true);
        chartTitle.setAccessibilityHeading(true);
        chartHeader.addView(chartTitle, new LinearLayout.LayoutParams(0, -2, 1));
        graph.addView(chartHeader);
        selectedBar = availableBar(periods, selectedBar);
        LinearLayout inspector = row();
        inspector.setBackground(shape(colors.background, 12, colors.border));
        TextView detail = label("", 13, colors.text, true);
        detail.setPadding(dp(6), dp(4), dp(6), dp(4));
        detail.setGravity(Gravity.CENTER);
        BarChart chart = new BarChart(periods, accent, selectedBar);
        TextView reset = action("Toplama dön", () -> chart.choose(-1));
        chartHeader.addView(reset);
        TextView previous = action("‹", () -> chart.step(-1), true, "Önceki grafik aralığı");
        TextView next = action("›", () -> chart.step(1), true, "Sonraki grafik aralığı");
        chart.selection = value -> {
            selectedBar = value;
            Period period = value < 0 ? null : periods.get(value);
            heading.setText(period == null ? totalTitle : period.description);
            amount.setText(Usage.format(period == null ? total : period.bytes));
            usageHint.setText(period == null ? totalHint : selectedTab == 2 ? "İnternet paylaşımı" : "Mobil veri kullanımı");
            detail.setText(period == null ? "Grafikten bir aralık seç" : period.description);
            reset.setVisibility(period == null ? View.GONE : View.VISIBLE);
            previous.setEnabled(chart.canStep(-1));
            next.setEnabled(chart.canStep(1));
            if (period != null && graph.isAttachedToWindow()) {
                usage.announceForAccessibility(periodText(period));
            }
        };
        chart.selection.select(selectedBar);
        graph.addView(chart, new LinearLayout.LayoutParams(-1, dp(186)));
        inspector.addView(previous);
        inspector.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
        inspector.addView(next);
        graph.addView(inspector);
        paragraph(graph, selectedTab == 2
                ? "Android, Wi-Fi, USB ve Bluetooth paylaşımını tek toplam olarak bildirir. Kısa aralıklar yaklaşık olabilir."
                : "Kısa aralıklar Android’in kayıt sıklığına göre yaklaşık değerlerdir.");
    }

    private void showApps(List<Usage.App> apps, int selectedRange) {
        LinearLayout list = card();
        TextView title = label("Mobil veri kullanımı", 18, colors.text, true);
        title.setAccessibilityHeading(true);
        list.addView(title);
        if (selectedRange < 2) paragraph(list, "Kısa aralıktaki uygulama değerleri yaklaşık olabilir.");
        if (apps.isEmpty()) {
            paragraph(list, "Bu aralıkta kaydedilmiş uygulama kullanımı yok.");
            return;
        }
        addAppRows(list, apps, result -> {
            app = result;
            selectedBar = -1;
            render();
        });
    }

    private void addAppRows(LinearLayout list, List<Usage.App> apps, Consumer<Usage.App> choose) {
        long max = apps.get(0).bytes;
        for (Usage.App result : apps) {
            LinearLayout entry = column();
            entry.setPadding(dp(8), dp(11), dp(8), dp(11));
            entry.setBackground(interactive(colors.surface, 12, 0));
            if (choose != null) {
                entry.setOnClickListener(view -> choose.accept(result));
                entry.setFocusable(true);
            }
            LinearLayout top = row();
            LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(dp(34), dp(34));
            badgeLayout.setMarginEnd(dp(10));
            top.addView(appIcon(result), badgeLayout);
            TextView name = label(result.name, 15, colors.text, true);
            name.setPadding(0, 0, dp(8), 0);
            top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            top.addView(label(Usage.format(result.bytes) + (choose == null ? "" : "  ›"), 14, colors.accent, true));
            entry.addView(top);
            LinearLayout bar = row();
            bar.setBackground(shape(colors.border, 5, 0));
            bar.setClipToOutline(true);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, dp(4));
            line.setMarginStart(dp(44));
            line.topMargin = dp(8);
            entry.addView(bar, line);
            View mark = new View(this);
            mark.setBackground(shape(colors.accent, 5, 0));
            float fraction = Math.max(0.01f, (float) result.bytes / max);
            bar.setWeightSum(1f);
            bar.addView(mark, new LinearLayout.LayoutParams(0, -1, fraction));
            list.addView(entry);
        }
    }

    private final class SessionPanel {
        private final int token;
        private LinearLayout results;
        private TextView clock;
        private TextView status;
        private TextView toggle;
        private SessionMeter.State state;
        private List<Usage.App> apps = new ArrayList<>();
        private int seenRevision = Integer.MIN_VALUE;
        private boolean loading;
        private boolean unavailable;

        SessionPanel(int token) {
            this.token = token;
        }

        void show() {
            LinearLayout banner = card();
            setHeroStyle(banner, 0);
            banner.addView(label("Veri Sayacı", 20, colors.text, true));
            clock = label("00:00", 40, colors.accent, true);
            clock.setFontFeatureSettings("tnum");
            banner.addView(clock);
            status = label("Sayaç verileri yükleniyor…", 13, colors.secondary, false);
            banner.addView(status);
            toggle = chip("Başlat", true, () -> {
                if (SessionService.phase() == SessionService.IDLE) sessionMetric = SessionMeter.TOTAL;
                SessionService.toggle(Dashboard.this);
                updateControls();
            });
            banner.addView(toggle, new LinearLayout.LayoutParams(-1, dp(48)));
            if (historyId > 0) {
                toggle.setVisibility(View.GONE);
                banner.addView(action("Son ölçüme dön", () -> {
                    historyId = 0;
                    sessionMetric = SessionMeter.TOTAL;
                    render();
                }));
            }
            banner.addView(action("Oturum geçmişi", this::openHistory));
            results = column();
            content.addView(results);
            tick();
        }

        private void tick() {
            if (token != revision || !visible || isFinishing()) return;
            int current = SessionService.revision();
            if (!loading && current != seenRevision) {
                loading = true;
                long wanted = historyId;
                request = queries.submit(() -> {
                    try (SessionMeter meter = new SessionMeter(Dashboard.this)) {
                        SessionMeter.State latest = meter.read(wanted);
                        List<Usage.App> ranked = Usage.ranked(Dashboard.this, latest.appAmounts());
                        runOnUiThread(() -> {
                            if (token != revision || isDestroyed()) return;
                            loading = false;
                            seenRevision = current;
                            state = latest;
                            apps = ranked;
                            unavailable = false;
                            updateControls();
                            showResults();
                        });
                    } catch (RuntimeException failure) {
                        android.util.Log.w("DataWarner", "Sayaç ekranı okunamadı", failure);
                        runOnUiThread(() -> {
                            if (token != revision || isDestroyed()) return;
                            loading = false;
                            seenRevision = current;
                            unavailable = true;
                            updateControls();
                        });
                    }
                });
            }
            updateControls();
            clock.postDelayed(this::tick, 1000);
        }

        private void updateControls() {
            int phase = SessionService.phase();
            boolean hasPoints = state != null && !state.points.isEmpty();
            boolean running = historyId == 0 && phase == SessionService.RUNNING;
            boolean available = phase == SessionService.IDLE || running;
            toggle.setEnabled(available);
            toggle.setAlpha(available ? 1f : 0.62f);
            toggle.setText(running ? "Bitir" : hasPoints ? "Yeni ölçüm başlat" : "Başlat");
            long time = hasPoints ? state.duration() : 0;
            if (hasPoints && state.storedActive && running) {
                time = SystemClock.elapsedRealtime() - state.points.get(0).elapsed;
            }
            clock.setText(SessionMeter.duration(time));
            String error = SessionService.error();
            if (error != null) status.setText(error);
            else if (unavailable) status.setText("Sayaç kaydı okunamadı. Yenile’ye dokun.");
            else if (historyId == 0 && phase == SessionService.STARTING) status.setText("Ölçüm başlatılıyor…");
            else if (historyId == 0 && phase == SessionService.STOPPING) status.setText("Son ölçüm kaydediliyor…");
            else if (running) status.setText("Ölçüm sürüyor · Mobil veri");
            else if (hasPoints && (state.interrupted || (state.storedActive && phase == SessionService.IDLE))) {
                status.setText("Ölçüm kesildi · Son kayda kadar");
            } else status.setText(hasPoints ? (historyId > 0 ? "Geçmiş oturum" : "Son ölçüm")
                    : "Başlat ve Bitir arasındaki mobil veri ölçülür.");
        }

        private void showResults() {
            results.removeAllViews();
            if (state.points.isEmpty()) return;

            LinearLayout totals = sessionCard();
            totals.addView(label(state.title.isEmpty() ? "Mobil veri toplamı" : state.title, 17, colors.text, true));
            totals.addView(label(Usage.format(state.amount(SessionMeter.TOTAL)), 32, colors.accent, true));
            long hourly = state.hourlyEstimate();
            if (hourly >= 0) paragraph(totals, "Bu hızla yaklaşık " + Usage.format(hourly) + "/saat");
            totals.addView(label("Hotspot · " + Usage.format(state.amount(Usage.HOTSPOT)), 16, colors.hotspot, true));
            ZoneId zone = ZoneId.systemDefault();
            String start = Instant.ofEpochMilli(state.points.get(0).wall).atZone(zone).format(SESSION_TIME);
            String end = Instant.ofEpochMilli(state.last().wall).atZone(zone).format(SESSION_TIME);
            paragraph(totals, start + " – " + end);
            totals.addView(action("Oturumu adlandır", () -> nameSession(state.id, state.title)));

            LinearLayout graph = sessionCard();
            LinearLayout switches = row();
            addMetric(switches, "Toplam", SessionMeter.TOTAL);
            addMetric(switches, "Hotspot", Usage.HOTSPOT);
            graph.addView(switches);
            String metric = sessionMetric == SessionMeter.TOTAL ? "Toplam mobil veri" :
                    sessionMetric == Usage.HOTSPOT ? "Hotspot" : "Seçili uygulama";
            for (Usage.App item : apps) {
                if (item.uid == sessionMetric) metric = item.name;
            }
            TextView detail = label(metric + " · " + Usage.format(state.amount(sessionMetric)), 16, colors.text, true);
            detail.setPadding(0, dp(9), 0, 0);
            graph.addView(detail);
            List<Period> periods = sessionPeriods(state.series(sessionMetric));
            if (periods.isEmpty()) {
                paragraph(graph, sessionMetric == Usage.HOTSPOT && state.points.size() > 1
                        ? "Bu oturum için Android hotspot kaydı sunmadı." : "Grafik için en az iki ölçüm gerekiyor.");
            } else {
                BarChart chart = new BarChart(periods, sessionMetric == Usage.HOTSPOT ? colors.hotspot : colors.accent, -1);
                chart.selection = index -> {
                    if (index >= 0) detail.setText(periodText(periods.get(index)));
                };
                graph.addView(chart, new LinearLayout.LayoutParams(-1, dp(174)));
            }
            paragraph(graph, "Uygulama ve hotspot tüketimi Android kayıtlarından tahmin edilir. Kısa ölçümlerde gecikebilir.");

            LinearLayout breakdown = sessionCard();
            breakdown.addView(label("Uygulamalara göre", 18, colors.text, true));
            if (apps.isEmpty()) paragraph(breakdown, "Bu aralıkta henüz uygulama kullanımı raporlanmadı.");
            else addAppRows(breakdown, apps, item -> {
                sessionMetric = item.uid;
                showResults();
                results.post(() -> {
                    if (results.getChildCount() > 1) {
                        View updated = results.getChildAt(1);
                        updated.requestRectangleOnScreen(new android.graphics.Rect(0, 0, updated.getWidth(), dp(160)), false);
                    }
                });
            });
        }

        private void openHistory() {
            queries.execute(() -> {
                try (SessionMeter meter = new SessionMeter(Dashboard.this)) {
                    List<SessionMeter.Entry> history = meter.history();
                    runOnUiThread(() -> {
                        if (token != revision || !visible || isFinishing()) return;
                        AlertDialog.Builder dialog = new AlertDialog.Builder(Dashboard.this)
                                .setTitle("Oturum geçmişi").setNegativeButton("Kapat", null);
                        if (history.isEmpty()) dialog.setMessage("Henüz kayıtlı ölçüm yok.");
                        else {
                            String[] entries = new String[history.size()];
                            for (int i = 0; i < entries.length; i++) {
                                SessionMeter.Entry item = history.get(i);
                                String date = Instant.ofEpochMilli(item.started).atZone(ZoneId.systemDefault()).format(SESSION_TIME);
                                entries[i] = item.title.isEmpty() ? date : item.title + " · " + date;
                            }
                            dialog.setItems(entries, (d, index) -> {
                                historyId = history.get(index).id;
                                sessionMetric = SessionMeter.TOTAL;
                                render();
                            });
                        }
                        dialog.show();
                    });
                } catch (RuntimeException error) {
                    runOnUiThread(() -> {
                        if (token == revision) status.setText("Oturum geçmişi okunamadı.");
                    });
                }
            });
        }

        private void nameSession(long id, String name) {
            EditText input = new EditText(Dashboard.this);
            input.setSingleLine(true);
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
            input.setText(name);
            new AlertDialog.Builder(Dashboard.this).setTitle("Oturum adı").setView(input)
                    .setNegativeButton("Vazgeç", null).setPositiveButton("Kaydet", (d, w) -> {
                        String title = input.getText().toString().trim();
                        queries.execute(() -> {
                            try (SessionMeter meter = new SessionMeter(Dashboard.this)) {
                                meter.rename(id, title);
                                runOnUiThread(() -> {
                                    if (token == revision) seenRevision = Integer.MIN_VALUE;
                                });
                            } catch (RuntimeException error) {
                                runOnUiThread(() -> {
                                    if (token == revision) status.setText("Oturum adı kaydedilemedi.");
                                });
                            }
                        });
                    }).show();
        }

        private LinearLayout sessionCard() {
            return newCard(results);
        }

        private void addMetric(LinearLayout view, String text, int uid) {
            TextView choice = chip(text, sessionMetric == uid, () -> {
                sessionMetric = uid;
                showResults();
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(44), 1);
            size.setMargins(dp(2), 0, dp(2), 0);
            view.addView(choice, size);
        }
    }

    private static List<Period> sessionPeriods(List<SessionMeter.Segment> segments) {
        List<Period> periods = new ArrayList<>();
        for (SessionMeter.Segment segment : segments) {
            String start = SessionMeter.duration(segment.start);
            String end = SessionMeter.duration(segment.end);
            Period period = new Period(segment.start, segment.end, start, start + "–" + end);
            period.bytes = segment.bytes;
            periods.add(period);
        }
        return periods;
    }

    private View appIcon(Usage.App app) {
        View icon;
        if (app.icon != null) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageDrawable(app.icon);
            icon = image;
        } else {
            String initial = app.name.isEmpty() ? "•"
                    : new String(Character.toChars(app.name.codePointAt(0))).toUpperCase(Usage.TR);
            TextView badge = label(initial, 15, colors.accent, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(shape(colors.iconBackground, 10, 0));
            icon = badge;
        }
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private static List<Period> periods(LocalDate today, ZoneId zone, int mode, int daysBack) {
        List<Period> periods = new ArrayList<>();
        if (mode == 0) {
            LocalDate date = today.minusDays(daysBack);
            ZonedDateTime start = date.atStartOfDay(zone);
            ZonedDateTime end = date.plusDays(1).atStartOfDay(zone);
            for (ZonedDateTime time = start; time.isBefore(end); time = time.plusHours(1)) {
                periods.add(new Period(time.toInstant().toEpochMilli(), time.plusHours(1).toInstant().toEpochMilli(),
                        time.format(CLOCK), time.format(DATE) + " · " + time.format(CLOCK)
                                + "–" + time.plusHours(1).format(CLOCK)));
            }
        } else {
            LocalDate limit = today.plusDays(1);
            for (LocalDate date = today.minusDays(29); date.isBefore(limit); ) {
                LocalDate next = mode == 1 ? date.plusDays(1) :
                        date.plusDays(8L - date.getDayOfWeek().getValue());
                if (next.isAfter(limit)) next = limit;
                String name = date.format(DATE);
                String span = mode == 1 ? name : name + " – " + next.minusDays(1).format(DATE);
                periods.add(new Period(Usage.startOf(date, zone), Usage.startOf(next, zone),
                        mode == 1 ? Integer.toString(date.getDayOfMonth()) : name, span));
                date = next;
            }
        }
        return periods;
    }

    private static List<Period> rollingPeriods(long start, long end, ZoneId zone, int range) {
        List<Period> periods = new ArrayList<>();
        long step = range <= 1 ? 15 * 60_000L : range <= 3 ? 3 * Usage.HOUR : 24 * Usage.HOUR;
        for (long cursor = start; cursor < end; cursor += step) {
            long boundary = Math.min(end, cursor + step);
            ZonedDateTime date = Instant.ofEpochMilli(cursor).atZone(zone);
            ZonedDateTime until = Instant.ofEpochMilli(boundary).atZone(zone);
            String time = date.format(CLOCK);
            String last = until.toLocalDate().equals(date.toLocalDate()) ? until.format(CLOCK)
                    : until.format(DATE) + " " + until.format(CLOCK);
            periods.add(new Period(cursor, boundary, range <= 3 ? time : date.format(DATE),
                    date.format(DATE) + " · " + time + "–" + last));
        }
        return periods;
    }

    private static String periodText(Period period) {
        return period.description + "   •   " + Usage.format(period.bytes);
    }

    static int availableBar(List<Period> periods, int index) {
        if (index < 0 || index >= periods.size() || periods.get(index).bytes < 0) return -1;
        return index;
    }

    static final class Period {
        final long start;
        final long end;
        final String label;
        final String description;
        long bytes;

        Period(long start, long end, String label, String description) {
            this.start = start;
            this.end = end;
            this.label = label;
            this.description = description;
        }
    }

    private final class BarChart extends View {
        private final List<Period> periods;
        private final int accent;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final long max;
        private final int lastAvailable;
        private Selection selection;
        private int chosen;

        BarChart(List<Period> periods, int accent, int chosen) {
            super(Dashboard.this);
            this.periods = periods;
            this.accent = accent;
            this.chosen = chosen;
            long peak = 0;
            for (Period period : periods) peak = Math.max(peak, period.bytes);
            max = peak;
            int last = periods.size() - 1;
            while (last >= 0 && periods.get(last).bytes < 0) last--;
            lastAvailable = last;
            describeSelection();
        }

        @Override protected void onDraw(Canvas canvas) {
            float top = dp(25);
            float bottom = getHeight() - dp(27);
            paint.setTextSize(dp(10));
            float left = Math.max(dp(34), paint.measureText(Usage.format(max)) + dp(7));
            float width = Math.max(1, (getWidth() - left) / periods.size());
            paint.setStrokeWidth(dp(1));
            paint.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i <= 2; i++) {
                float y = top + (bottom - top) * i / 2;
                paint.setColor(colors.border);
                canvas.drawLine(left, y, getWidth(), y, paint);
                if (max > 0 || i == 2) {
                    paint.setColor(colors.secondary);
                    canvas.drawText(Usage.format(max - max * i / 2), 0, y + dp(3), paint);
                }
            }
            int every = Math.max(1, (periods.size() + 4) / 5);
            Shader barFill = new LinearGradient(0, top, 0, bottom, accent, withAlpha(accent, 85), Shader.TileMode.CLAMP);
            paint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < periods.size(); i++) {
                Period period = periods.get(i);
                float center = left + width * (i + 0.5f);
                float halfBar = Math.min(dp(17), width * 0.32f);
                if (i == chosen) {
                    paint.setColor(withAlpha(accent, 37));
                    canvas.drawRoundRect(center - width / 2f, top, center + width / 2f, bottom, dp(3), dp(3), paint);
                }
                if (period.bytes >= 0) {
                    float bar = Math.max(dp(2), (bottom - top) * period.bytes / (float) Math.max(1, max));
                    paint.setColor(period.bytes == 0 ? colors.border : accent);
                    paint.setShader(period.bytes > 0 && i != chosen ? barFill : null);
                    canvas.drawRoundRect(center - halfBar, bottom - bar, center + halfBar, bottom, dp(3), dp(3), paint);
                    paint.setShader(null);
                }
                if (i % every == 0 || every == 1) {
                    paint.setColor(colors.secondary);
                    float labelWidth = paint.measureText(period.label);
                    float x = Math.max(labelWidth / 2, Math.min(center, getWidth() - labelWidth / 2));
                    canvas.drawText(period.label, x, bottom + dp(19), paint);
                }
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_CANCEL) return true;
            if (action == MotionEvent.ACTION_UP) {
                paint.setTextSize(dp(10));
                float left = Math.max(dp(34), paint.measureText(Usage.format(max)) + dp(7));
                float usable = Math.max(1, getWidth() - left);
                if (event.getX() >= left && event.getX() < getWidth()) {
                    int index = (int) ((event.getX() - left) * periods.size() / usable);
                    if (availableBar(periods, index) >= 0) {
                        choose(index);
                        performClick();
                    }
                }
                return true;
            }
            return super.onTouchEvent(event);
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }

        void step(int offset) {
            if (canStep(offset)) choose(chosen < 0 ? lastAvailable : chosen + offset);
        }

        boolean canStep(int offset) {
            return chosen < 0 ? lastAvailable >= 0 : availableBar(periods, chosen + offset) >= 0;
        }

        private void describeSelection() {
            setContentDescription(chosen < 0 ? "Kullanım grafiği" : "Kullanım grafiği. " + periodText(periods.get(chosen)));
        }

        private void choose(int index) {
            chosen = index;
            describeSelection();
            selection.select(chosen);
            invalidate();
        }
    }

    private LinearLayout card() {
        return newCard(content);
    }

    private LinearLayout newCard(LinearLayout parent) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(shape(colors.surface, 20, colors.border));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.setMargins(0, dp(10), 0, dp(3));
        parent.addView(card, layout);
        return card;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private void paragraph(LinearLayout parent, String value) {
        TextView copy = label(value, 14, colors.secondary, false);
        copy.setPadding(0, dp(7), 0, dp(7));
        parent.addView(copy);
    }

    private TextView action(String text, Runnable callback) {
        return action(text, callback, true, text);
    }

    private TextView action(String text, Runnable callback, boolean enabled, String description) {
        TextView action = label(text, 15, enabled ? colors.accent : colors.secondary, true);
        action.setPadding(dp(11), 0, dp(11), 0);
        action.setMinimumHeight(dp(48));
        action.setMinimumWidth(dp(44));
        action.setGravity(Gravity.CENTER);
        action.setContentDescription(description);
        action.setBackground(interactive(0x00000000, 10, 0));
        action.setEnabled(enabled);
        action.setFocusable(enabled);
        action.setOnClickListener(view -> callback.run());
        return action;
    }

    private TextView chip(String label, boolean selected, Runnable callback) {
        TextView tab = label(label, 13, selected ? colors.onAccent : colors.secondary, true);
        tab.setPadding(dp(7), 0, dp(7), 0);
        tab.setGravity(Gravity.CENTER);
        tab.setBackground(interactive(selected ? colors.accent : colors.surface, 12, selected ? 0 : colors.border));
        tab.setSelected(selected);
        tab.setOnClickListener(view -> callback.run());
        tab.setFocusable(true);
        return tab;
    }

    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private RippleDrawable interactive(int fill, int radius, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(colors.accent, 52)),
                shape(fill, radius, stroke), shape(colors.text, radius, 0));
    }

    private static int withAlpha(int color, int alpha) {
        return color & 0x00ffffff | alpha << 24;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
