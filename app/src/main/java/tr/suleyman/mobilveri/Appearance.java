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
        background = light ? 0xfff4f6fa : 0xff101521;
        surface = light ? 0xffffffff : 0xff191f2d;
        border = light ? 0xffdfe5ee : 0xff333d50;
        text = light ? 0xff172033 : 0xfff2f5fc;
        secondary = light ? 0xff536175 : 0xffadb8ca;
        accent = light ? 0xff2854bf : 0xffa5bdff;
        hotspot = light ? 0xff106b56 : 0xff6fdfc2;
        warning = light ? 0xff8c4e08 : 0xffffc78c;
        onAccent = light ? 0xffffffff : background;
        iconBackground = light ? 0xffe9efff : 0xff293954;
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
