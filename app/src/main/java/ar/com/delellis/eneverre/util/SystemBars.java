package ar.com.delellis.eneverre.util;

import android.app.Activity;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Full-screen chrome for the video screens: hides the status and navigation
 * bars while the app shows a full-screen grid or player, and restores them when
 * the rest of the UI (toolbar, tabs) comes back.
 */
public final class SystemBars {
    private SystemBars() {
    }

    /**
     * Hides or shows the system bars. Hidden bars are <b>transient</b>: a swipe
     * from the edge brings them back and they auto-hide again, so the system
     * gestures (back, home, notification shade) stay reachable and the user is
     * never trapped.
     */
    public static void setHidden(Activity activity, boolean hidden) {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                activity.getWindow(), activity.getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (hidden) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }
}
