package tr.suleyman.mobilveri;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

final class DashboardSettings {
    private final Dashboard activity;
    private final DashboardUi ui;
    private final Appearance colors;

    DashboardSettings(Dashboard activity, DashboardUi ui) {
        this.activity = activity;
        this.ui = ui;
        this.colors = ui.colors;
    }

    void editPlan() {
        DataPlan existing = DataPlan.load(activity);
        LinearLayout inputs = ui.column();
        inputs.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        EditText amount = alertInput(inputs, activity.getString(R.string.plan_size), existing.capacity == 0 ? "" : existing.gigabytes(), true);
        EditText day = alertInput(inputs, activity.getString(R.string.plan_renewal_day), Integer.toString(existing.renewalDay), false);
        EditText offset = alertInput(inputs, activity.getString(R.string.plan_offset), existing.offsetGigabytes(LocalDate.now()), true);
        offset.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        ui.paragraph(inputs, activity.getString(R.string.plan_offset_help));
        TextView message = ui.label(activity.getString(R.string.plan_rule_help), 13, colors.secondary, false);
        inputs.addView(message);
        ScrollView form = new ScrollView(activity);
        form.addView(inputs);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.plan_tracking)).setView(form)
                .setNegativeButton(activity.getString(R.string.cancel), null).setPositiveButton(activity.getString(R.string.save), null)
                .setNeutralButton(existing.capacity > 0 ? activity.getString(R.string.plan_turn_off) : null,
                        (d, w) -> activity.savePlan(new DataPlan(0, 1))).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                DataPlan plan = DataPlan.parse(amount.getText().toString(), day.getText().toString())
                        .calibrate(offset.getText().toString(), LocalDate.now());
                dialog.dismiss();
                activity.savePlan(plan);
            } catch (IllegalArgumentException invalid) {
                message.setText(activity.getString(R.string.plan_validation_with_offset));
                message.setTextColor(colors.warning);
            }
        }));
        dialog.show();
    }

    void addNotificationPreferences() {
        LinearLayout channels = activity.card();
        boolean enabled = activity.getSystemService(NotificationManager.class).areNotificationsEnabled();
        channels.addView(settingsLink(activity.getString(R.string.all_notifications), enabled ? activity.getString(R.string.enabled) : activity.getString(R.string.disabled), 0, this::enableNotifications));
        addNotificationChannel(channels, activity.getString(R.string.daily_summary), Monitor.SUMMARY_CHANNEL);
        addNotificationChannel(channels, activity.getString(R.string.limit_notification), Monitor.ALERT_CHANNEL);
        addNotificationChannel(channels, activity.getString(R.string.monthly_alert), Monitor.PLAN_CHANNEL);
        SummarySchedule schedule = SummarySchedule.load(activity);
        String frequency = schedule.hours == 24
                ? activity.getString(R.string.summary_at, LocalTime.of(schedule.minuteOfDay / 60, schedule.minuteOfDay % 60)
                    .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
                : activity.getString(R.string.summary_every, schedule.hours);
        channels.addView(settingsLink(activity.getString(R.string.summary_schedule), frequency, 0,
                () -> editSummarySchedule(schedule)));
        if (Build.VERSION.SDK_INT >= 31 && !activity.getSystemService(AlarmManager.class).canScheduleExactAlarms()) {
            channels.addView(settingsLink(activity.getString(R.string.exact_alarm), activity.getString(R.string.exact_alarm_detail), 0,
                    this::openExactAlarmSettings));
        }
        activity.card().addView(settingsLink(activity.getString(R.string.alert_data_limit), AlertRule.load(activity).limit(activity), 0, this::editAlertRule));
        boolean unrestricted = activity.getSystemService(PowerManager.class)
                .isIgnoringBatteryOptimizations(activity.getPackageName());
        LinearLayout battery = activity.card();
        battery.addView(settingsLink(activity.getString(R.string.battery_settings),
                activity.getString(unrestricted ? R.string.battery_unrestricted : R.string.battery_restricted),
                0, () -> openSystemSettings(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))));
        ui.paragraph(battery, activity.getString(R.string.battery_help));
    }

    private void editSummarySchedule(SummarySchedule current) {
        int selected = current.hours == 24 ? 2 : current.hours == 12 ? 1 : 0;
        new AlertDialog.Builder(activity).setTitle(R.string.summary_schedule)
                .setSingleChoiceItems(new String[]{activity.getString(R.string.summary_every, 6),
                        activity.getString(R.string.summary_every, 12), activity.getString(R.string.daily)}, selected,
                        (dialog, choice) -> {
                            dialog.dismiss();
                            if (choice == 2) {
                                new TimePickerDialog(activity, (picker, hour, minute) ->
                                        saveSchedule(new SummarySchedule(24, hour * 60 + minute)),
                                        current.hours == 24 ? current.minuteOfDay / 60 : 18,
                                        current.minuteOfDay % 60,
                                        android.text.format.DateFormat.is24HourFormat(activity)).show();
                            } else {
                                saveSchedule(new SummarySchedule(choice == 0 ? 6 : 12, 0));
                            }
                        })
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void saveSchedule(SummarySchedule schedule) {
        schedule.save(activity);
        activity.render();
    }

    private void openExactAlarmSettings() {
        openSystemSettings(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.fromParts("package", activity.getPackageName(), null)));
    }

    private void openSystemSettings(Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.getPackageName(), null)));
        }
    }

    void addAboutPreferences() {
        String version = "";
        try {
            version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException impossible) {
            android.util.Log.w("DataWarner", "Could not read package version", impossible);
        }
        LinearLayout information = activity.card();
        ui.paragraph(information, activity.getString(R.string.app_version, version));
        information.addView(settingsLink(activity.getString(R.string.source_code), "GitHub", 0,
                () -> openProject()));
        information.addView(settingsLink(activity.getString(R.string.privacy_policy), null, 0,
                () -> new AlertDialog.Builder(activity).setTitle(R.string.privacy_policy)
                        .setMessage(R.string.privacy_body).setPositiveButton(R.string.close, null).show()));
        LinearLayout data = activity.card();
        ui.paragraph(data, activity.getString(R.string.export_description));
        data.addView(settingsLink(activity.getString(R.string.export_data), null, 0, activity::selectExport));
    }

    private void openProject() {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/suleymanibis/data-warner")));
        } catch (ActivityNotFoundException unavailable) {
            new AlertDialog.Builder(activity).setTitle(R.string.source_code)
                    .setMessage("https://github.com/suleymanibis/data-warner")
                    .setPositiveButton(R.string.close, null).show();
        }
    }

    void addAppearancePreferences() {
        LinearLayout theme = activity.card();
        TextView heading = ui.label(activity.getString(R.string.theme), 16, colors.text, true);
        heading.setAccessibilityHeading(true);
        theme.addView(heading);
        RadioGroup modes = new RadioGroup(activity);
        int mode = Appearance.mode(activity);
        int checked = View.NO_ID;
        for (int i = 0; i < Appearance.LABELS.length; i++) {
            RadioButton option = new RadioButton(activity);
            option.setId(View.generateViewId());
            option.setTag(i);
            option.setText(Appearance.LABELS[i]);
            option.setTextSize(15);
            option.setTextColor(colors.text);
            option.setMinHeight(ui.dp(48));
            option.setButtonTintList(ColorStateList.valueOf(colors.accent));
            option.setBackground(ui.interactive(colors.surface, 10, 0));
            modes.addView(option);
            if (mode == i) checked = option.getId();
        }
        modes.check(checked);
        modes.setOnCheckedChangeListener((group, id) -> {
            View option = group.findViewById(id);
            if (option != null && (int) option.getTag() != mode) {
                Appearance.setMode(activity, (int) option.getTag());
                PlanWidget.requestUpdate(activity);
                activity.recreate();
            }
        });
        theme.addView(modes);
    }

    private void editAlertRule() {
        AlertRule rule = AlertRule.load(activity);
        LinearLayout inputs = ui.column();
        inputs.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        EditText amount = alertInput(inputs, activity.getString(R.string.alert_limit_gb), rule.gigabytes(), true);
        EditText duration = alertInput(inputs, activity.getString(R.string.alert_duration_minutes), Integer.toString(rule.minutes), false);
        TextView message = ui.label(activity.getString(R.string.alert_duration_note),
                13, colors.secondary, false);
        message.setPadding(0, ui.dp(12), 0, ui.dp(8));
        inputs.addView(message);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.alert_data_limit))
                .setView(inputs).setNegativeButton(activity.getString(R.string.cancel), null)
                .setNeutralButton(activity.getString(R.string.default_value), null).setPositiveButton(activity.getString(R.string.save), null).create();
        dialog.setOnShowListener(unused -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                amount.setText("3");
                duration.setText(Integer.toString(AlertRule.DEFAULT_MINUTES));
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    AlertRule.parse(amount.getText().toString(), duration.getText().toString()).save(activity);
                    dialog.dismiss();
                    activity.render();
                } catch (IllegalArgumentException invalid) {
                    message.setText(activity.getString(R.string.alert_validation));
                    message.setTextColor(colors.warning);
                }
            });
        });
        dialog.show();
    }

    private EditText alertInput(LinearLayout parent, String title, String value, boolean decimal) {
        TextView caption = ui.label(title, 14, colors.text, true);
        caption.setPadding(0, ui.dp(8), 0, 0);
        parent.addView(caption);
        EditText input = new EditText(activity);
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        input.setText(value);
        parent.addView(input);
        return input;
    }

    View settingsLink(String title, String detail, int iconRes, Runnable callback) {
        LinearLayout link = ui.row();
        link.setMinimumHeight(ui.dp(54));
        link.setPadding(ui.dp(10), ui.dp(8), ui.dp(8), ui.dp(8));
        link.setBackground(ui.interactive(0, 12, 0));
        if (iconRes != 0) {
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22));
            size.setMarginEnd(ui.dp(13));
            link.addView(ui.icon(iconRes, colors.accent), size);
        }
        LinearLayout text = ui.column();
        text.addView(ui.label(title, 15, colors.text, true));
        if (detail != null) text.addView(ui.label(detail, 13, colors.secondary, false));
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        link.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        link.addView(ui.icon(R.drawable.ic_next, colors.secondary), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        link.setContentDescription(detail == null ? title : title + ", " + detail);
        link.setFocusable(true);
        link.setOnClickListener(view -> callback.run());
        ui.addPressFeedback(link);
        return link;
    }

    private void addNotificationChannel(LinearLayout parent, String title, String id) {
        NotificationManager manager = activity.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(id);
        String status = manager.areNotificationsEnabled() && channel != null
                && channel.getImportance() != NotificationManager.IMPORTANCE_NONE ? activity.getString(R.string.enabled) : activity.getString(R.string.disabled);
        parent.addView(settingsLink(title, status, 0, () -> openNotificationSettings(id)));
    }

    private void openNotificationSettings(String channel) {
        Intent settings = new Intent(channel == null ? Settings.ACTION_APP_NOTIFICATION_SETTINGS
                : Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
        if (channel != null) settings.putExtra(Settings.EXTRA_CHANNEL_ID, channel);
        try {
            activity.startActivity(settings);
        } catch (ActivityNotFoundException unavailable) {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", activity.getPackageName(), null)));
        }
    }

    void enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                || !activity.getPreferences(Context.MODE_PRIVATE).getBoolean("asked_notifications", false))) {
            activity.getPreferences(Context.MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply();
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        openNotificationSettings(null);
    }
}
