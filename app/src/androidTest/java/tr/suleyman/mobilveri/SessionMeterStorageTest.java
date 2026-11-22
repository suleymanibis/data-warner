package tr.suleyman.mobilveri;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class SessionMeterStorageTest {
    private static final String TEST_DATABASE = "sessions-instrumentation.db";
    private static final String TEST_DISPLAY = "session_display_instrumentation";
    private Context context;

    @Before public void resetStorage() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        clearTestStorage();
    }

    @After public void clearTestStorage() {
        context.deleteDatabase(TEST_DATABASE);
        context.getSharedPreferences(TEST_DISPLAY, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void migratesExistingMeasurementFromVersionOne() {
        try (SQLiteDatabase legacy = context.openOrCreateDatabase(TEST_DATABASE, Context.MODE_PRIVATE, null)) {
            legacy.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY CHECK(id = 1), active INTEGER NOT NULL)");
            legacy.execSQL("CREATE TABLE point (elapsed INTEGER PRIMARY KEY, wall INTEGER NOT NULL,"
                    + " mobile INTEGER NOT NULL, apps TEXT)");
            legacy.execSQL("INSERT INTO session VALUES(1, 0)");
            legacy.execSQL("INSERT INTO point VALUES(1000, 51000, 50, NULL), (2000, 52000, 90, NULL)");
            legacy.setVersion(1);
        }
        try (SessionMeter meter = new SessionMeter(context, TEST_DATABASE, TEST_DISPLAY)) {
            assertEquals(2, meter.getReadableDatabase().getVersion());
            SessionMeter.State session = meter.read(1);
            assertEquals(40, session.amount(SessionMeter.TOTAL));
            assertEquals(1000, session.duration());
            assertFalse(session.interrupted);
            assertEquals(1, meter.history().size());
        }
    }

    @Test public void savesRenamesResetsAndDeletesSessions() {
        try (SessionMeter meter = new SessionMeter(context, TEST_DATABASE, TEST_DISPLAY)) {
            long saved = meter.begin(point(0, 100));
            meter.record(saved, point(1500, 450));
            meter.end(saved, point(1000, 350));
            meter.rename(saved, "Streaming");
            assertEquals("Streaming", meter.read(saved).title);
            assertEquals(250, meter.read(saved).amount(SessionMeter.TOTAL));
            assertEquals(2, meter.read(saved).points.size());

            meter.resetDisplay(saved);
            assertTrue(meter.read().points.isEmpty());
            assertEquals(1, meter.history().size());

            long live = meter.begin(point(2000, 400));
            meter.deleteHistory(saved);
            assertTrue(meter.read(saved).points.isEmpty());
            assertTrue(meter.read(live).storedActive);

            meter.markInterrupted();
            assertTrue(meter.read(live).interrupted);
            assertEquals(1, meter.history().size());
            meter.deleteHistory(0);
            assertTrue(meter.history().isEmpty());
            assertTrue(meter.read(live).points.isEmpty());
        }
    }

    private SessionMeter.Point point(long elapsed, long mobile) {
        return new SessionMeter.Point(elapsed, 50000 + elapsed, mobile, null);
    }
}
