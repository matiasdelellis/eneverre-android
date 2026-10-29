package ar.com.delellis.eneverre.util;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.biometric.BiometricManager;

import ar.com.delellis.eneverre.LockActivity;
import ar.com.delellis.eneverre.SplashActivity;

/**
 * Opt-in app lock: after the app has been in the background for a while (or on
 * a cold start) the content stays behind {@link LockActivity} until the user
 * authenticates with the device's fingerprint/face/PIN.
 *
 * <p>The lock does not replace the server session and never touches the stored
 * tokens: it only gates the UI, so authentication failures cannot log the user
 * out. It is skipped when it cannot protect anything — the feature is off, there
 * is no session, or the device has no screen lock — so it can never trap the
 * user behind a screen it cannot verify.
 *
 * <p>Registered from {@link ar.com.delellis.eneverre.EneverreApp}; all callbacks
 * run on the main thread.
 */
public final class AppLock implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "AppLock";

    /** Time allowed in the background before authenticating again. */
    private static final long BACKGROUND_TIMEOUT_MS = 30_000;

    private static AppLock instance;

    private final Context appContext;

    /** Number of started activities; 0 means the app is in the background. */
    private int startedActivities;
    /** Uptime when the app went to the background, 0 while it is in the foreground. */
    private long backgroundedAt;
    /** Whether the UI is currently gated. */
    private boolean locked;

    /** Opened lazily so the application start does not pay for the keystore. */
    @Nullable
    private SecureStore store;

    private AppLock(Context context) {
        this.appContext = context.getApplicationContext();
        // A cold start with the feature on must gate the content from the start;
        // whether there is a session and a way to authenticate is checked lazily
        // by isLocked(), which can back out instead of trapping the user.
        this.locked = AppPreferences.getInstance(appContext).isAppLockEnabled();
    }

    /** Registers the lifecycle observer. Called once from the Application. */
    public static synchronized void init(Context context) {
        if (instance != null) {
            return;
        }
        instance = new AppLock(context);
        ((Application) context.getApplicationContext())
                .registerActivityLifecycleCallbacks(instance);
    }

    public static synchronized AppLock getInstance() {
        if (instance == null) {
            throw new IllegalStateException(
                    "AppLock is not initialized; call init(context) from the Application");
        }
        return instance;
    }

    /** True when the content must stay behind the lock screen. */
    public synchronized boolean isLocked() {
        if (!locked) {
            return false;
        }
        if (!isProtectionAvailable()) {
            // Never trap the user: no session or no way to authenticate means
            // there is nothing this screen could verify.
            locked = false;
            return false;
        }
        return true;
    }

    /** Marks the session as unlocked (biometric success, or a fresh login). */
    public synchronized void unlock() {
        locked = false;
    }

    /**
     * The authenticators to offer: device biometrics plus the device PIN/pattern
     * when the platform supports that combination, otherwise biometrics alone.
     * Returns 0 when the device has no usable screen lock.
     */
    public int authenticators() {
        BiometricManager manager = BiometricManager.from(appContext);
        int combined = BiometricManager.Authenticators.BIOMETRIC_WEAK
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        try {
            if (manager.canAuthenticate(combined) == BiometricManager.BIOMETRIC_SUCCESS) {
                return combined;
            }
            if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    == BiometricManager.BIOMETRIC_SUCCESS) {
                return BiometricManager.Authenticators.BIOMETRIC_WEAK;
            }
        } catch (IllegalArgumentException e) {
            // Some OEMs reject a combination they do not implement.
            Log.w(TAG, "Biometric authenticators unsupported", e);
        }
        return 0;
    }

    /** Whether the lock is enabled, has a session to protect and can authenticate. */
    private boolean isProtectionAvailable() {
        if (!AppPreferences.getInstance(appContext).isAppLockEnabled()) {
            return false;
        }
        SecureStore secureStore = store();
        return secureStore != null && secureStore.hasCredentials() && authenticators() != 0;
    }

    @Nullable
    private synchronized SecureStore store() {
        if (store == null) {
            try {
                store = SecureStore.getInstance(appContext);
            } catch (IllegalStateException e) {
                Log.w(TAG, "Secure store unavailable; app lock disabled", e);
            }
        }
        return store;
    }

    // --- app foreground/background tracking ---------------------------------

    @Override
    public void onActivityStarted(Activity activity) {
        boolean enteringForeground = startedActivities == 0;
        startedActivities++;
        if (!enteringForeground) {
            return;
        }

        long away = backgroundedAt == 0
                ? 0
                : SystemClock.elapsedRealtime() - backgroundedAt;
        backgroundedAt = 0;
        if (locked || away < BACKGROUND_TIMEOUT_MS || !isProtectionAvailable()) {
            return;
        }

        locked = true;
        if (activity instanceof SplashActivity) {
            // Splash re-checks in onResume (after this callback) and opens the
            // lock itself; launching from here would race its validation.
            return;
        }
        if (activity instanceof LockActivity || activity.isFinishing()) {
            return;
        }
        activity.startActivity(new Intent(activity, LockActivity.class));
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (startedActivities > 0) {
            startedActivities--;
        }
        if (startedActivities == 0) {
            backgroundedAt = SystemClock.elapsedRealtime();
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // With the lock on, do not leave the last camera frame in the
            // recents thumbnail. API 33+ can hide only the snapshot; below it
            // the alternative (FLAG_SECURE) would also block screenshots.
            activity.setRecentsScreenshotEnabled(
                    !AppPreferences.getInstance(appContext).isAppLockEnabled());
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
