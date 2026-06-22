package ar.com.delellis.eneverre.util;


import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.IOException;
import java.security.GeneralSecurityException;

public class SecureStore {
    private static final String PREFERENCES = "EneverreSecureStore";

    private static final String KEY_CONFIG_HOST = "eneverre_host";
    private static final String KEY_CONFIG_USERNAME = "eneverre_username";
    private static final String KEY_CONFIG_PASSWORD = "eneverre_password";

    private static SecureStore instance;

    private final SharedPreferences prefs;

    private SecureStore(Context context) {
        this.prefs = openEncrypted(context.getApplicationContext());
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

    public static SecureStore getInstance(Context context) {
        if (instance == null) {
            instance = new SecureStore(context);
        }
        return instance;
    }

    public boolean hasCredentials() {
        if (getConfigHost() == null)
            return false;
        if (getConfigUsername() == null)
            return false;
        if (getConfigPassword() == null)
            return false;

        return true;
    }

    /** Removes the stored host/username/password (e.g. on logout or a 401). */
    public void clearCredentials() {
        prefs.edit()
                .remove(KEY_CONFIG_HOST)
                .remove(KEY_CONFIG_USERNAME)
                .remove(KEY_CONFIG_PASSWORD)
                .apply();
    }

    public String getConfigHost() {
        return prefs.getString(KEY_CONFIG_HOST, null);
    }
    public void setConfigHost(String host) {
        prefs.edit().putString(KEY_CONFIG_HOST, host).apply();
    }

    public String getConfigUsername() {
        return prefs.getString(KEY_CONFIG_USERNAME, null);
    }
    public void setConfigUsername(String username) {
        prefs.edit().putString(KEY_CONFIG_USERNAME, username).apply();
    }

    public String getConfigPassword() {
        return prefs.getString(KEY_CONFIG_PASSWORD, null);
    }
    public void setConfigPassword(String password) {
        prefs.edit().putString(KEY_CONFIG_PASSWORD, password).apply();
    }

}
