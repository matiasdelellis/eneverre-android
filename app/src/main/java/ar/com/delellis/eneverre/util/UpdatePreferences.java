package ar.com.delellis.eneverre.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Tracks the user's update-dialog choices. Today this is just the version
 * the user chose to "Skip" — when a higher version comes along it is shown
 * again implicitly (see {@link #isSkipped(String)}).
 */
public class UpdatePreferences {
    private static final String PREF_NAME = "updates";
    private static final String KEY_SKIPPED_VERSION = "skipped_version";

    private static UpdatePreferences instance;
    private final SharedPreferences prefs;

    private UpdatePreferences(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized UpdatePreferences getInstance(Context context) {
        if (instance == null) {
            instance = new UpdatePreferences(context);
        }
        return instance;
    }

    public String getSkippedVersion() {
        return prefs.getString(KEY_SKIPPED_VERSION, null);
    }

    public void setSkippedVersion(String versionName) {
        prefs.edit().putString(KEY_SKIPPED_VERSION, versionName).apply();
    }

    /**
     * True if the user previously chose to skip {@code versionName}. A later,
     * strictly-higher versionName is never considered skipped.
     */
    public boolean isSkipped(String versionName) {
        String skipped = getSkippedVersion();
        if (skipped == null || versionName == null) {
            return false;
        }
        return compareVersions(versionName, skipped) <= 0;
    }

    /**
     * Numeric version comparison that tolerates a different number of dot-
     * separated components and ignores non-numeric suffixes (e.g. {@code -beta}).
     * Both inputs are compared component-by-component as integers; missing
     * components are treated as 0. Returns negative/zero/positive as
     * {@code v1} is less/equal/greater than {@code v2}.
     */
    static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < parts1.length ? leadingInt(parts1[i]) : 0;
            int n2 = i < parts2.length ? leadingInt(parts2[i]) : 0;
            if (n1 != n2) {
                return Integer.compare(n1, n2);
            }
        }
        return 0;
    }

    private static int leadingInt(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            n = n * 10 + (c - '0');
        }
        return n;
    }
}
