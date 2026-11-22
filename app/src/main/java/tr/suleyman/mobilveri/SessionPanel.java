package tr.suleyman.mobilveri;

import android.app.AlertDialog;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import tr.suleyman.mobilveri.Dashboard.Period;
import tr.suleyman.mobilveri.Dashboard.SessionSnapshot;

final class SessionPanel {
    private final Dashboard dashboard;
    private final DashboardUi ui;
    private final Appearance colors;
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

    SessionPanel(Dashboard dashboard, int token) {
        this.dashboard = dashboard;
        this.ui = dashboard.ui;
        this.colors = ui.colors;
        this.token = token;
    }

    void show() {
        show(true);
    }

    void show(boolean foreground) {
        SessionSnapshot prepared = dashboard.historyId == 0 ? dashboard.latestSession : dashboard.historySnapshot;
        if (prepared != null && (dashboard.historyId == 0 || prepared.state.id == dashboard.historyId)) {
            state = prepared.state;
            apps = prepared.apps;
            seenRevision = prepared.revision;
        }
        LinearLayout banner = dashboard.card();
        banner.addView(ui.label(dashboard.getString(R.string.meter_title), 18, colors.text, true));
        clock = ui.label("00:00", 38, colors.text, true);
        clock.setFontFeatureSettings("tnum");
        banner.addView(clock);
        amount = ui.label("", 26, colors.accent, true);
        amount.setFontFeatureSettings("tnum");
        amount.setVisibility(View.GONE);
        banner.addView(amount);
        pace = ui.label("", 14, colors.secondary, false);
        pace.setVisibility(View.GONE);
        banner.addView(pace);
        status = ui.label(dashboard.getString(R.string.measurement_loading), 13, colors.secondary, false);
        banner.addView(status);
        LinearLayout actions = ui.row();
        actions.setPadding(0, ui.dp(12), 0, 0);
        toggle = ui.chip(dashboard.getString(R.string.measurement_start), true, () -> {
            if (SessionService.phase() == SessionService.IDLE) dashboard.sessionMetric = SessionMeter.TOTAL;
            SessionService.toggle(dashboard);
            updateControls();
        });
        toggle.setMinHeight(ui.dp(48));
        actions.addView(toggle, new LinearLayout.LayoutParams(0, -2, 1));
        boolean historical = dashboard.historyId > 0;
        toggle.setVisibility(historical ? View.GONE : View.VISIBLE);
        actions.addView(ui.iconButton(historical ? R.drawable.ic_back : R.drawable.ic_history,
                historical ? dashboard.getString(R.string.measurement_back_history) : dashboard.getString(R.string.measurement_open_history), dashboard::openSessionHistory), ui.touchTarget());
        reset = ui.iconButton(R.drawable.ic_reset, dashboard.getString(R.string.measurement_reset), this::resetCounter);
        reset.setVisibility(View.GONE);
        if (!historical) actions.addView(reset, ui.touchTarget());
        banner.addView(actions);
        results = ui.column();
        dashboard.content.addView(results);
        if (state != null) {
            updateControls();
            showResults();
        }
        if (foreground) tick();
    }

    private void tick() {
        if (token != dashboard.revision || !dashboard.visible || dashboard.isFinishing()) return;
        int current = dashboard.historyId > 0 ? 0 : SessionService.revision();
        if (!loading && current != seenRevision) {
            loading = true;
            long wanted = dashboard.historyId;
            dashboard.request = dashboard.queries.submit(() -> {
                try {
                    SessionSnapshot latest = dashboard.readSession(wanted, current);
                    dashboard.runOnUiThread(() -> {
                        if (token != dashboard.revision || dashboard.isDestroyed()) return;
                        loading = false;
                        seenRevision = current;
                        state = latest.state;
                        apps = latest.apps;
                        if (wanted == 0) dashboard.latestSession = latest;
                        else dashboard.historySnapshot = latest;
                        if (dashboard.sessionMetric != SessionMeter.TOTAL && dashboard.sessionMetric != Usage.HOTSPOT
                                && apps.stream().noneMatch(item -> item.uid == dashboard.sessionMetric)) {
                            dashboard.sessionMetric = SessionMeter.TOTAL;
                        }
                        unavailable = false;
                        updateControls();
                        showResults();
                    });
                } catch (RuntimeException failure) {
                    android.util.Log.w("DataWarner", "Sayaç ekranı okunamadı", failure);
                    dashboard.runOnUiThread(() -> {
                        if (token != dashboard.revision || dashboard.isDestroyed()) return;
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
        boolean running = dashboard.historyId == 0 && phase == SessionService.RUNNING;
        boolean available = phase == SessionService.IDLE || running;
        reset.setVisibility(dashboard.historyId == 0 && (hasPoints || running) ? View.VISIBLE : View.GONE);
        reset.setEnabled(available);
        toggle.setEnabled(available);
        toggle.setAlpha(available ? 1f : 0.62f);
        toggle.setText(running ? dashboard.getString(R.string.measurement_finish) : hasPoints ? dashboard.getString(R.string.measurement_new) : dashboard.getString(R.string.measurement_start));
        long time = hasPoints ? state.duration() : 0;
        if (hasPoints && state.storedActive && running) {
            time = SystemClock.elapsedRealtime() - state.points.get(0).elapsed;
        }
        clock.setText(SessionMeter.duration(time));
        amount.setVisibility(hasPoints ? View.VISIBLE : View.GONE);
        if (hasPoints) amount.setText(Usage.format(state.amount(SessionMeter.TOTAL)));
        long hourly = hasPoints ? state.hourlyEstimate() : -1;
        pace.setVisibility(hourly >= 0 ? View.VISIBLE : View.GONE);
        if (hourly >= 0) pace.setText(dashboard.getString(R.string.hourly_estimate, Usage.format(hourly)));
        String error = SessionService.error();
        if (error != null) status.setText(error);
        else if (unavailable) status.setText(dashboard.getString(R.string.measurement_error_refresh));
        else if (state == null) status.setText(dashboard.getString(R.string.measurement_preparing));
        else if (dashboard.historyId == 0 && phase == SessionService.STARTING) status.setText(dashboard.getString(R.string.measurement_starting));
        else if (dashboard.historyId == 0 && phase == SessionService.STOPPING) status.setText(dashboard.getString(R.string.measurement_saving));
        else if (running) status.setText(dashboard.getString(R.string.measurement_running));
        else if (hasPoints && (state.interrupted || (state.storedActive && phase == SessionService.IDLE))) {
            status.setText(dashboard.getString(R.string.measurement_interrupted));
        } else status.setText(hasPoints ? (dashboard.historyId > 0 ? dashboard.getString(R.string.measurement_history_old) : dashboard.getString(R.string.measurement_latest))
                : dashboard.getString(R.string.measurement_tip));
    }

    private void showResults() {
        results.removeAllViews();
        if (state.points.isEmpty()) return;

        LinearLayout totals = sessionCard();
        LinearLayout heading = ui.row();
        heading.addView(ui.label(state.title.isEmpty() ? dashboard.getString(R.string.measurement_details) : state.title, 17, colors.text, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(ui.iconButton(R.drawable.ic_edit, dashboard.getString(R.string.measurement_rename),
                () -> nameSession(state.id, state.title)), ui.touchTarget());
        totals.addView(heading);
        totals.addView(ui.label(dashboard.getString(R.string.hotspot_prefix) + Usage.format(state.amount(Usage.HOTSPOT)), 16, colors.hotspot, true));
        ZoneId zone = ZoneId.systemDefault();
        String start = Instant.ofEpochMilli(state.points.get(0).wall).atZone(zone).format(Dashboard.sessionTime());
        String end = Instant.ofEpochMilli(state.last().wall).atZone(zone).format(Dashboard.sessionTime());
        ui.paragraph(totals, start + " – " + end);

        LinearLayout graph = sessionCard();
        LinearLayout switches = ui.row();
        addMetric(switches, dashboard.getString(R.string.total), SessionMeter.TOTAL);
        addMetric(switches, dashboard.getString(R.string.hotspot), Usage.HOTSPOT);
        graph.addView(switches);
        String metric = dashboard.sessionMetric == SessionMeter.TOTAL ? dashboard.getString(R.string.total_mobile_data) :
                dashboard.sessionMetric == Usage.HOTSPOT ? dashboard.getString(R.string.hotspot) : dashboard.getString(R.string.selected_app);
        for (Usage.App item : apps) {
            if (item.uid == dashboard.sessionMetric) metric = item.name;
        }
        TextView detail = ui.label(metric + " · " + Usage.format(state.amount(dashboard.sessionMetric)), 16, colors.text, true);
        detail.setPadding(0, ui.dp(9), 0, 0);
        graph.addView(detail);
        List<Period> periods = Dashboard.sessionPeriods(state.series(dashboard.sessionMetric));
        if (periods.isEmpty()) {
            ui.paragraph(graph, dashboard.sessionMetric == Usage.HOTSPOT && state.points.size() > 1
                    ? dashboard.getString(R.string.measurement_hotspot_missing) : dashboard.getString(R.string.measurement_samples_needed));
        } else {
            BarChart chart = new BarChart(dashboard, colors, periods, dashboard.sessionMetric == Usage.HOTSPOT ? colors.hotspot : colors.accent, -1);
            chart.selection = index -> {
                if (index >= 0) detail.setText(Dashboard.periodText(periods.get(index)));
            };
            graph.addView(chart, new LinearLayout.LayoutParams(-1, ui.dp(174)));
        }
        ui.paragraph(graph, dashboard.getString(R.string.measurement_estimate));

        LinearLayout breakdown = sessionCard();
        breakdown.addView(ui.label(dashboard.getString(R.string.apps_breakdown), 18, colors.text, true));
        if (apps.isEmpty()) ui.paragraph(breakdown, dashboard.getString(R.string.apps_unreported));
        else dashboard.addAppRows(breakdown, apps, apps.size(), item -> {
            dashboard.sessionMetric = item.uid;
            showResults();
            results.post(() -> {
                if (results.getChildCount() > 1) {
                    View updated = results.getChildAt(1);
                    updated.requestRectangleOnScreen(new android.graphics.Rect(0, 0, updated.getWidth(), ui.dp(160)), false);
                }
            });
        });
    }

    private void resetCounter() {
        boolean running = SessionService.phase() == SessionService.RUNNING;
        long id = state == null ? 0 : state.id;
        new AlertDialog.Builder(dashboard).setTitle(dashboard.getString(R.string.measurement_reset_confirm))
                .setMessage(running ? dashboard.getString(R.string.measurement_reset_active) : dashboard.getString(R.string.measurement_reset_inactive))
                .setNegativeButton(dashboard.getString(R.string.cancel), null).setPositiveButton(dashboard.getString(R.string.reset), (dialog, button) -> {
                    if (SessionService.phase() == SessionService.RUNNING) {
                        dashboard.latestSession = null;
                        SessionService.resetRunning(dashboard);
                    } else if (SessionService.phase() == SessionService.IDLE && id > 0) {
                        dashboard.updateSessionStorage(meter -> meter.resetDisplay(id));
                    }
                }).show();
    }

    private void nameSession(long id, String name) {
        EditText input = new EditText(dashboard);
        input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        input.setText(name);
        new AlertDialog.Builder(dashboard).setTitle(dashboard.getString(R.string.measurement_name)).setView(input)
                .setNegativeButton(dashboard.getString(R.string.cancel), null).setPositiveButton(dashboard.getString(R.string.save), (d, w) -> {
                    String title = input.getText().toString().trim();
                    dashboard.queries.execute(() -> {
                        try (SessionMeter meter = new SessionMeter(dashboard)) {
                            meter.rename(id, title);
                            dashboard.runOnUiThread(() -> {
                                dashboard.latestSession = null;
                                dashboard.historyEntries = null;
                                dashboard.historySnapshot = null;
                                if (token == dashboard.revision) seenRevision = Integer.MIN_VALUE;
                            });
                        } catch (RuntimeException error) {
                            dashboard.runOnUiThread(() -> {
                                if (token == dashboard.revision) status.setText(dashboard.getString(R.string.measurement_name_error));
                            });
                        }
                    });
                }).show();
    }

    private LinearLayout sessionCard() {
        return ui.newCard(results);
    }

    private void addMetric(LinearLayout view, String text, int uid) {
        TextView choice = ui.chip(text, dashboard.sessionMetric == uid, () -> {
            dashboard.sessionMetric = uid;
            showResults();
        });
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ui.dp(44), 1);
        size.setMargins(ui.dp(2), 0, ui.dp(2), 0);
        view.addView(choice, size);
    }
}
