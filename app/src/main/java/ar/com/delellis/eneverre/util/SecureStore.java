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

    private SharedPreferences prefs;

    private SecureStore(Context context) {
        try {
            String masterKeys = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            this.prefs = EncryptedSharedPreferences
                    .create(PREFERENCES, masterKeys, context, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);

        } catch (GeneralSecurityException | IOException e) {
            this.prefs = null;
        }
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
