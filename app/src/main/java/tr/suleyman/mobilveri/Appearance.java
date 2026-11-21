package tr.suleyman.mobilveri;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

final class Appearance {
    static final String[] LABELS = {"Sisteme göre", "Koyu mod", "Açık mod"};
    static final int SYSTEM = 0;
    static final int DARK = 1;
    static final int LIGHT = 2;
    private static final String MODE = "theme";

    final boolean light;
    final int background;
    final int surface;
    final int border;
    final int text;
    final int secondary;
    final int accent;
    final int hotspot;
    final int warning;
    final int onAccent;
    final int iconBackground;

    Appearance(boolean light) {
        this.light = light;
        background = light ? 0xffeff2fa : 0xff10121e;
        surface = light ? 0xffffffff : 0xff191d30;
        border = light ? 0xffd1d9eb : 0xff323a57;
        text = light ? 0xff17203c : 0xfff3f4ff;
        secondary = light ? 0xff4f5b75 : 0xffaeb7d1;
        accent = light ? 0xff3854ba : 0xffa6b6ff;
        hotspot = light ? 0xff106958 : 0xff6fe0cb;
        warning = light ? 0xff805308 : 0xfff7cc8b;
        onAccent = light ? 0xffffffff : 0xff10121e;
        iconBackground = light ? 0xffe2e9ff : 0xff273351;
    }

    static int mode(Context context) {
        return prefs(context).getInt(MODE, SYSTEM);
    }

    static void setMode(Context context, int mode) {
        prefs(context).edit().putInt(MODE, mode).apply();
    }

    static boolean useLight(int mode, int uiMode) {
        if (mode == LIGHT) return true;
        if (mode == DARK) return false;
        return (uiMode & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    }
}
