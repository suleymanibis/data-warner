package tr.suleyman.mobilveri;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.util.List;
import tr.suleyman.mobilveri.Dashboard.Period;

final class BarChart extends View {
    private final List<Period> periods;
    private final int accent;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long max;
    private final int lastAvailable;
    Dashboard.Selection selection;
    private final Appearance colors;
    private int chosen;

    BarChart(Context context, Appearance colors, List<Period> periods, int accent, int chosen) {
        super(context);
        this.colors = colors;
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
                paint.setColor(DashboardUi.withAlpha(accent, 37));
                canvas.drawRoundRect(center - width / 2f, top, center + width / 2f, bottom, dp(3), dp(3), paint);
            }
            if (period.bytes >= 0) {
                float bar = Math.max(dp(2), (bottom - top) * period.bytes / (float) Math.max(1, max));
                paint.setColor(i == chosen ? accent : period.bytes == 0 ? colors.border : DashboardUi.withAlpha(accent, 170));
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
                if (Dashboard.availableBar(periods, index) >= 0) {
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
        return chosen < 0 ? lastAvailable >= 0 : Dashboard.availableBar(periods, chosen + offset) >= 0;
    }

    private void describeSelection() {
        setContentDescription(chosen < 0 ? getContext().getString(R.string.chart_usage) : getContext().getString(R.string.chart_usage_prefix) + Dashboard.periodText(periods.get(chosen)));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    void choose(int index) {
        chosen = index;
        describeSelection();
        selection.select(chosen);
        invalidate();
    }
}
