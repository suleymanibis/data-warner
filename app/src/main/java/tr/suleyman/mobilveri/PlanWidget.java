package tr.suleyman.mobilveri;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class PlanWidget extends AppWidgetProvider {
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault());

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        PendingResult result = goAsync();
        Context app = context.getApplicationContext();
        WORK.execute(() -> {
            try {
                refresh(app, false);
            } finally {
                result.finish();
            }
        });
    }

    static void requestUpdate(Context context) {
        Context app = context.getApplicationContext();
        WORK.execute(() -> refresh(app, false));
    }

    static synchronized void refresh(Context context, boolean alerts) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, PlanWidget.class);
        int[] ids = manager.getAppWidgetIds(provider);
        DataPlan plan = DataPlan.load(context);
        if (ids.length == 0 && (!alerts || plan.capacity == 0)) return;
        String today = context.getString(R.string.usage_required);
        String remaining = plan.capacity == 0 ? context.getString(R.string.widget_plan_setup) : context.getString(R.string.plan_error);
        try {
            if (Usage.allowed(context)) {
                ZonedDateTime now = ZonedDateTime.now();
                long end = now.toInstant().toEpochMilli();
                if (ids.length > 0) {
                    today = context.getString(R.string.widget_today_prefix) + Usage.format(Usage.total(context,
                            Usage.startOf(now.toLocalDate(), now.getZone()), end));
                }
                if (plan.capacity > 0) {
                    DataPlan.Snapshot snapshot = plan.read(context, now);
                    remaining = context.getString(R.string.remaining_prefix) + Usage.format(snapshot.remaining()) + " · " + snapshot.next.format(DATE);
                    if (alerts) Monitor.notifyPlan(context, plan, snapshot);
                }
            }
        } catch (RuntimeException error) {
            Log.w("DataWarner", "Widget verileri okunamadı", error);
        }
        if (ids.length == 0) return;
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.plan_widget);
        view.setTextViewText(R.id.widget_today, today);
        view.setTextViewText(R.id.widget_plan, remaining);
        PendingIntent open = PendingIntent.getActivity(context, 5, new Intent(context, Dashboard.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE);
        view.setOnClickPendingIntent(R.id.widget_root, open);
        manager.updateAppWidget(ids, view);
    }
}
