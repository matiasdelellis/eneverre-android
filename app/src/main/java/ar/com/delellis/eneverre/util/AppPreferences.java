package ar.com.delellis.eneverre.util;

import android.content.Context;
import android.content.SharedPreferences;

public class AppPreferences {
    private static final String PREF_NAME = "eneverre_app_prefs";

    private static AppPreferences instance;
    private final SharedPreferences prefs;

    private AppPreferences(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized AppPreferences getInstance(Context context) {
        if (instance == null) {
            instance = new AppPreferences(context);
        }
        return instance;
    }

    private static final String KEY_GLOBAL_MUTE = "global_mute";

    public void setGlobalMute(boolean muted) {
        prefs.edit().putBoolean(KEY_GLOBAL_MUTE, muted).apply();
    }

    public boolean isGlobalMute() {
        return prefs.getBoolean(KEY_GLOBAL_MUTE, false);
    }

    /** Mosaic view mode: one screen-filling grid, or the scrolling grid (default). */
    private static final String KEY_MOSAIC_FIT_TO_SCREEN = "mosaic_fit_to_screen";

    public void setMosaicFitToScreen(boolean fitToScreen) {
        prefs.edit().putBoolean(KEY_MOSAIC_FIT_TO_SCREEN, fitToScreen).apply();
    }

    public boolean isMosaicFitToScreen() {
        return prefs.getBoolean(KEY_MOSAIC_FIT_TO_SCREEN, false);
    }
}