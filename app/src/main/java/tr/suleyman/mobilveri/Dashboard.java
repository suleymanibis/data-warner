package tr.suleyman.mobilveri;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

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
    private static final int[] TAB_NAMES = {R.string.overview, R.string.apps, R.string.hotspot, R.string.meter};
    private static final int[] TAB_ICONS = {R.drawable.ic_overview, R.drawable.ic_apps, R.drawable.ic_hotspot, R.drawable.ic_timer};
    private static final int[] MODES = {R.string.hourly, R.string.daily, R.string.weekly};
    private static final int[] RANGES = {R.string.one_hour, R.string.three_hours, R.string.one_day, R.string.three_days, R.string.one_week, R.string.one_month};
    private static final long[] RANGE_HOURS = {1, 3, 24, 72, 168, 720};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault());
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("d MMMM, EEEE", java.util.Locale.getDefault());
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.getDefault());
    static final DateTimeFormatter SESSION_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm:ss", java.util.Locale.getDefault());

    final ExecutorService queries = Executors.newSingleThreadExecutor();
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
    SessionSnapshot latestSession;
    private boolean preloadingSession;
    private final PathInterpolator easeOut = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private final PathInterpolator easeIn = new PathInterpolator(0.4f, 0f, 1f, 1f);
    Future<?> request;
    private Appearance colors;
    DashboardUi ui;
    private DashboardSettings settings;
    private FrameLayout root;
    private FrameLayout drawer;
    private View settingsButton;
    private boolean drawerOpen;
    private LinearLayout main;
    private FrameLayout pageFrame;
    private ScrollView pageScroll;
    private String renderedPage;
    private int restoredScroll;
    private boolean backRegistered;
    private Object backAction;
    LinearLayout content;
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
    int revision;
    boolean visible;
    int sessionMetric = SessionMeter.TOTAL;
    long historyId;
    private boolean historyOpen;
    List<SessionMeter.Entry> historyEntries;
    SessionSnapshot historySnapshot;
    private int historyNavigation;
    private long alertEnd;
    private long alertDuration;

    private enum SettingsPage {
        NONE(0, 0),
        NOTIFICATIONS(R.string.notifications_settings, R.drawable.ic_notification),
        APPEARANCE(R.string.appearance_settings, R.drawable.ic_appearance),
        PLAN(R.string.data_plan_settings, R.drawable.ic_plan);

        final int title;
        final int icon;

        SettingsPage(int title, int icon) {
            this.title = title;
            this.icon = icon;
        }
    }

    @Override protected void onCreate(Bundle saved) {
        colors = new Appearance(Appearance.useLight(Appearance.mode(this), getResources().getConfiguration().uiMode));
        ui = new DashboardUi(this, colors);
        settings = new DashboardSettings(this, ui);
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
        if (backRegistered && Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) backAction);
        }
        revision++;
        queries.shutdownNow();
        usageQueries.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (hasBackDestination()) navigateBack();
        else super.onBackPressed();
    }

    private boolean hasBackDestination() {
        return drawerOpen || settingsPage != SettingsPage.NONE || app != null || tab != 0;
    }

    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33 || hasBackDestination() == backRegistered) return;
        backRegistered = hasBackDestination();
        if (backRegistered) {
            if (backAction == null) backAction = (OnBackInvokedCallback) this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) backAction);
        } else {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) backAction);
        }
    }

    private void navigateBack() {
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
        }
    }

    void render() {
        render(0, false);
    }

    void render(int direction, boolean fullWidth) {
        FrameLayout previousPage = pageFrame;
        if (request != null) request.cancel(true);
        int token = ++revision;
        awaitedPage = null;
        pendingTabPage = null;
        pendingTab = -1;
        clearPendingSelection();
        tabPreviews.clear();
        String page = settingsPage.name() + "/" + tab + "/" + historyOpen + "/" + historyId
                + "/" + (app == null ? "" : app.uid) + "/" + mode + "/" + range + "/" + daysBack;
        int oldScroll = pageScroll == null ? 0 : pageScroll.getScrollY();
        restoredScroll = page.equals(renderedPage) ? oldScroll : 0;
        renderedPage = page;
        updateBackCallback();
        if (root == null) {
            root = new FrameLayout(this);
            root.setBackgroundColor(colors.background);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            });
            setContentView(root);
            root.requestApplyInsets();
        } else {
            root.removeAllViews();
        }
        drawer = null;
        swipeLayout = null;
        main = ui.column();
        main.setBackgroundColor(colors.background);
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));
        if (settingsPage != SettingsPage.NONE) {
            renderSettingsPage();
            PageMotion.animate(previousPage, pageFrame, direction, fullWidth, ui.dp(28), easeOut);
            return;
        }

        LinearLayout header = ui.row();
        header.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4));
        LinearLayout menu = ui.row();
        menu.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        menu.addView(ui.icon(R.drawable.ic_menu, colors.text), new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        ImageView brand = new ImageView(this);
        brand.setImageResource(R.mipmap.ic_launcher);
        brand.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logo = new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30));
        logo.setMarginStart(ui.dp(4));
        menu.addView(brand, logo);
        menu.setContentDescription(getString(R.string.open_drawer));
        menu.setBackground(ui.interactive(0, 12, 0));
        ui.addPressFeedback(menu);
        menu.setOnClickListener(view -> openDrawer());
        menu.setFocusable(true);
        settingsButton = menu;
        header.addView(menu, new LinearLayout.LayoutParams(-2, ui.dp(48)));
        TextView identity = ui.label(getString(R.string.app_name), 21, colors.text, true);
        identity.setPadding(ui.dp(8), 0, ui.dp(4), 0);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(ui.iconButton(R.drawable.ic_refresh, getString(R.string.refresh), this::refresh), ui.touchTarget());
        main.addView(header);

        ScrollView scroll = new UsageScrollView(this, colors.accent);
        restoreScroll(scroll);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = ui.column();
        content.setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(20));
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
        PageMotion.animate(previousPage, pageFrame, direction, fullWidth, ui.dp(28), easeOut);
        if (drawerOpen) openDrawer();
        if (!usageAllowed) {
            LinearLayout permission = card();
            permission.addView(ui.label(getString(R.string.usage_required), 23, colors.text, true));
            ui.paragraph(permission, getString(R.string.usage_help));
            permission.addView(ui.chip(getString(R.string.usage_open), true, this::openUsageSettings),
                    new LinearLayout.LayoutParams(-1, ui.dp(48)));
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
            new SessionPanel(this, token).show();
        }
        prepareTabPreviews();
    }

    private void mountPage(View page) {
        pageFrame = new FrameLayout(this);
        pageFrame.addView(page, new FrameLayout.LayoutParams(-1, -1));
        main.addView(pageFrame, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void restoreScroll(ScrollView scroll) {
        pageScroll = scroll;
        final int position = restoredScroll;
        if (position > 0) scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                                                int oldL, int oldT, int oldR, int oldB) {
                scroll.removeOnLayoutChangeListener(this);
                scroll.scrollTo(0, position);
            }
        });
    }

    private void drawUsageTab(int target, Usage.App selectedApp, ZonedDateTime snapshot, boolean foreground) {
        String key = usageKey(target, selectedApp, snapshot);
        UsagePage page = usagePages.get(key);
        if (foreground) loadUsagePage(target, selectedApp, snapshot);
        if (page == null) {
            if (foreground) {
                awaitedPage = key;
            }
            showPageMessage(getString(R.string.usage_prepare));
            return;
        }
        if (page.error) {
            showPageMessage(getString(R.string.usage_error_refresh));
            return;
        }
        LocalDate today = snapshot.toLocalDate();
        ZoneId zone = snapshot.getZone();
        if (target == 1) {
            LinearLayout titleRow = ui.row();
            if (selectedApp != null) {
                titleRow.addView(ui.iconButton(R.drawable.ic_back, getString(R.string.apps_back),
                        () -> changeUsageSelection(null, mode, range, daysBack, -1)), ui.touchTarget());
            }
            TextView heading = ui.label(selectedApp == null ? getString(R.string.apps_usage) : selectedApp.name, 22, colors.text, true);
            heading.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8));
            titleRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            content.addView(titleRow);
        }
        LinearLayout chartPanel = target == 1 && selectedApp == null ? null : card();
        LinearLayout chartSummary = chartPanel == null ? null : ui.column();
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
                notifications.addView(ui.label(getString(R.string.notifications_disabled), 15, colors.warning, true));
                notifications.addView(ui.action(getString(R.string.enable_notifications), settings::enableNotifications));
            }
        }
    }

    private void showPageMessage(String message) {
        ui.paragraph(card(), message);
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
        LinearLayout previewContent = ui.column();
        previewContent.setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(20));
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
            if (target == SESSION_TAB) new SessionPanel(this, revision).show(false);
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
                android.util.Log.w("MobilVeri", getString(R.string.usage_error), error);
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
                android.util.Log.w("DataWarner", getString(R.string.plan_error), error);
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
        TextView details = planHeading(panel, plan, getString(R.string.plan_loading));
        if (plan.capacity == 0) return;
        queries.submit(() -> {
            try {
                DataPlan.Snapshot value = plan.read(this, now);
                runOnUiThread(() -> {
                    if (token == revision && !isDestroyed()) showPlanSnapshot(panel, details, value);
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (token == revision) details.setText(getString(R.string.plan_error_now));
                });
            }
        });
    }

    private void addPreparedPlanCard(DataPlan.Snapshot snapshot) {
        LinearLayout panel = card();
        TextView details = planHeading(panel, DataPlan.load(this), getString(R.string.plan_error_now));
        if (snapshot != null) showPlanSnapshot(panel, details, snapshot);
    }

    private TextView planHeading(LinearLayout panel, DataPlan plan, String status) {
        LinearLayout heading = ui.row();
        heading.addView(ui.label(getString(R.string.plan_title), 17, colors.text, true), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(ui.iconButton(R.drawable.ic_edit, plan.capacity == 0 ? getString(R.string.plan_setup) : getString(R.string.plan_edit),
                settings::editPlan), ui.touchTarget());
        panel.addView(heading);
        TextView details = ui.label(plan.capacity == 0 ? getString(R.string.plan_input_help) : status,
                14, colors.secondary, false);
        panel.addView(details);
        return details;
    }

    private void showPlanSnapshot(LinearLayout panel, TextView details, DataPlan.Snapshot value) {
        details.setText(Usage.format(value.remaining()) + getString(R.string.plan_remaining_suffix));
        details.setTextColor(colors.text);
        details.setTextSize(24);
        details.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(value.level() > 0 ? colors.warning : colors.accent));
        progress.setProgress(value.percent());
        panel.addView(progress);
        ui.paragraph(panel, getString(R.string.plan_details, Usage.format(value.capacity), value.next.format(DATE),
                value.days, Usage.format(value.perDay())));
    }

    void savePlan(DataPlan plan) {
        plan.save(this);
        PlanWidget.requestUpdate(this);
        refresh();
    }

    private void showAlert(int token) {
        LinearLayout card = card();
        card.addView(ui.label(getString(R.string.alert_summary), 23, colors.text, true));
        long end = alertEnd;
        long start = end - alertDuration;
        ZoneId zone = ZoneId.systemDefault();
        ui.paragraph(card, Instant.ofEpochMilli(start).atZone(zone).format(SESSION_TIME) + " – "
                + Instant.ofEpochMilli(end).atZone(zone).format(SESSION_TIME));
        TextView detail = ui.label(getString(R.string.android_reading), 16, colors.text, true);
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
                    detail.setText(getString(R.string.android_record) + Usage.format(total));
                    BarChart chart = new BarChart(this, colors, periods, colors.accent, -1);
                    chart.selection = index -> {
                        if (index >= 0) detail.setText(periodText(periods.get(index)));
                    };
                    card.addView(chart, new LinearLayout.LayoutParams(-1, ui.dp(186)));
                    ui.paragraph(card, getString(R.string.android_records_delayed));
                    LinearLayout breakdown = card();
                    breakdown.addView(ui.label(getString(R.string.apps_top), 16, colors.text, true));
                    if (apps.isEmpty()) ui.paragraph(breakdown, getString(R.string.apps_empty));
                    else addAppRows(breakdown, apps, 5, null);
                });
            } catch (RuntimeException error) {
                runOnUiThread(() -> {
                    if (token == revision) detail.setText(getString(R.string.range_error));
                });
            }
        });
    }

    private void addNavigation() {
        LinearLayout nav = ui.row();
        nav.setBackgroundColor(colors.surface);
        nav.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        nav.setElevation(ui.dp(3));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int target = i;
            boolean selected = tab == i;
            int color = selected ? colors.accent : colors.secondary;
            LinearLayout view = ui.column();
            view.setGravity(Gravity.CENTER);
            view.setMinimumHeight(ui.dp(56));
            view.setBackground(ui.interactive(0, 12, 0));
            ui.addPressFeedback(view);
            ImageView image = ui.icon(TAB_ICONS[i], color);
            image.setPadding(ui.dp(11), ui.dp(4), ui.dp(11), ui.dp(4));
            if (selected) image.setBackground(ui.shape(colors.iconBackground, 16, 0));
            view.addView(image, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(30)));
            TextView caption = ui.label(getString(TAB_NAMES[i]), 11, color, selected);
            caption.setGravity(Gravity.CENTER);
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.addView(caption, new LinearLayout.LayoutParams(-1, -2));
            view.setContentDescription(getString(TAB_NAMES[i]));
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
        updateBackCallback();
        drawer = new FrameLayout(this);
        drawer.setAccessibilityPaneTitle(getString(R.string.settings));
        View scrim = new View(this);
        scrim.setBackgroundColor(0x99090d1a);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.setOnClickListener(view -> closeDrawer());
        drawer.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        ScrollView panel = new ScrollView(this);
        panel.setBackgroundColor(colors.surface);
        panel.setElevation(ui.dp(18));
        panel.setFillViewport(true);
        panel.setClickable(true);
        LinearLayout settings = ui.column();
        settings.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(24));
        panel.addView(settings);
        FrameLayout.LayoutParams dimensions = new FrameLayout.LayoutParams(
                Math.min(ui.dp(328), Math.max(ui.dp(216), getResources().getDisplayMetrics().widthPixels - ui.dp(38))),
                -1, Gravity.LEFT);
        drawer.addView(panel, dimensions);

        LinearLayout heading = ui.row();
        TextView title = ui.label(getString(R.string.settings), 25, colors.text, true);
        title.setAccessibilityHeading(true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton close = ui.iconButton(R.drawable.ic_close, getString(R.string.close_drawer), this::closeDrawer);
        heading.addView(close, ui.touchTarget());
        settings.addView(heading);
        for (SettingsPage page : SettingsPage.values()) {
            if (page == SettingsPage.NONE) continue;
            settings.addView(this.settings.settingsLink(getString(page.title), null, page.icon, () -> closeDrawer(() -> showSettingsPage(page))));
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
                        android.util.Log.w("DataWarner", getString(R.string.plan_error), error);
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
        LinearLayout header = ui.row();
        header.setPadding(ui.dp(12), ui.dp(4), ui.dp(16), ui.dp(4));
        header.addView(ui.iconButton(R.drawable.ic_back, getString(R.string.back), () -> showSettingsPage(SettingsPage.NONE)), ui.touchTarget());
        TextView title = ui.label(getString(settingsPage.title), 21, colors.text, true);
        title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        main.addView(header);

        ScrollView scroll = new UsageScrollView(this, colors.accent);
        restoreScroll(scroll);
        scroll.setFillViewport(true);
        content = ui.column();
        content.setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(20));
        scroll.addView(content);
        scroll.setBackgroundColor(colors.background);
        mountPage(scroll);
        switch (settingsPage) {
            case NOTIFICATIONS:
                settings.addNotificationPreferences();
                break;
            case APPEARANCE:
                settings.addAppearancePreferences();
                break;
            case PLAN:
                if (Usage.allowed(this)) {
                    if (settingsPlanPrepared) addPreparedPlanCard(readySettingsPlan);
                    else addPlanCard(revision, ZonedDateTime.now());
                } else {
                    LinearLayout plan = card();
                    ui.paragraph(plan, getString(R.string.plan_usage_required));
                    plan.addView(settings.settingsLink(getString(R.string.usage_open), null, 0, this::openUsageSettings));
                    plan.addView(settings.settingsLink(getString(R.string.plan_edit), null, 0, settings::editPlan));
                }
                break;
            default:
                break;
        }
    }

    private void closeDrawer() {
        closeDrawer(null);
    }

    private void closeDrawer(Runnable afterClose) {
        if (drawer == null || !drawerOpen) return;
        drawerOpen = false;
        updateBackCallback();
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
        LinearLayout dateRow = ui.row();
        ImageButton previous = ui.iconButton(R.drawable.ic_previous, getString(R.string.previous_day),
                () -> changeUsageSelection(null, mode, range, daysBack + 1, -1));
        previous.setEnabled(daysBack < 29);
        dateRow.addView(previous, ui.touchTarget());
        LocalDate selected = today.minusDays(daysBack);
        String dateLabel = daysBack == 0 ? getString(R.string.today) : selected.format(FULL_DATE);
        TextView day = ui.action(dateLabel, () -> {
            DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                LocalDate date = LocalDate.of(year, month + 1, dayOfMonth);
                int chosen = Math.toIntExact(today.toEpochDay() - date.toEpochDay());
                if (chosen != daysBack) changeUsageSelection(null, mode, range, chosen, Integer.compare(daysBack, chosen));
            }, selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth());
            dialog.getDatePicker().setMaxDate(Usage.startOf(today.plusDays(1), zone) - 1);
            dialog.getDatePicker().setMinDate(Usage.startOf(today.minusDays(29), zone));
            dialog.show();
        }, true, getString(R.string.pick_date) + dateLabel);
        android.graphics.drawable.Drawable calendar = getDrawable(R.drawable.ic_calendar).mutate();
        calendar.setTint(colors.accent);
        day.setCompoundDrawablesRelativeWithIntrinsicBounds(calendar, null, null, null);
        day.setCompoundDrawablePadding(ui.dp(6));
        dateRow.addView(day, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton next = ui.iconButton(R.drawable.ic_next, getString(R.string.next_day),
                () -> changeUsageSelection(null, mode, range, daysBack - 1, 1));
        next.setEnabled(daysBack > 0);
        dateRow.addView(next, ui.touchTarget());
        panel.addView(dateRow);
    }

    interface Selection { void select(int value); }

    void addSelector(LinearLayout parent, int[] items, int current, Selection callback) {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        horizontal.setFillViewport(true);
        LinearLayout switches = ui.row();
        switches.setBackground(ui.shape(colors.background, 12, 0));
        for (int i = 0; i < items.length; i++) {
            int index = i;
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2, 1);
            layout.setMargins(ui.dp(2), ui.dp(3), ui.dp(2), ui.dp(3));
            TextView option = ui.chip(getString(items[i]), i == current, () -> callback.select(index));
            option.setMinHeight(ui.dp(48));
            switches.addView(option, layout);
        }
        horizontal.addView(switches);
        parent.addView(horizontal);
        if (swipeLayout != null) swipeLayout.keepHorizontalGesturesOn(horizontal);
        View chosen = switches.getChildAt(current);
        horizontal.post(() -> {
            if (chosen.getRight() > horizontal.getWidth()) {
                horizontal.scrollTo(chosen.getRight() - horizontal.getWidth() + ui.dp(4), 0);
            }
        });
    }

    static int summaryTitle(int selectedTab, boolean appDetail, int mode, int daysBack) {
        if (appDetail) return R.string.selected_total;
        if (mode != 0) return selectedTab == 2 ? R.string.last_30_hotspot : R.string.last_30_mobile;
        if (daysBack > 0) return selectedTab == 2 ? R.string.hotspot_tethering : R.string.mobile_data;
        return selectedTab == 2 ? R.string.today_hotspot : R.string.today_mobile;
    }

    private void showChart(LinearLayout graph, LinearLayout usage, List<Period> periods, long total,
                           int selectedTab, Usage.App selectedApp, ZonedDateTime snapshot) {
        int accent = selectedTab == 2 ? colors.hotspot : colors.accent;
        usage.removeAllViews();
        String title = getString(summaryTitle(selectedTab, selectedApp != null, mode, daysBack));
        String totalTitle = selectedApp == null && mode == 0 && daysBack > 0
                ? title + " · " + snapshot.toLocalDate().minusDays(daysBack).format(FULL_DATE) : title;
        TextView heading = ui.label(totalTitle, 14, colors.secondary, true);
        heading.setAccessibilityHeading(true);
        usage.addView(heading);
        TextView amount = ui.label(Usage.format(total), 44, accent, true);
        amount.setFontFeatureSettings("tnum");
        amount.setPadding(0, 0, 0, ui.dp(8));
        usage.addView(amount);
        selectedBar = availableBar(periods, selectedBar);
        BarChart chart = new BarChart(this, colors, periods, accent, selectedBar);
        LinearLayout controls = ui.row();
        String chartHint = selectedTab == 2 ? getString(R.string.hotspot_included_types)
                : selectedTab == 0 ? getString(R.string.hotspot_included) : getString(R.string.pick_chart_range);
        TextView hint = ui.label(chartHint, 12, colors.secondary, false);
        controls.addView(hint, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton reset = ui.iconButton(R.drawable.ic_reset, getString(R.string.chart_total), () -> chart.choose(-1));
        controls.addView(reset, ui.touchTarget());
        ImageButton previous = ui.iconButton(R.drawable.ic_previous, getString(R.string.previous_chart_range), () -> chart.step(-1));
        ImageButton next = ui.iconButton(R.drawable.ic_next, getString(R.string.next_chart_range), () -> chart.step(1));
        controls.addView(previous, ui.touchTarget());
        controls.addView(next, ui.touchTarget());
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
        graph.addView(chart, new LinearLayout.LayoutParams(-1, ui.dp(164)));
        graph.addView(controls);
        graph.addView(ui.label(getString(R.string.short_range_estimate), 12, colors.secondary, false));
    }

    private void showApps(List<Usage.App> apps, int selectedRange) {
        LinearLayout list = card();
        if (selectedRange < 2) ui.paragraph(list, getString(R.string.short_app_estimate));
        if (apps.isEmpty()) {
            ui.paragraph(list, getString(R.string.apps_no_traffic));
            return;
        }
        list.addView(ui.label(getString(R.string.apps_system_hidden), 12, colors.secondary, false));
        addAppRows(list, apps, apps.size(), result -> {
            changeUsageSelection(result, mode, range, daysBack, 1);
        });
    }

    void addAppRows(LinearLayout list, List<Usage.App> apps, int limit, Consumer<Usage.App> choose) {
        double total = 0;
        for (Usage.App app : apps) total += app.bytes;
        list.addView(ui.label(getString(R.string.apps_share), 12, colors.secondary, false));
        for (Usage.App result : apps.subList(0, Math.min(limit, apps.size()))) {
            LinearLayout entry = ui.column();
            entry.setPadding(ui.dp(8), ui.dp(11), ui.dp(8), ui.dp(11));
            entry.setBackground(ui.interactive(colors.surface, 12, 0));
            if (choose != null) {
                entry.setOnClickListener(view -> choose.accept(result));
                entry.setFocusable(true);
                ui.addPressFeedback(entry);
            }
            LinearLayout top = ui.row();
            LinearLayout.LayoutParams badgeLayout = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34));
            badgeLayout.setMarginEnd(ui.dp(10));
            top.addView(appIcon(result), badgeLayout);
            TextView name = ui.label(result.name, 15, colors.text, true);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(0, 0, ui.dp(8), 0);
            top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout values = ui.column();
            values.setGravity(Gravity.END);
            values.addView(ui.label(Usage.format(result.bytes), 14, colors.text, true));
            float fraction = total > 0 ? (float) (result.bytes / total) : 0;
            int percent = Math.round(100 * fraction);
            String share = fraction > 0 && fraction < 0.01f ? "<%1" : "%" + percent;
            values.addView(ui.label(share, 12, colors.secondary, false));
            top.addView(values);
            if (choose != null) {
                LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16));
                arrow.setMarginStart(ui.dp(6));
                top.addView(ui.icon(R.drawable.ic_next, colors.secondary), arrow);
            }
            entry.addView(top);
            LinearLayout bar = ui.row();
            bar.setBackground(ui.shape(colors.border, 5, 0));
            bar.setClipToOutline(true);
            LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, ui.dp(4));
            line.setMarginStart(ui.dp(44));
            line.topMargin = ui.dp(8);
            entry.addView(bar, line);
            View mark = new View(this);
            mark.setBackground(ui.shape(colors.accent, 5, 0));
            bar.setWeightSum(1f);
            bar.addView(mark, new LinearLayout.LayoutParams(0, -1, fraction));
            list.addView(entry);
        }
    }

    static final class SessionSnapshot {
        final int revision;
        final SessionMeter.State state;
        final List<Usage.App> apps;

        SessionSnapshot(int revision, SessionMeter.State state, List<Usage.App> apps) {
            this.revision = revision;
            this.state = state;
            this.apps = apps;
        }
    }

    SessionSnapshot readSession(long wanted, int version) {
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

    void openSessionHistory() {
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
        LinearLayout heading = ui.row();
        heading.addView(ui.iconButton(R.drawable.ic_back, getString(R.string.measurement_back_meter), this::closeSessionHistory), ui.touchTarget());
        TextView title = ui.label(getString(R.string.measurement_history), 21, colors.text, true);
        title.setAccessibilityHeading(true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(heading);
        LinearLayout list = card();
        if (historyEntries != null) {
            showHistoryEntries(heading, list, historyEntries);
            return;
        }
        ui.paragraph(list, getString(R.string.measurement_history_loading));
        loadSessionHistory(entries -> {
            if (token != revision || isDestroyed()) return;
            historyEntries = entries;
            showHistoryEntries(heading, list, entries);
        }, () -> {
            if (token != revision || isDestroyed()) return;
            list.removeAllViews();
            ui.paragraph(list, getString(R.string.measurement_history_error));
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
            ui.paragraph(list, getString(R.string.measurement_history_empty));
            return;
        }
        header.addView(ui.iconButton(R.drawable.ic_trash, getString(R.string.measurement_history_delete_all), () -> confirmDeleteHistory(0)), ui.touchTarget());
        ZoneId zone = ZoneId.systemDefault();
        for (SessionMeter.Entry entry : entries) {
            String time = Instant.ofEpochMilli(entry.started).atZone(zone).format(SESSION_TIME);
            LinearLayout item = ui.row();
            item.addView(settings.settingsLink(entry.title.isEmpty() ? getString(R.string.measurement) : entry.title, time, 0,
                    () -> openHistoryEntry(entry.id)), new LinearLayout.LayoutParams(0, -2, 1));
            item.addView(ui.iconButton(R.drawable.ic_trash, getString(R.string.delete_measurement_named, time),
                    () -> confirmDeleteHistory(entry.id)), ui.touchTarget());
            list.addView(item);
        }
    }

    private void showHistoryReadError() {
        if (!isDestroyed() && visible) android.widget.Toast.makeText(this,
                getString(R.string.measurement_history_error), android.widget.Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteHistory(long id) {
        new AlertDialog.Builder(this).setTitle(id == 0 ? getString(R.string.measurement_history_confirm_all) : getString(R.string.measurement_confirm_delete))
                .setMessage(getString(R.string.measurement_delete_help))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.remove), (dialog, button) -> updateSessionStorage(meter -> meter.deleteHistory(id)))
                .show();
    }

    void updateSessionStorage(Consumer<SessionMeter> change) {
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
                    if (!isDestroyed()) android.widget.Toast.makeText(this, getString(R.string.measurement_update_error),
                            android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    static List<Period> sessionPeriods(List<SessionMeter.Segment> segments) {
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
                    : new String(Character.toChars(app.name.codePointAt(0))).toUpperCase(java.util.Locale.getDefault());
            TextView badge = ui.label(initial, 15, colors.accent, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(ui.shape(colors.iconBackground, 10, 0));
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

    static String periodText(Period period) {
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

    LinearLayout card() {
        return ui.newCard(content);
    }

}
