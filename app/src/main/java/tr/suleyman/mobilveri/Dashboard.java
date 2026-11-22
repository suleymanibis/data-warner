package tr.suleyman.mobilveri;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.PathInterpolator;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class Dashboard extends Activity {
    private static final int SESSION_TAB = 3;
    private static final int ALERT_TAB = 4;
    private static final String[] TAB_NAMES = {"Genel", "Uygulamalar", "Hotspot", "Sayaç"};
    private static final int[] TAB_ICONS = {R.drawable.ic_overview, R.drawable.ic_apps, R.drawable.ic_hotspot, R.drawable.ic_timer};
    private static final String[] MODES = {"Saatlik", "Günlük", "Haftalık"};
    private static final String[] RANGES = {"1 sa", "3 sa", "1 gün", "3 gün", "1 hafta", "1 ay"};
    private static final long[] RANGE_HOURS = {1, 3, 24, 72, 168, 720};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", Usage.TR);
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("d MMMM, EEEE", Usage.TR);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Usage.TR);
    private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm:ss", Usage.TR);

    private final ExecutorService queries = Executors.newSingleThreadExecutor();
    private final ExecutorService usageQueries = Executors.newFixedThreadPool(2);
    private final Map<String, UsagePage> usagePages = new LinkedHashMap<>();
    private final Map<Integer, View> tabPreviews = new LinkedHashMap<>();
    private final Set<String> loadingPages = new HashSet<>();
    private int usageGeneration;
    private String awaitedPage;
    private String pendingTabPage;
    private int pendingTab = -1;
    private String pendingSelectionKey;
    private Runnable pendingSelection;
    private SessionSnapshot latestSession;
    private boolean preloadingSession;
    private final PathInterpolator easeOut = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final PathInterpolator easeIn = new PathInterpolator(0.4f, 0f, 1f, 1f);
    private Future<?> request;
    private Appearance colors;
    private FrameLayout root;
    private FrameLayout drawer;
    private View settingsButton;
    private boolean drawerOpen;
    private LinearLayout main;
    private FrameLayout pageFrame;
    private LinearLayout content;
    private TabSwipeLayout swipeLayout;
    private SettingsPage settingsPage = SettingsPage.NONE;
    private DataPlan.Snapshot readySettingsPlan;
    private boolean settingsPlanPrepared;
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
    private boolean historyOpen;
    private List<SessionMeter.Entry> historyEntries;
    private SessionSnapshot historySnapshot;
    private int historyNavigation;
    private long alertEnd;
    private long alertDuration;

    private enum SettingsPage {
        NONE("", 0),
        NOTIFICATIONS("Bildirim Ayarları", R.drawable.ic_notification),
        APPEARANCE("Görünüm Ayarları", R.drawable.ic_appearance),
        PLAN("Mobil Veri Paketi", R.drawable.ic_plan);

        final String title;
        final int icon;

        SettingsPage(String title, int icon) {
            this.title = title;
            this.icon = icon;
        }
    }

    @Override protected void onCreate(Bundle saved) {
        colors = new Appearance(Appearance.useLight(Appearance.mode(this), getResources().getConfiguration().uiMode));
        setTheme(colors.light ? R.style.DataWarnerLight : R.style.DataWarnerDark);
        super.onCreate(saved);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(colors.light ? mask : 0, mask);
        }
        if (saved != null) {
            tab = saved.getInt("tab");
            mode = saved.getInt("mode");
            range = saved.getInt("range", 2);
            daysBack = saved.getInt("daysBack");
            selectedBar = saved.getInt("selectedBar", -1);
            sessionMetric = saved.getInt("sessionMetric", SessionMeter.TOTAL);
            historyId = saved.getLong("historyId");
            historyOpen = saved.getBoolean("historyOpen");
            alertEnd = saved.getLong("alertEnd");
            alertDuration = saved.getLong("alertDuration");
            drawerOpen = saved.getBoolean("drawerOpen");
            settingsPage = SettingsPage.valueOf(saved.getString("settingsPage", SettingsPage.NONE.name()));
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
            historyOpen = false;
            drawerOpen = false;
            settingsPage = SettingsPage.NONE;
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
                settingsPage = SettingsPage.NONE;
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
        state.putBoolean("historyOpen", historyOpen);
        state.putLong("alertEnd", alertEnd);
        state.putLong("alertDuration", alertDuration);
        state.putBoolean("drawerOpen", drawerOpen);
        state.putString("settingsPage", settingsPage.name());
        if (app != null) {
            state.putInt("app_uid", app.uid);
            state.putString("app_name", app.name);
        }
    }

    @Override protected void onDestroy() {
        revision++;
        queries.shutdownNow();
        usageQueries.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (drawer != null) {
            closeDrawer();
        } else if (settingsPage != SettingsPage.NONE) {
            showSettingsPage(SettingsPage.NONE);
        } else if (tab == SESSION_TAB && historyId > 0) {
            openSessionHistory();
        } else if (tab == SESSION_TAB && historyOpen) {
            closeSessionHistory();
        } else if (app != null) {
            changeUsageSelection(null, mode, range, daysBack, -1);
        } else if (tab != 0) {
            selectTab(0);
        } else {
            super.onBackPressed();
        }
    }

    private void render() {
        render(0, false);
    }

    private void render(int direction, boolean fullWidth) {
        FrameLayout previousPage = pageFrame;
        if (request != null) request.cancel(true);
        int token = ++revision;
        awaitedPage = null;
        pendingTabPage = null;
        pendingTab = -1;
        clearPendingSelection();
        tabPreviews.clear();
        root = new FrameLayout(this);
        root.setBackgroundColor(colors.background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return WindowInsets.CONSUMED;
        });
        drawer = null;
        swipeLayout = null;
        main = column();
        main.setBackgroundColor(colors.background);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.requestApplyInsets();
        if (settingsPage != SettingsPage.NONE) {
            renderSettingsPage();
            PageMotion.animate(previousPage, pageFrame, direction, fullWidth, dp(28), easeOut);
            return;
        }

        LinearLayout header = row();
        header.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout menu = row();
        menu.setPadding(dp(4), 0, dp(4), 0);
        menu.addView(icon(R.drawable.ic_menu, colors.text), new LinearLayout.LayoutParams(dp(22), dp(22)));
        ImageView brand = new ImageView(this);
        brand.setImageResource(R.mipmap.ic_launcher);
        brand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logo = new LinearLayout.LayoutParams(dp(30), dp(30));
        logo.setMarginStart(dp(4));
        menu.addView(brand, logo);
        menu.setContentDescription("Ayarlar panelini aç");
        menu.setBackground(interactive(0, 12, 0));
        addPressFeedback(menu);
        menu.setOnClickListener(view -> openDrawer());
        menu.setFocusable(true);
        settingsButton = menu;
        header.addView(menu, new LinearLayout.LayoutParams(-2, dp(48)));
        TextView identity = label(getString(R.string.app_name), 21, colors.text, true);
        identity.setPadding(dp(8), 0, dp(4), 0);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(iconButton(R.drawable.ic_refresh, "Yenile", this::refresh), touchTarget());
        main.addView(header);

        ScrollView scroll = new UsageScrollView(this, colors.accent);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = column();
        content.setPadding(dp(16), dp(2), dp(16), dp(20));
        scroll.addView(content);
        scroll.setBackgroundColor(colors.background);
        boolean usageAllowed = Usage.allowed(this);
        if (usageAllowed && tab != ALERT_TAB && !historyOpen && historyId == 0) {
            swipeLayout = new TabSwipeLayout(this, offset -> previewTab(tab + offset),
                    offset -> selectTab(tab + offset, false));
            swipeLayout.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            swipeLayout.setBackgroundColor(colors.background);
            mountPage(swipeLayout);
        } else {
            mountPage(scroll);
        }
        PageMotion.animate(previousPage, pageFrame, direction, fullWidth, dp(28), easeOut);
        if (drawerOpen) openDrawer();
        if (!usageAllowed) {
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
        if (tab == SESSION_TAB && historyOpen) {
            renderSessionHistory(token);
            return;
        }
        ZonedDateTime snapshot = ZonedDateTime.now();
        if (tab != SESSION_TAB) drawUsageTab(tab, app, snapshot, true);
        preloadAdjacent(snapshot);
        if (tab == SESSION_TAB) {
            new SessionPanel(token).show();
        }
        prepareTabPreviews();
    }

    private void mountPage(View page) {
        pageFrame = new FrameLayout(this);
        pageFrame.addView(page, new FrameLayout.LayoutParams(-1, -1));
        main.addView(pageFrame, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void drawUsageTab(int target, Usage.App selectedApp, ZonedDateTime snapshot, boolean foreground) {
        String key = usageKey(target, selectedApp, snapshot);
        UsagePage page = usagePages.get(key);
        if (foreground) loadUsagePage(target, selectedApp, snapshot);
        if (page == null) {
            if (foreground) {
                awaitedPage = key;
            }
            showPageMessage("Kullanım verileri hazırlanıyor…");
            return;
        }
        if (page.error) {
            showPageMessage("Kullanım verileri okunamadı. Yenile’ye dokun.");
            return;
        }
        LocalDate today = snapshot.toLocalDate();
        ZoneId zone = snapshot.getZone();
        if (target == 1) {
            LinearLayout titleRow = row();
            if (selectedApp != null) {
                titleRow.addView(iconButton(R.drawable.ic_back, "Uygulamalara dön",
                        () -> changeUsageSelection(null, mode, range, daysBack, -1)), touchTarget());
            }
            TextView heading = label(selectedApp == null ? "Uygulama kullanımı" : selectedApp.name, 22, colors.text, true);
            heading.setPadding(dp(4), dp(8), dp(4), dp(8));
            titleRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(titleRow);
        }
        LinearLayout chartPanel = target == 1 && selectedApp == null ? null : card();
        LinearLayout chartSummary = chartPanel == null ? null : column();
        if (chartSummary != null) {
            chartPanel.addView(chartSummary);
        }
        if (target == 1) {
            addSelector(chartPanel == null ? content : chartPanel, RANGES, range, value -> {
                if (range != value) changeUsageSelection(app, mode, value, daysBack, Integer.compare(value, range));
            });
        } else {
            addSelector(chartPanel, MODES, mode, value -> {
                if (mode != value) changeUsageSelection(null, value, range, daysBack, Integer.compare(value, mode));
            });
            if (mode == 0) addDatePicker(chartPanel, today, zone);
        }
        if (chartPanel == null) showApps(page.apps, range);
        else showChart(chartPanel, chartSummary, page.periods, page.total, target, selectedApp, snapshot);
        if (target == 0) {
            addPreparedPlanCard(page.plan);
            if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
                LinearLayout notifications = card();
                notifications.addView(label("Bildirimler kapalı", 15, colors.warning, true));
                notifications.addView(action("Bildirimleri aç", this::enableNotifications));
            }
        }
    }

    private void showPageMessage(String message) {
        paragraph(card(), message);
    }

    private View previewTab(int target) {
        if (target < 0 || target >= TAB_NAMES.length) return null;
        View prepared = tabPreviews.get(target);
        return prepared == null ? buildTabPreview(target) : prepared;
    }

    private void prepareTabPreviews() {
        if (swipeLayout == null) return;
        TabSwipeLayout pager = swipeLayout;
        pager.post(() -> {
            if (pager != swipeLayout || isDestroyed()) return;
            ZonedDateTime now = ZonedDateTime.now();
            for (int target : new int[]{tab - 1, tab + 1}) {
                if (target >= 0 && target < TAB_NAMES.length && !tabPreviews.containsKey(target)
                        && (target == SESSION_TAB ? latestSession != null : usagePages.containsKey(usageKey(target, null, now)))) {
                    tabPreviews.put(target, buildTabPreview(target));
                }
            }
        });
    }

    private View buildTabPreview(int target) {
        ScrollView preview = new UsageScrollView(this, colors.accent);
        preview.setBackgroundColor(colors.background);
        preview.setFillViewport(true);
        preview.setVerticalScrollBarEnabled(false);
        LinearLayout previewContent = column();
        previewContent.setPadding(dp(16), dp(2), dp(16), dp(20));
        preview.addView(previewContent);
        LinearLayout originalContent = content;
        TabSwipeLayout originalSwipe = swipeLayout;
        Usage.App originalApp = app;
        int originalBar = selectedBar;
        int originalMetric = sessionMetric;
        content = previewContent;
        swipeLayout = null;
        app = null;
        selectedBar = -1;
        sessionMetric = SessionMeter.TOTAL;
        try {
            if (target == SESSION_TAB) new SessionPanel(revision).show(false);
            else drawUsageTab(target, null, ZonedDateTime.now(), false);
        } finally {
            content = originalContent;
            swipeLayout = originalSwipe;
            app = originalApp;
            selectedBar = originalBar;
            sessionMetric = originalMetric;
        }
        return preview;
    }

    private void refresh() {
        usageGeneration++;
        usagePages.clear();
        loadingPages.clear();
        latestSession = null;
        settingsPlanPrepared = false;
        readySettingsPlan = null;
        render();
    }

    private String usageKey(int target, Usage.App selectedApp, ZonedDateTime snapshot) {
        return usageKey(target, selectedApp, snapshot, mode, range, daysBack);
    }

    private String usageKey(int target, Usage.App selectedApp, ZonedDateTime snapshot,
                            int selectedMode, int selectedRange, int selectedDay) {
        String period = target == 1 ? selectedRange + ":" + (selectedApp == null ? "all" : selectedApp.uid)
                : selectedMode + ":" + selectedDay;
        return target + ":" + period + ":" + snapshot.toLocalDate() + ":" + snapshot.getZone();
    }

    private void clearPendingSelection() {
        pendingSelection = null;
        pendingSelectionKey = null;
    }

    private void changeUsageSelection(Usage.App selectedApp, int newMode, int newRange, int newDay, int direction) {
        pendingTab = -1;
        pendingTabPage = null;
        ZonedDateTime now = ZonedDateTime.now();
        String key = usageKey(tab, selectedApp, now, newMode, newRange, newDay);
        Runnable show = () -> {
            app = selectedApp;
            mode = newMode;
            range = newRange;
            daysBack = newDay;
            selectedBar = -1;
            render(direction, false);
        };
        if (usagePages.containsKey(key)) {
            show.run();
            return;
        }
        pendingSelectionKey = key;
        pendingSelection = show;
        loadUsagePage(tab, selectedApp, now, newMode, newRange, newDay);
    }

    private void preloadAdjacent(ZonedDateTime snapshot) {
        for (int offset : new int[]{-1, 1, -2, 2, -3, 3}) {
            int target = tab + offset;
            if (target >= 0 && target < SESSION_TAB) loadUsagePage(target, null, snapshot);
        }
        if (tab != SESSION_TAB) preloadLatestSession();
    }

    private void loadUsagePage(int selectedTab, Usage.App selectedApp, ZonedDateTime snapshot) {
        loadUsagePage(selectedTab, selectedApp, snapshot, mode, range, daysBack);
    }

    private void loadUsagePage(int selectedTab, Usage.App selectedApp, ZonedDateTime snapshot,
                               int selectedMode, int selectedRange, int selectedDay) {
        String key = usageKey(selectedTab, selectedApp, snapshot, selectedMode, selectedRange, selectedDay);
        UsagePage cached = usagePages.get(key);
        if (cached != null && SystemClock.elapsedRealtime() - cached.loadedAt < 60_000) return;
        if (!loadingPages.add(key)) return;
        int generation = usageGeneration;
        usageQueries.execute(() -> {
            UsagePage page;
            try {
                page = readUsagePage(selectedTab, selectedApp, snapshot, selectedMode, selectedRange, selectedDay);
            } catch (RuntimeException error) {
                android.util.Log.w("MobilVeri", "Kullanım verileri okunamadı", error);
                page = new UsagePage(null, 0, null, null, true);
            }
            UsagePage result = page;
            runOnUiThread(() -> {
                if (generation != usageGeneration || isDestroyed()) return;
                loadingPages.remove(key);
                usagePages.put(key, result);
                if (usagePages.size() > 12) usagePages.remove(usagePages.keySet().iterator().next());
                if (visible && key.equals(pendingSelectionKey)) pendingSelection.run();
                else if (visible && key.equals(pendingTabPage)) selectTab(pendingTab, true);
                else if (visible && pendingTab < 0 && pendingSelection == null && key.equals(awaitedPage)) {
                    int token = revision;
                    Runnable update = () -> {
                        if (visible && token == revision && pendingTab < 0 && pendingSelection == null) render();
                    };
                    if (swipeLayout == null) update.run();
                    else swipeLayout.whenGestureEnds(update);
                } else prepareTabPreviews();
            });
        });
    }

    private UsagePage readUsagePage(int target, Usage.App selectedApp, ZonedDateTime snapshot,
                                   int selectedMode, int selectedRange, int selectedDay) {
        long now = snapshot.toInstant().toEpochMilli();
        long start = now - RANGE_HOURS[selectedRange] * Usage.HOUR;
        if (target == 1 && selectedApp == null) {
            return new UsagePage(null, 0, Usage.rankedUserApps(this, Usage.apps(this, start, now)), null, false);
        }
        int uid = target == 2 ? Usage.HOTSPOT : selectedApp == null ? Integer.MIN_VALUE : selectedApp.uid;
        ZoneId zone = snapshot.getZone();
        List<Period> series = selectedApp == null ? periods(snapshot.toLocalDate(), zone, selectedMode, selectedDay)
                : rollingPeriods(start, now, zone, selectedRange);
        Long total = populatePeriods(series, now, (from, to) -> read(uid, from, to));
        if (total == null) throw new IllegalStateException("Usage query interrupted");
        DataPlan.Snapshot plan = null;
        if (target == 0) {
            DataPlan configured = DataPlan.load(this);
            try {
                if (configured.capacity > 0) plan = configured.read(this, snapshot);
            } catch (RuntimeException error) {
                android.util.Log.w("DataWarner", "Paket verisi okunamadı", error);
            }
        }
        return new UsagePage(series, total, null, plan, false);
    }

    private static final class UsagePage {
        final List<Period> periods;
        final long total;
        final List<Usage.App> apps;
        final DataPlan.Snapshot plan;
        final boolean error;
        final long loadedAt = SystemClock.elapsedRealtime();

        UsagePage(List<Period> periods, long total, List<Usage.App> apps, DataPlan.Snapshot plan, boolean error) {
            this.periods = periods;
            this.total = total;
            this.apps = apps;
            this.plan = plan;
            this.error = error;
        }
    }

    private void addPlanCard(int token, ZonedDateTime now) {
        LinearLayout panel = card();
        DataPlan plan = DataPlan.load(this);
        TextView details = planHeading(panel, plan, "Paket hesaplanıyor…");
        if (plan.capacity == 0) return;
        queries.submit(() -> {
            try {
                DataPlan.Snapshot value = plan.read(this, now);
                runOnUiThread(() -> {
                    if (token == revision && !isDestroyed()) showPlanSnapshot(panel, details, value);
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (token == revision) details.setText("Paket verisi şu anda okunamadı.");
                });
            }
        });
    }

    private void addPreparedPlanCard(DataPlan.Snapshot snapshot) {
        LinearLayout panel = card();
        TextView details = planHeading(panel, DataPlan.load(this), "Paket verisi şu anda okunamadı.");
        if (snapshot != null) showPlanSnapshot(panel, details, snapshot);
    }

    private TextView planHeading(LinearLayout panel, DataPlan plan, String status) {
        LinearLayout heading = row();
        heading.addView(label("Mobil veri paketi", 17, colors.text, true), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(iconButton(R.drawable.ic_edit, plan.capacity == 0 ? "Paketi ayarla" : "Paketi düzenle",
                this::editPlan), touchTarget());
        panel.addView(heading);
        TextView details = label(plan.capacity == 0 ? "Paket boyutunu ve yenilenme gününü gir." : status,
                14, colors.secondary, false);
        panel.addView(details);
        return details;
    }

    private void showPlanSnapshot(LinearLayout panel, TextView details, DataPlan.Snapshot value) {
        details.setText(Usage.format(value.remaining()) + " kaldı");
        details.setTextColor(colors.text);
        details.setTextSize(24);
        details.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(value.level() > 0 ? colors.warning : colors.accent));
        progress.setProgress(value.percent());
        panel.addView(progress);
        paragraph(panel, "Paket " + Usage.format(value.capacity) + " · Yenileme " + value.next.format(DATE)
                + " (" + value.days + " gün)\nGünlük bütçe " + Usage.format(value.perDay()) + " · Android ölçümü");
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
        refresh();
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
                List<Usage.App> apps = Usage.rankedUserApps(this, counts);
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
                    else addAppRows(breakdown, apps, 5, null);
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
        nav.setPadding(dp(8), dp(4), dp(8), dp(4));
        nav.setElevation(dp(3));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int target = i;
            boolean selected = tab == i;
            int color = selected ? colors.accent : colors.secondary;
            LinearLayout view = column();
            view.setGravity(Gravity.CENTER);
            view.setMinimumHeight(dp(56));
            view.setBackground(interactive(0, 12, 0));
            addPressFeedback(view);
            ImageView image = icon(TAB_ICONS[i], color);
            image.setPadding(dp(11), dp(4), dp(11), dp(4));
            if (selected) image.setBackground(shape(colors.iconBackground, 16, 0));
            view.addView(image, new LinearLayout.LayoutParams(dp(44), dp(30)));
            TextView caption = label(TAB_NAMES[i], 11, color, selected);
            caption.setGravity(Gravity.CENTER);
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.addView(caption, new LinearLayout.LayoutParams(-1, -2));
            view.setContentDescription(TAB_NAMES[i]);
            view.setSelected(selected);
            view.setFocusable(true);
            view.setOnClickListener(clicked -> selectTab(target));
            nav.addView(view, new LinearLayout.LayoutParams(0, -2, i == 1 ? 1.25f : 1f));
        }
        main.addView(nav);
    }

    private void selectTab(int target) {
        selectTab(target, true);
    }

    private void selectTab(int target, boolean animate) {
        if (target < 0 || target >= TAB_NAMES.length) return;
        clearPendingSelection();
        pendingTab = -1;
        pendingTabPage = null;
        if (target == tab && app == null && historyId == 0 && !historyOpen) return;
        ZonedDateTime now = ZonedDateTime.now();
        if (target != SESSION_TAB && !usagePages.containsKey(usageKey(target, null, now))) {
            pendingTab = target;
            pendingTabPage = usageKey(target, null, now);
            loadUsagePage(target, null, now);
            return;
        }
        if (target == SESSION_TAB && tab != SESSION_TAB && latestSession == null) {
            pendingTab = target;
            preloadLatestSession();
            return;
        }
        int direction = target == tab ? -1 : Integer.compare(target, tab);
        boolean differentTab = target != tab;
        selectedBar = -1;
        tab = target;
        historyId = 0;
        historyOpen = false;
        sessionMetric = SessionMeter.TOTAL;
        app = null;
        render(animate ? direction : 0, differentTab);
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
        ImageButton close = iconButton(R.drawable.ic_close, "Ayarlar panelini kapat", this::closeDrawer);
        heading.addView(close, touchTarget());
        settings.addView(heading);
        for (SettingsPage page : SettingsPage.values()) {
            if (page == SettingsPage.NONE) continue;
            settings.addView(settingsLink(page.title, null, page.icon, () -> closeDrawer(() -> showSettingsPage(page))));
        }

        main.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        main.setDescendantFocusability(LinearLayout.FOCUS_BLOCK_DESCENDANTS);
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        close.requestFocus();
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            scrim.setAlpha(0);
            panel.setTranslationX(-dimensions.width);
            scrim.animate().alpha(1).setDuration(210).setInterpolator(easeOut).start();
            panel.animate().translationX(0).setDuration(250).setInterpolator(easeOut).start();
        }
    }

    private void showSettingsPage(SettingsPage page) {
        if (page == SettingsPage.PLAN && Usage.allowed(this)) {
            DataPlan plan = DataPlan.load(this);
            ZonedDateTime now = ZonedDateTime.now();
            UsagePage overview = usagePages.get(usageKey(0, null, now));
            if (plan.capacity > 0 && overview == null) {
                int token = revision;
                queries.execute(() -> {
                    DataPlan.Snapshot value = null;
                    try {
                        value = plan.read(this, now);
                    } catch (RuntimeException error) {
                        android.util.Log.w("DataWarner", "Paket verisi okunamadı", error);
                    }
                    DataPlan.Snapshot prepared = value;
                    runOnUiThread(() -> {
                        if (token != revision || !visible || isDestroyed() || drawerOpen
                                || pendingTab >= 0 || pendingSelection != null) return;
                        readySettingsPlan = prepared;
                        settingsPlanPrepared = true;
                        navigateToSettings(page);
                    });
                });
                return;
            }
            readySettingsPlan = overview == null ? null : overview.plan;
            settingsPlanPrepared = true;
        }
        navigateToSettings(page);
    }

    private void navigateToSettings(SettingsPage page) {
        settingsPage = page;
        render(page == SettingsPage.NONE ? -1 : 1, false);
    }

    private void renderSettingsPage() {
        LinearLayout header = row();
        header.setPadding(dp(12), dp(4), dp(16), dp(4));
        header.addView(iconButton(R.drawable.ic_back, "Geri", () -> showSettingsPage(SettingsPage.NONE)), touchTarget());
        TextView title = label(settingsPage.title, 21, colors.text, true);
        title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        main.addView(header);

        ScrollView scroll = new UsageScrollView(this, colors.accent);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(dp(16), dp(2), dp(16), dp(20));
        scroll.addView(content);
        scroll.setBackgroundColor(colors.background);
        mountPage(scroll);
        switch (settingsPage) {
            case NOTIFICATIONS:
                addNotificationPreferences();
                break;
            case APPEARANCE:
                addAppearancePreferences();
                break;
            case PLAN:
                if (Usage.allowed(this)) {
                    if (settingsPlanPrepared) addPreparedPlanCard(readySettingsPlan);
                    else addPlanCard(revision, ZonedDateTime.now());
                } else {
                    LinearLayout plan = card();
                    paragraph(plan, "Paket tüketimini görmek için kullanım erişimi gerekir.");
                    plan.addView(settingsLink("Kullanım erişimini aç", null, 0, this::openUsageSettings));
                    plan.addView(settingsLink("Paketi düzenle", null, 0, this::editPlan));
                }
                break;
            default:
                break;
        }
    }

    private void addNotificationPreferences() {
        LinearLayout channels = card();
        boolean enabled = getSystemService(NotificationManager.class).areNotificationsEnabled();
        channels.addView(settingsLink("Tüm bildirimler", enabled ? "Açık" : "Kapalı", 0, this::enableNotifications));
        addNotificationChannel(channels, "Günlük kullanım özeti", Monitor.SUMMARY_CHANNEL);
        addNotificationChannel(channels, "Veri sınırı bildirimi", Monitor.ALERT_CHANNEL);
        addNotificationChannel(channels, "Aylık paket uyarısı", Monitor.PLAN_CHANNEL);
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager.class).canScheduleExactAlarms()) {
            channels.addView(settingsLink("Kesin alarm izni", "Özet bildirimi zamanında gelsin", 0,
                    this::openExactAlarmSettings));
        }
        card().addView(settingsLink("Mobil veri sınırı", AlertRule.load(this).limit(), 0, this::editAlertRule));
    }

    private void openExactAlarmSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        }
    }

    private void addAppearancePreferences() {
        LinearLayout theme = card();
        TextView heading = label("Tema", 16, colors.text, true);
        heading.setAccessibilityHeading(true);
        theme.addView(heading);
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
        theme.addView(modes);
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
        closeDrawer(null);
    }

    private void closeDrawer(Runnable afterClose) {
        if (drawer == null || !drawerOpen) return;
        drawerOpen = false;
        FrameLayout closing = drawer;
        View scrim = closing.getChildAt(0);
        View panel = closing.getChildAt(1);
        closing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        View inputShield = new View(this);
        inputShield.setElevation(panel.getElevation() + 1);
        inputShield.setClickable(true);
        closing.addView(inputShield, new FrameLayout.LayoutParams(-1, -1));
        Runnable finish = () -> {
            if (drawer != closing) return;
            root.removeView(closing);
            drawer = null;
            main.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            main.setDescendantFocusability(LinearLayout.FOCUS_BEFORE_DESCENDANTS);
            settingsButton.requestFocus();
            if (afterClose != null) afterClose.run();
        };
        scrim.animate().cancel();
        panel.animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finish.run();
            return;
        }
        scrim.animate().alpha(0).setDuration(190).setInterpolator(easeIn).start();
        panel.animate().translationX(-panel.getLayoutParams().width).setDuration(190)
                .setInterpolator(easeIn).withEndAction(finish).start();
    }

    private View settingsLink(String title, String detail, int iconRes, Runnable callback) {
        LinearLayout link = row();
        link.setMinimumHeight(dp(54));
        link.setPadding(dp(10), dp(8), dp(8), dp(8));
        link.setBackground(interactive(0, 12, 0));
        if (iconRes != 0) {
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(22), dp(22));
            size.setMarginEnd(dp(13));
            link.addView(icon(iconRes, colors.accent), size);
        }
        LinearLayout text = column();
        text.addView(label(title, 15, colors.text, true));
        if (detail != null) text.addView(label(detail, 13, colors.secondary, false));
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        link.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        link.addView(icon(R.drawable.ic_next, colors.secondary), new LinearLayout.LayoutParams(dp(20), dp(20)));
        link.setContentDescription(detail == null ? title : title + ", " + detail);
        link.setFocusable(true);
        link.setOnClickListener(view -> callback.run());
        addPressFeedback(link);
        return link;
    }

    private void addNotificationChannel(LinearLayout parent, String title, String id) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(id);
        String status = manager.areNotificationsEnabled() && channel != null
                && channel.getImportance() != NotificationManager.IMPORTANCE_NONE ? "Açık" : "Kapalı";
        parent.addView(settingsLink(title, status, 0, () -> openNotificationSettings(id)));
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

    private void addDatePicker(LinearLayout panel, LocalDate today, ZoneId zone) {
        LinearLayout dateRow = row();
        ImageButton previous = iconButton(R.drawable.ic_previous, "Önceki gün",
                () -> changeUsageSelection(null, mode, range, daysBack + 1, -1));
        previous.setEnabled(daysBack < 29);
        dateRow.addView(previous, touchTarget());
        LocalDate selected = today.minusDays(daysBack);
        String dateLabel = daysBack == 0 ? "Bugün" : selected.format(FULL_DATE);
        TextView day = action(dateLabel, () -> {
            DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                LocalDate date = LocalDate.of(year, month + 1, dayOfMonth);
                int chosen = Math.toIntExact(today.toEpochDay() - date.toEpochDay());
                if (chosen != daysBack) changeUsageSelection(null, mode, range, chosen, Integer.compare(daysBack, chosen));
            }, selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth());
            dialog.getDatePicker().setMaxDate(Usage.startOf(today.plusDays(1), zone) - 1);
            dialog.getDatePicker().setMinDate(Usage.startOf(today.minusDays(29), zone));
            dialog.show();
        }, true, "Tarih seç. " + dateLabel);
        android.graphics.drawable.Drawable calendar = getDrawable(R.drawable.ic_calendar).mutate();
        calendar.setTint(colors.accent);
        day.setCompoundDrawablesRelativeWithIntrinsicBounds(calendar, null, null, null);
        day.setCompoundDrawablePadding(dp(6));
        dateRow.addView(day, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton next = iconButton(R.drawable.ic_next, "Sonraki gün",
                () -> changeUsageSelection(null, mode, range, daysBack - 1, 1));
        next.setEnabled(daysBack > 0);
        dateRow.addView(next, touchTarget());
        panel.addView(dateRow);
    }

    private interface Selection { void select(int value); }

    private void addSelector(LinearLayout parent, String[] items, int current, Selection callback) {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setFillViewport(true);
        LinearLayout switches = row();
        switches.setBackground(shape(colors.background, 12, 0));
        for (int i = 0; i < items.length; i++) {
            int index = i;
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2, 1);
            layout.setMargins(dp(2), dp(3), dp(2), dp(3));
            TextView option = chip(items[i], i == current, () -> callback.select(index));
            option.setMinHeight(dp(48));
            switches.addView(option, layout);
        }
        horizontal.addView(switches);
        parent.addView(horizontal);
        if (swipeLayout != null) swipeLayout.keepHorizontalGesturesOn(horizontal);
        View chosen = switches.getChildAt(current);
        horizontal.post(() -> {
            if (chosen.getRight() > horizontal.getWidth()) {
                horizontal.scrollTo(chosen.getRight() - horizontal.getWidth() + dp(4), 0);
            }
        });
    }

    static String summaryTitle(int selectedTab, boolean appDetail, int mode, int daysBack, LocalDate today) {
        if (appDetail) return "Seçilen aralıktaki toplam";
        if (mode != 0) return selectedTab == 2 ? "Son 30 gün · Hotspot" : "Son 30 gün · Mobil veri";
        if (mode == 0 && daysBack > 0) {
            String metric = selectedTab == 2 ? "İnternet paylaşımı" : "Mobil veri";
            return metric + " · " + today.minusDays(daysBack).format(FULL_DATE);
        }
        return selectedTab == 2 ? "Bugünkü internet paylaşımı" : "Bugünkü mobil veri";
    }

    private void showChart(LinearLayout graph, LinearLayout usage, List<Period> periods, long total,
                           int selectedTab, Usage.App selectedApp, ZonedDateTime snapshot) {
        int accent = selectedTab == 2 ? colors.hotspot : colors.accent;
        usage.removeAllViews();
        String totalTitle = summaryTitle(selectedTab, selectedApp != null, mode, daysBack, snapshot.toLocalDate());
        TextView heading = label(totalTitle, 14, colors.secondary, true);
        heading.setAccessibilityHeading(true);
        usage.addView(heading);
        TextView amount = label(Usage.format(total), 44, accent, true);
        amount.setFontFeatureSettings("tnum");
        amount.setPadding(0, 0, 0, dp(8));
        usage.addView(amount);
        selectedBar = availableBar(periods, selectedBar);
        BarChart chart = new BarChart(periods, accent, selectedBar);
        LinearLayout controls = row();
        String chartHint = selectedTab == 2 ? "Wi-Fi, USB ve Bluetooth dahil"
                : selectedTab == 0 ? "Hotspot dahil" : "Grafikten bir aralık seç";
        TextView hint = label(chartHint, 12, colors.secondary, false);
        controls.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton reset = iconButton(R.drawable.ic_reset, "Toplama dön", () -> chart.choose(-1));
        controls.addView(reset, touchTarget());
        ImageButton previous = iconButton(R.drawable.ic_previous, "Önceki grafik aralığı", () -> chart.step(-1));
        ImageButton next = iconButton(R.drawable.ic_next, "Sonraki grafik aralığı", () -> chart.step(1));
        controls.addView(previous, touchTarget());
        controls.addView(next, touchTarget());
        chart.selection = value -> {
            selectedBar = value;
            Period period = value < 0 ? null : periods.get(value);
            heading.setText(period == null ? totalTitle : period.description);
            amount.setText(Usage.format(period == null ? total : period.bytes));
            reset.setVisibility(period == null ? View.GONE : View.VISIBLE);
            hint.setVisibility(period == null ? View.VISIBLE : View.INVISIBLE);
            previous.setEnabled(chart.canStep(-1));
            next.setEnabled(chart.canStep(1));
            if (period != null && graph.isAttachedToWindow()) {
                usage.announceForAccessibility(periodText(period));
            }
        };
        chart.selection.select(selectedBar);
        graph.addView(chart, new LinearLayout.LayoutParams(-1, dp(164)));
        graph.addView(controls);
        graph.addView(label("Kısa aralıklar yaklaşık olabilir.", 12, colors.secondary, false));
    }

    private void showApps(List<Usage.App> apps, int selectedRange) {
        LinearLayout list = card();
        if (selectedRange < 2) paragraph(list, "Kısa aralıktaki uygulama değerleri yaklaşık olabilir.");
        if (apps.isEmpty()) {
            paragraph(list, "Bu aralıkta kullanıcı uygulaması trafiği bulunamadı.");
            return;
        }
        list.addView(label("Sistem uygulamaları gizli", 12, colors.secondary, false));
        addAppRows(list, apps, apps.size(), result -> {
            changeUsageSelection(result, mode, range, daysBack, 1);
        });
    }

    private void addAppRows(LinearLayout list, List<Usage.App> apps, int limit, Consumer<Usage.App> choose) {
        double total = 0;
        for (Usage.App app : apps) total += app.bytes;
        list.addView(label("Toplam uygulama tüketimindeki pay", 12, colors.secondary, false));
        for (Usage.App result : apps.subList(0, Math.min(limit, apps.size()))) {
            LinearLayout entry = column();
            entry.setPadding(dp(8), dp(11), dp(8), dp(11));
            entry.setBackground(interactive(colors.surface, 12, 0));
            if (choose != null) {
                entry.setOnClickListener(view -> choose.accept(result));
                entry.setFocusable(true);
                addPressFeedback(entry);
            }
            LinearLayout top = row();
            LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(dp(34), dp(34));
            badgeLayout.setMarginEnd(dp(10));
            top.addView(appIcon(result), badgeLayout);
            TextView name = label(result.name, 15, colors.text, true);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(0, 0, dp(8), 0);
            top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout values = column();
            values.setGravity(Gravity.END);
            values.addView(label(Usage.format(result.bytes), 14, colors.text, true));
            float fraction = total > 0 ? (float) (result.bytes / total) : 0;
            int percent = Math.round(100 * fraction);
            String share = fraction > 0 && fraction < 0.01f ? "<%1" : "%" + percent;
            values.addView(label(share, 12, colors.secondary, false));
            top.addView(values);
            if (choose != null) {
                LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(dp(16), dp(16));
                arrow.setMarginStart(dp(6));
                top.addView(icon(R.drawable.ic_next, colors.secondary), arrow);
            }
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
            bar.setWeightSum(1f);
            bar.addView(mark, new LinearLayout.LayoutParams(0, -1, fraction));
            list.addView(entry);
        }
    }

    private static final class SessionSnapshot {
        final int revision;
        final SessionMeter.State state;
        final List<Usage.App> apps;

        SessionSnapshot(int revision, SessionMeter.State state, List<Usage.App> apps) {
            this.revision = revision;
            this.state = state;
            this.apps = apps;
        }
    }

    private SessionSnapshot readSession(long wanted, int version) {
        try (SessionMeter meter = new SessionMeter(this)) {
            SessionMeter.State state = meter.read(wanted);
            return new SessionSnapshot(version, state, Usage.rankedUserApps(this, state.appAmounts()));
        }
    }

    private void preloadLatestSession() {
        int version = SessionService.revision();
        if (preloadingSession || (latestSession != null && latestSession.revision == version)) return;
        preloadingSession = true;
        int generation = usageGeneration;
        queries.execute(() -> {
            SessionSnapshot result = null;
            try {
                result = readSession(0, version);
            } catch (RuntimeException failure) {
                android.util.Log.w("DataWarner", "Sayaç önceden okunamadı", failure);
            }
            SessionSnapshot snapshot = result;
            runOnUiThread(() -> {
                preloadingSession = false;
                if (isDestroyed() || generation != usageGeneration) return;
                if (snapshot != null) latestSession = snapshot;
                if (visible && pendingTab == SESSION_TAB) {
                    if (latestSession != null) selectTab(SESSION_TAB, true);
                    else {
                        pendingTab = -1;
                        tab = SESSION_TAB;
                        render(1, true);
                    }
                } else prepareTabPreviews();
            });
        });
    }

    private void openSessionHistory() {
        int direction = historyId > 0 ? -1 : 1;
        int token = revision;
        int navigation = ++historyNavigation;
        loadSessionHistory(entries -> {
            if (token != revision || navigation != historyNavigation || !visible) return;
            historyEntries = entries;
            historyId = 0;
            historyOpen = true;
            sessionMetric = SessionMeter.TOTAL;
            render(direction, false);
        }, this::showHistoryReadError);
    }

    private void closeSessionHistory() {
        historyOpen = false;
        render(-1, false);
    }

    private void openHistoryEntry(long id) {
        int token = revision;
        int navigation = ++historyNavigation;
        queries.execute(() -> {
            try {
                SessionSnapshot snapshot = readSession(id, 0);
                runOnUiThread(() -> {
                    if (token != revision || navigation != historyNavigation || !visible || isDestroyed()) return;
                    if (snapshot.state.id == 0) {
                        showHistoryReadError();
                        return;
                    }
                    historySnapshot = snapshot;
                    historyOpen = false;
                    historyId = id;
                    sessionMetric = SessionMeter.TOTAL;
                    render(1, false);
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (token == revision && navigation == historyNavigation) showHistoryReadError();
                });
            }
        });
    }

    private void renderSessionHistory(int token) {
        LinearLayout heading = row();
        heading.addView(iconButton(R.drawable.ic_back, "Sayaca dön", this::closeSessionHistory), touchTarget());
        TextView title = label("Sayaç geçmişi", 21, colors.text, true);
        title.setAccessibilityHeading(true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(heading);
        LinearLayout list = card();
        if (historyEntries != null) {
            showHistoryEntries(heading, list, historyEntries);
            return;
        }
        paragraph(list, "Geçmiş yükleniyor…");
        loadSessionHistory(entries -> {
            if (token != revision || isDestroyed()) return;
            historyEntries = entries;
            showHistoryEntries(heading, list, entries);
        }, () -> {
            if (token != revision || isDestroyed()) return;
            list.removeAllViews();
            paragraph(list, "Sayaç geçmişi okunamadı.");
        });
    }

    private void loadSessionHistory(Consumer<List<SessionMeter.Entry>> ready, Runnable failed) {
        queries.execute(() -> {
            try (SessionMeter meter = new SessionMeter(this)) {
                List<SessionMeter.Entry> entries = meter.history();
                runOnUiThread(() -> ready.accept(entries));
            } catch (RuntimeException error) {
                runOnUiThread(failed);
            }
        });
    }

    private void showHistoryEntries(LinearLayout header, LinearLayout list, List<SessionMeter.Entry> entries) {
        list.removeAllViews();
        if (entries.isEmpty()) {
            paragraph(list, "Henüz kayıtlı ölçüm yok.");
            return;
        }
        header.addView(iconButton(R.drawable.ic_trash, "Tüm geçmişi sil", () -> confirmDeleteHistory(0)), touchTarget());
        ZoneId zone = ZoneId.systemDefault();
        for (SessionMeter.Entry entry : entries) {
            String time = Instant.ofEpochMilli(entry.started).atZone(zone).format(SESSION_TIME);
            LinearLayout item = row();
            item.addView(settingsLink(entry.title.isEmpty() ? "Veri ölçümü" : entry.title, time, 0,
                    () -> openHistoryEntry(entry.id)), new LinearLayout.LayoutParams(0, -2, 1));
            item.addView(iconButton(R.drawable.ic_trash, time + " ölçümünü sil",
                    () -> confirmDeleteHistory(entry.id)), touchTarget());
            list.addView(item);
        }
    }

    private void showHistoryReadError() {
        if (!isDestroyed() && visible) android.widget.Toast.makeText(this,
                "Sayaç geçmişi okunamadı.", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteHistory(long id) {
        new AlertDialog.Builder(this).setTitle(id == 0 ? "Tüm geçmiş silinsin mi?" : "Ölçüm silinsin mi?")
                .setMessage("Silinen ölçümler geri alınamaz.")
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Sil", (dialog, button) -> updateSessionStorage(meter -> meter.deleteHistory(id)))
                .show();
    }

    private void updateSessionStorage(Consumer<SessionMeter> change) {
        queries.execute(() -> {
            try (SessionMeter meter = new SessionMeter(this)) {
                change.accept(meter);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    latestSession = null;
                    historyEntries = null;
                    historySnapshot = null;
                    if (!visible || tab != SESSION_TAB || settingsPage != SettingsPage.NONE) return;
                    if (!historyOpen) {
                        render();
                        return;
                    }
                    int token = revision;
                    loadSessionHistory(entries -> {
                        if (token != revision || isDestroyed()) return;
                        historyEntries = entries;
                        render();
                    }, this::showHistoryReadError);
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) android.widget.Toast.makeText(this, "Sayaç kaydı güncellenemedi.",
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private final class SessionPanel {
        private final int token;
        private LinearLayout results;
        private TextView clock;
        private TextView amount;
        private TextView pace;
        private TextView status;
        private TextView toggle;
        private ImageButton reset;
        private SessionMeter.State state;
        private List<Usage.App> apps = new ArrayList<>();
        private int seenRevision = Integer.MIN_VALUE;
        private boolean loading;
        private boolean unavailable;

        SessionPanel(int token) {
            this.token = token;
        }

        void show() {
            show(true);
        }

        void show(boolean foreground) {
            SessionSnapshot prepared = historyId == 0 ? latestSession : historySnapshot;
            if (prepared != null && (historyId == 0 || prepared.state.id == historyId)) {
                state = prepared.state;
                apps = prepared.apps;
                seenRevision = prepared.revision;
            }
            LinearLayout banner = card();
            banner.addView(label("Veri sayacı", 18, colors.text, true));
            clock = label("00:00", 38, colors.text, true);
            clock.setFontFeatureSettings("tnum");
            banner.addView(clock);
            amount = label("", 26, colors.accent, true);
            amount.setFontFeatureSettings("tnum");
            amount.setVisibility(View.GONE);
            banner.addView(amount);
            pace = label("", 14, colors.secondary, false);
            pace.setVisibility(View.GONE);
            banner.addView(pace);
            status = label("Sayaç verileri yükleniyor…", 13, colors.secondary, false);
            banner.addView(status);
            LinearLayout actions = row();
            actions.setPadding(0, dp(12), 0, 0);
            toggle = chip("Başlat", true, () -> {
                if (SessionService.phase() == SessionService.IDLE) sessionMetric = SessionMeter.TOTAL;
                SessionService.toggle(Dashboard.this);
                updateControls();
            });
            toggle.setMinHeight(dp(48));
            actions.addView(toggle, new LinearLayout.LayoutParams(0, -2, 1));
            boolean historical = historyId > 0;
            toggle.setVisibility(historical ? View.GONE : View.VISIBLE);
            actions.addView(iconButton(historical ? R.drawable.ic_back : R.drawable.ic_history,
                    historical ? "Geçmişe dön" : "Oturum geçmişi", Dashboard.this::openSessionHistory), touchTarget());
            reset = iconButton(R.drawable.ic_reset, "Sayacı sıfırla", this::resetCounter);
            reset.setVisibility(View.GONE);
            if (!historical) actions.addView(reset, touchTarget());
            banner.addView(actions);
            results = column();
            content.addView(results);
            if (state != null) {
                updateControls();
                showResults();
            }
            if (foreground) tick();
        }

        private void tick() {
            if (token != revision || !visible || isFinishing()) return;
            int current = historyId > 0 ? 0 : SessionService.revision();
            if (!loading && current != seenRevision) {
                loading = true;
                long wanted = historyId;
                request = queries.submit(() -> {
                    try {
                        SessionSnapshot latest = readSession(wanted, current);
                        runOnUiThread(() -> {
                            if (token != revision || isDestroyed()) return;
                            loading = false;
                            seenRevision = current;
                            state = latest.state;
                            apps = latest.apps;
                            if (wanted == 0) latestSession = latest;
                            else historySnapshot = latest;
                            if (sessionMetric != SessionMeter.TOTAL && sessionMetric != Usage.HOTSPOT
                                    && apps.stream().noneMatch(item -> item.uid == sessionMetric)) {
                                sessionMetric = SessionMeter.TOTAL;
                            }
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
            reset.setVisibility(historyId == 0 && (hasPoints || running) ? View.VISIBLE : View.GONE);
            reset.setEnabled(available);
            toggle.setEnabled(available);
            toggle.setAlpha(available ? 1f : 0.62f);
            toggle.setText(running ? "Bitir" : hasPoints ? "Yeni ölçüm başlat" : "Başlat");
            long time = hasPoints ? state.duration() : 0;
            if (hasPoints && state.storedActive && running) {
                time = SystemClock.elapsedRealtime() - state.points.get(0).elapsed;
            }
            clock.setText(SessionMeter.duration(time));
            amount.setVisibility(hasPoints ? View.VISIBLE : View.GONE);
            if (hasPoints) amount.setText(Usage.format(state.amount(SessionMeter.TOTAL)));
            long hourly = hasPoints ? state.hourlyEstimate() : -1;
            pace.setVisibility(hourly >= 0 ? View.VISIBLE : View.GONE);
            if (hourly >= 0) pace.setText("Bu hızla yaklaşık " + Usage.format(hourly) + "/saat");
            String error = SessionService.error();
            if (error != null) status.setText(error);
            else if (unavailable) status.setText("Sayaç kaydı okunamadı. Yenile’ye dokun.");
            else if (state == null) status.setText("Sayaç verileri hazırlanıyor…");
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
            LinearLayout heading = row();
            heading.addView(label(state.title.isEmpty() ? "Oturum ayrıntıları" : state.title, 17, colors.text, true),
                    new LinearLayout.LayoutParams(0, -2, 1));
            heading.addView(iconButton(R.drawable.ic_edit, "Oturumu adlandır",
                    () -> nameSession(state.id, state.title)), touchTarget());
            totals.addView(heading);
            totals.addView(label("Hotspot · " + Usage.format(state.amount(Usage.HOTSPOT)), 16, colors.hotspot, true));
            ZoneId zone = ZoneId.systemDefault();
            String start = Instant.ofEpochMilli(state.points.get(0).wall).atZone(zone).format(SESSION_TIME);
            String end = Instant.ofEpochMilli(state.last().wall).atZone(zone).format(SESSION_TIME);
            paragraph(totals, start + " – " + end);

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
            else addAppRows(breakdown, apps, apps.size(), item -> {
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

        private void resetCounter() {
            boolean running = SessionService.phase() == SessionService.RUNNING;
            long id = state == null ? 0 : state.id;
            new AlertDialog.Builder(Dashboard.this).setTitle("Sayaç sıfırlansın mı?")
                    .setMessage(running ? "Ölçüm durdurulup geçmişe kaydedilecek." : "Sayaç sıfırlanacak. Ölçüm geçmişte kalacak.")
                    .setNegativeButton("Vazgeç", null).setPositiveButton("Sıfırla", (dialog, button) -> {
                        if (SessionService.phase() == SessionService.RUNNING) {
                            latestSession = null;
                            SessionService.resetRunning(Dashboard.this);
                        } else if (SessionService.phase() == SessionService.IDLE && id > 0) {
                            updateSessionStorage(meter -> meter.resetDisplay(id));
                        }
                    }).show();
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
                                    latestSession = null;
                                    historyEntries = null;
                                    historySnapshot = null;
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
            android.graphics.drawable.Drawable.ConstantState state = app.icon.getConstantState();
            image.setImageDrawable(state == null ? app.icon : state.newDrawable(getResources()).mutate());
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
                    paint.setColor(i == chosen ? accent : period.bytes == 0 ? colors.border : withAlpha(accent, 170));
                    canvas.drawRoundRect(center - halfBar, bottom - bar, center + halfBar, bottom, dp(3), dp(3), paint);
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
        addPressFeedback(action);
        action.setEnabled(enabled);
        action.setFocusable(enabled);
        action.setOnClickListener(view -> callback.run());
        return action;
    }

    private LinearLayout.LayoutParams touchTarget() {
        return new LinearLayout.LayoutParams(dp(48), dp(48));
    }

    private ImageView icon(int resource, int tint) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(resource);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private ImageButton iconButton(int resource, String description, Runnable callback) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(resource);
        button.setImageTintList(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{withAlpha(colors.secondary, 95), colors.accent}));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(interactive(0, 12, 0));
        addPressFeedback(button);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setFocusable(true);
        button.setOnClickListener(view -> callback.run());
        return button;
    }

    private TextView chip(String label, boolean selected, Runnable callback) {
        TextView tab = label(label, 13, selected ? colors.onAccent : colors.secondary, true);
        tab.setPadding(dp(7), 0, dp(7), 0);
        tab.setGravity(Gravity.CENTER);
        tab.setBackground(interactive(selected ? colors.accent : 0, 10, 0));
        addPressFeedback(tab);
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

    private void addPressFeedback(View view) {
        view.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.press_feedback));
    }

    private static int withAlpha(int color, int alpha) {
        return color & 0x00ffffff | alpha << 24;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
