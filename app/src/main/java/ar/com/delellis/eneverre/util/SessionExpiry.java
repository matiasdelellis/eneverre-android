package ar.com.delellis.eneverre.util;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import ar.com.delellis.eneverre.LoginActivity;
import ar.com.delellis.eneverre.R;

/**
 * Reacts to a {@code 401} while the app is already running: the refresh token
 * expired or the session was revoked (e.g. from "My sessions" on another
 * device), so no request can succeed any more.
 *
 * <p>Rather than leaving every screen to show its own "invalid credentials"
 * toast over data that no longer refreshes, the session is cleared and the task
 * restarts on the login screen. Simultaneous 401s (several requests were in
 * flight) collapse into a single redirect thanks to a short debounce; the
 * session itself is cleared by every call, so a late 401 after a logout is
 * harmless.
 *
 * <p>The debounce also makes the redirect self-healing: if the system refused to
 * start the activity because the app was in the background, the next 401 once
 * it is visible again retries the navigation.
 */
public final class SessionExpiry {
    private static final String TAG = "SessionExpiry";

    /** Ignore repeated redirects for a moment (a burst of in-flight 401s). */
    private static final long REDIRECT_DEBOUNCE_MS = 3000;

    /** Uptime of the last redirect attempt; {@code SystemClock} so it ignores clock changes. */
    private static long lastRedirectAt = -REDIRECT_DEBOUNCE_MS;

    private SessionExpiry() {
    }

    /** Clears a dead session and opens login. Safe to call more than once. */
    public static synchronized void expire() {
        SecureStore store;
        try {
            store = SecureStore.getInstance();
        } catch (IllegalStateException e) {
            // The app never reached the splash, so there is no session to expire.
            return;
        }

        // The tokens are dead either way; drop them even if the redirect below
        // is debounced (another 401 already handled it).
        store.clearCredentials();

        long now = SystemClock.elapsedRealtime();
        if (now - lastRedirectAt < REDIRECT_DEBOUNCE_MS) {
            return;
        }
        lastRedirectAt = now;

        Log.i(TAG, "Session rejected by the server; returning to login");
        Context context = store.getAppContext();
        // clearCredentials keeps the host, so the login screen is pre-filled.
        Intent intent = new Intent(context, LoginActivity.class);
        // The whole task is stale: nothing behind login can talk to the server.
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        Toast.makeText(context, R.string.session_expired, Toast.LENGTH_LONG).show();
    }
}
