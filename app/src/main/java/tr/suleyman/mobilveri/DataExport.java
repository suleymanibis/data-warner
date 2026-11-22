package tr.suleyman.mobilveri;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DataExport {
    private static final int CSV = 701;
    private static final int JSON = 702;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final String[] FIELDS = {"type", "title", "start_ms", "end_ms", "mobile_bytes", "hotspot_bytes"};

    private DataExport() { }

    static void chooseDestination(Activity activity, boolean json) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(json ? "application/json" : "text/csv")
                .putExtra(Intent.EXTRA_TITLE, "Data-Warner-" + LocalDate.now() + (json ? ".json" : ".csv"));
        try {
            activity.startActivityForResult(intent, json ? JSON : CSV);
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(activity, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }

    static void save(Context context, int request, Uri destination) {
        if (request != JSON && request != CSV) return;
        Context app = context.getApplicationContext();
        WORK.execute(() -> {
            int message = R.string.export_saved;
            try {
                List<Record> records = collect(app);
                byte[] data = (request == JSON ? json(records) : csv(records)).getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = openDestination(app.getContentResolver(), destination)) {
                    output.write(data);
                }
            } catch (IOException | JSONException | RuntimeException failure) {
                Log.w("DataWarner", "Could not export data", failure);
                message = R.string.export_failed;
            }
            final int result = message;
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, result, Toast.LENGTH_LONG).show());
        });
    }

    private static OutputStream openDestination(ContentResolver resolver, Uri destination) throws IOException {
        try {
            OutputStream output = resolver.openOutputStream(destination, "wt");
            if (output != null) return output;
        } catch (FileNotFoundException | IllegalArgumentException | UnsupportedOperationException unsupported) {
            // Some document providers only accept the basic write mode.
        }
        OutputStream output = resolver.openOutputStream(destination, "w");
        if (output == null) throw new IOException("Export provider returned no output stream");
        return output;
    }

    private static List<Record> collect(Context app) {
        List<Record> records = new ArrayList<>();
        if (Usage.allowed(app)) {
            ZonedDateTime now = ZonedDateTime.now();
            long end = now.toInstant().toEpochMilli();
            addSummary(app, records, "today", Usage.startOf(now.toLocalDate(), now.getZone()), end);
            addSummary(app, records, "last_30_days", Usage.startOf(now.toLocalDate().minusDays(29), now.getZone()), end);
        }
        try (SessionMeter meter = new SessionMeter(app)) {
            for (SessionMeter.Entry entry : meter.history()) {
                SessionMeter.State state = meter.read(entry.id);
                if (!state.points.isEmpty()) {
                    records.add(new Record("session", state.title, state.points.get(0).wall, state.last().wall,
                            state.amount(SessionMeter.TOTAL), state.amount(Usage.HOTSPOT)));
                }
            }
        }
        return records;
    }

    private static void addSummary(Context app, List<Record> records, String type, long start, long end) {
        records.add(new Record(type, "", start, end, Usage.total(app, start, end),
                Usage.apps(app, start, end).getOrDefault(Usage.HOTSPOT, -1L)));
    }

    static String json(List<Record> records) throws JSONException {
        JSONArray rows = new JSONArray();
        for (Record record : records) {
            JSONObject row = new JSONObject();
            for (int field = 0; field < FIELDS.length; field++) {
                row.put(FIELDS[field], record.values[field]);
            }
            rows.put(row);
        }
        return rows.toString(2) + "\n";
    }

    static String csv(List<Record> records) {
        StringBuilder csv = new StringBuilder(String.join(",", FIELDS)).append("\r\n");
        for (Record record : records) {
            String delimiter = "";
            for (Object value : record.values) {
                csv.append(delimiter);
                if (value instanceof String) {
                    String text = (String) value;
                    if (text.matches("(?s)^[\\s]*[=+@-].*")) text = "'" + text;
                    csv.append('"').append(text.replace("\"", "\"\"")).append('"');
                } else {
                    csv.append(value);
                }
                delimiter = ",";
            }
            csv.append("\r\n");
        }
        return csv.toString();
    }

    static final class Record {
        final Object[] values;

        Record(String type, String title, long start, long end, long mobile, long hotspot) {
            values = new Object[]{type, title, start, end, mobile, hotspot};
        }
    }
}
