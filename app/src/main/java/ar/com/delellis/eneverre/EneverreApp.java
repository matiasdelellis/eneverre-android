package ar.com.delellis.eneverre;

import android.app.Application;

import ar.com.delellis.eneverre.util.AppLock;

/**
 * Owns the app-wide pieces that need a process-wide lifecycle: currently the
 * opt-in app lock, which needs to know when the whole app leaves and returns to
 * the foreground, not just one activity.
 */
public class EneverreApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppLock.init(this);
    }
}
