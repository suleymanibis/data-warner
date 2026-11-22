package tr.suleyman.mobilveri;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class DashboardUi {
    private final Context context;
    final Appearance colors;

    DashboardUi(Context context, Appearance colors) {
        this.context = context;
        this.colors = colors;
    }

    LinearLayout newCard(LinearLayout parent) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(shape(colors.surface, 20, colors.border));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.setMargins(0, dp(10), 0, dp(3));
        parent.addView(card, layout);
        return card;
    }

    LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    void paragraph(LinearLayout parent, String value) {
        TextView copy = label(value, 14, colors.secondary, false);
        copy.setPadding(0, dp(7), 0, dp(7));
        parent.addView(copy);
    }

    TextView action(String text, Runnable callback) {
        return action(text, callback, true, text);
    }

    TextView action(String text, Runnable callback, boolean enabled, String description) {
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

    LinearLayout.LayoutParams touchTarget() {
        return new LinearLayout.LayoutParams(dp(48), dp(48));
    }

    ImageView icon(int resource, int tint) {
        ImageView icon = new ImageView(context);
        icon.setImageResource(resource);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    ImageButton iconButton(int resource, String description, Runnable callback) {
        ImageButton button = new ImageButton(context);
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

    TextView chip(String label, boolean selected, Runnable callback) {
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

    GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    RippleDrawable interactive(int fill, int radius, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(colors.accent, 52)),
                shape(fill, radius, stroke), shape(colors.text, radius, 0));
    }

    void addPressFeedback(View view) {
        view.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.press_feedback));
    }

    static int withAlpha(int color, int alpha) {
        return color & 0x00ffffff | alpha << 24;
    }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
