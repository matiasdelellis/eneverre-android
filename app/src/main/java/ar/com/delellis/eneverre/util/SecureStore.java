package ar.com.delellis.eneverre.util;


import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.IOException;
import java.security.GeneralSecurityException;

import ar.com.delellis.eneverre.api.model.Role;

public class SecureStore {
    private static final String PREFERENCES = "EneverreSecureStore";

    private static final String KEY_CONFIG_HOST = "eneverre_host";
    private static final String KEY_ACCESS_TOKEN = "eneverre_access_token";
    private static final String KEY_REFRESH_TOKEN = "eneverre_refresh_token";
    private static final String KEY_ACCESS_EXPIRES_AT = "eneverre_access_expires_at";
    private static final String KEY_MUST_CHANGE_PASSWORD = "eneverre_must_change_password";
    private static final String KEY_IS_ADMIN = "eneverre_is_admin";
    private static final String KEY_ROLE = "eneverre_role";

    private static SecureStore instance;

    /** Application context, exposed so session-wide reactions need no new plumbing. */
    private final Context appContext;
    private final SharedPreferences prefs;

    private SecureStore(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = openEncrypted(this.appContext);
    }

    private static SharedPreferences openEncrypted(Context context) {
        try {
            return create(context);
        } catch (GeneralSecurityException | IOException e) {
            // The encrypted store can become undecryptable (e.g. after a
            // backup/restore or a keystore reset). Drop it and recreate so the
            // app stays usable — the user simply has to log in again.
            context.deleteSharedPreferences(PREFERENCES);
            try {
                return create(context);
            } catch (GeneralSecurityException | IOException e2) {
                throw new IllegalStateException("Unable to initialize secure storage", e2);
            }
        }
    }

    private static SharedPreferences create(Context context) throws GeneralSecurityException, IOException {
        String masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        return EncryptedSharedPreferences
                .create(PREFERENCES, masterKey, context, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    public static synchronized SecureStore getInstance(Context context) {
        if (instance == null) {
            instance = new SecureStore(context);
        }
        return instance;
    }

    /**
     * Returns the already-initialized store (e.g. for {@code ApiClient} to
     * persist rotated tokens off the UI thread).
     *
     * @throws IllegalStateException if {@link #getInstance(Context)} was never called.
     */
    public static synchronized SecureStore getInstance() {
        if (instance == null) {
            throw new IllegalStateException("SecureStore is not initialized; call getInstance(context) first.");
        }
        return instance;
    }

    /** A session exists when we have a host and a refresh token to renew it with. */
    public boolean hasCredentials() {
        return getConfigHost() != null && getRefreshToken() != null;
    }

    /** The application context this store was created with (never an Activity). */
    public Context getAppContext() {
        return appContext;
    }

    /**
     * Removes the stored token pair and everything derived from the account
     * (role, admin flag, mandatory password change).
     *
     * <p>The server address is configuration, not a credential, so it is kept:
     * the login screen pre-fills it and signing out does not force the user to
     * retype the URL. {@link #hasCredentials()} still reports {@code false}
     * because the refresh token is gone. Call this on logout or a dead session.
     */
    public void clearCredentials() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_ACCESS_EXPIRES_AT)
                .remove(KEY_MUST_CHANGE_PASSWORD)
                .remove(KEY_IS_ADMIN)
                .remove(KEY_ROLE)
                .apply();
    }

    /**
     * Whether the logged-in account still owes a mandatory password change
     * ({@code must_change_password} on login). Persisted so the gate survives an
     * app restart — the returning session skips login, so the flag is the only
     * record. Cleared by a successful self password change.
     */
    public boolean mustChangePassword() {
        return prefs.getBoolean(KEY_MUST_CHANGE_PASSWORD, false);
    }
    public void setMustChangePassword(boolean must) {
        prefs.edit().putBoolean(KEY_MUST_CHANGE_PASSWORD, must).apply();
    }

    /**
     * The account's fixed role, or the legacy fallback for a session cached
     * before roles were persisted: the old admin flag means {@code admin},
     * anything else {@code user} (the only two roles the app understood then).
     * Such a session is refreshed on the next start to replace the guess.
     */
    public String getRole() {
        String role = prefs.getString(KEY_ROLE, null);
        if (role != null) {
            return role;
        }
        return prefs.getBoolean(KEY_IS_ADMIN, false) ? Role.ADMIN : Role.USER;
    }

    /** Whether a real role (not the legacy admin/user guess) has been cached. */
    public boolean hasRole() {
        return prefs.getString(KEY_ROLE, null) != null;
    }

    public void setRole(String role) {
        prefs.edit().putString(KEY_ROLE, role).apply();
    }

    /**
     * Whether the logged-in account is an admin. Persisted so the returning
     * session — which skips login — can still gate admin-only UI (e.g. the
     * user-administration screen) without a fresh login.
     */
    public boolean isAdmin() {
        return Role.isAdmin(getRole());
    }

    /** Persists the server's admin flag; the role remains the source of truth. */
    public void setIsAdmin(boolean isAdmin) {
        prefs.edit().putBoolean(KEY_IS_ADMIN, isAdmin).apply();
    }

    /** Live and events: every role except {@code monitor}. */
    public boolean canViewCameras() {
        return Role.canViewCameras(getRole());
    }

    /** Recordings: {@code viewer} and {@code monitor} never get playback. */
    public boolean canPlayback() {
        return Role.canPlayback(getRole());
    }

    /** PTZ, privacy and talk: {@code viewer} and {@code monitor} cannot operate. */
    public boolean canOperate() {
        return Role.canOperate(getRole());
    }

    /** {@code monitor} accounts have no camera access at all. */
    public boolean isMonitor() {
        return Role.isMonitor(getRole());
    }

    public String getConfigHost() {
        return prefs.getString(KEY_CONFIG_HOST, null);
    }
    public void setConfigHost(String host) {
        prefs.edit().putString(KEY_CONFIG_HOST, host).apply();
    }

    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, null);
    }
    public void setAccessToken(String token) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, token).apply();
    }

    public String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, null);
    }
    public void setRefreshToken(String token) {
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply();
    }

    /** Unix expiry (seconds) of the stored access token, or 0 if unknown. */
    public long getAccessExpiresAt() {
        return prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0L);
    }

    /** Persists a freshly issued/rotated token pair and its access expiry in one edit. */
    public void setTokens(String accessToken, String refreshToken, long accessExpiresAt) {
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_ACCESS_EXPIRES_AT, accessExpiresAt)
                .apply();
    }

}
