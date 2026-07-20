package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.LoginRequest;
import ar.com.delellis.eneverre.api.model.LoginResponse;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.ApiError;
import ar.com.delellis.eneverre.util.SecureStore;

public class LoginActivity extends AppCompatActivity {
    private static final String TAG = "LoginActivity";

    private EditText hostText = null;
    private EditText usernameText = null;
    private EditText passwordText = null;
    private ProgressBar progressBar = null;
    private Button logingButton = null;

    SecureStore secureStore = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // With targetSdk 35 the app draws edge-to-edge; pad the scroll content by
        // the on-screen keyboard (IME) or the navigation bar, whichever is taller,
        // so the login button stays reachable when the keyboard is open.
        View loginScroll = findViewById(R.id.login_scroll);
        ViewCompat.setOnApplyWindowInsetsListener(loginScroll, (v, insets) -> {
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), Math.max(ime, bars));
            return insets;
        });

        secureStore = SecureStore.getInstance(this);

        hostText = (EditText) findViewById(R.id.editServer);
        if (!BuildConfig.API_HOST.isEmpty()) {
            hostText.setText(BuildConfig.API_HOST);
            hostText.setVisibility(GONE);
        } else {
            hostText.setText(secureStore.getConfigHost());
        }

        usernameText = (EditText) findViewById(R.id.editUsername);
        passwordText = (EditText) findViewById(R.id.editPassword);
        passwordText.setOnTouchListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_UP) {
                return false;
            }
            Drawable end = passwordText.getCompoundDrawablesRelative()[2];
            if (end == null) {
                return false;
            }
            int touchSlop = (int) (24 * v.getResources().getDisplayMetrics().density);
            if (event.getRawX() < passwordText.getRight() - passwordText.getPaddingEnd() - touchSlop) {
                return false;
            }
            togglePasswordVisibility();
            return true;
        });

        progressBar = (ProgressBar) findViewById(R.id.loginProgressBar);
        progressBar.setVisibility(GONE);

        logingButton = (Button) findViewById(R.id.buttonLogin);
        logingButton.setOnClickListener(view -> {
            String host, username, password;

            host = hostText.getText().toString();
            username = usernameText.getText().toString();
            password = passwordText.getText().toString();

            if (!validate_credentials(host, username, password))
                return;

            performLogin(host, username, password);
        });
    }
    private boolean validate_credentials(String host, String username, String password) {
        if (host.isEmpty()) {
            hostText.setError(getString(R.string.required_configuration));
            return false;
        }
        if (username.isEmpty()) {
            usernameText.setError(getString(R.string.required_configuration));
            return false;
        }
        if (password.isEmpty()) {
            passwordText.setError(getString(R.string.required_configuration));
            return false;
        }

        try {
            new URL(host);
        } catch (MalformedURLException e) {
            hostText.setError(e.getLocalizedMessage());
            return false;
        }

        return true;
    }

    private void performLogin(String host, String username, String password) {
        progressBar.setVisibility(VISIBLE);
        logingButton.setEnabled(false);

        ApiClient.getInstance(host, null, null, 0L);

        LoginRequest request = new LoginRequest(username, password, deviceName());
        ApiClient.getApiService().login(request).enqueue(new ApiCallback<LoginResponse>(this) {
            @Override
            public void onSuccess(LoginResponse response) {
                if (response == null) {
                    onError(ApiError.NO_HTTP_CODE, getString(R.string.error_server));
                    return;
                }
                Log.i(TAG, "Valid login: saving session");
                secureStore.setConfigHost(host);
                ApiClient.getInstance().setTokens(
                        response.getToken(), response.getRefreshToken(), response.getExpiresAt());

                if (response.isMustChangePassword()) {
                    // The server mandates a password change before the app is usable.
                    // Persist it so a later cold start (which skips login) still gates.
                    secureStore.setMustChangePassword(true);
                    startActivity(new Intent(LoginActivity.this, ChangePasswordActivity.class));
                    finish();
                    return;
                }

                loadCamerasAndContinue();
            }

            @Override
            public void onError(int httpCode, String message) {
                resetForm(message);
            }
        });
    }

    /** With a valid session in hand, fetch the camera list and open the camera view. */
    private void loadCamerasAndContinue() {
        ApiClient.getApiService().cameras().enqueue(new ApiCallback<List<Camera>>(this) {
            @Override
            public void onSuccess(List<Camera> cameras) {
                Log.i(TAG, "Go to cameras view");
                Intent intent = new Intent(LoginActivity.this, CamerasActivity.class);
                intent.putExtra(CamerasActivity.RAW_CAMERAS_LIST_DATA, (Serializable) cameras);
                startActivity(intent);
                finish();
            }

            @Override
            public void onError(int httpCode, String message) {
                resetForm(message);
            }
        });
    }

    private void resetForm(String message) {
        Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
        progressBar.setVisibility(GONE);
        logingButton.setEnabled(true);
    }

    private void togglePasswordVisibility() {
        boolean showing = passwordText.getTransformationMethod() instanceof PasswordTransformationMethod;
        int endDrawable = showing ? R.drawable.ic_visibility_off_24 : R.drawable.ic_visibility_24;
        passwordText.setTransformationMethod(showing
                ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        passwordText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_lock_24, 0, endDrawable, 0);
        passwordText.setSelection(passwordText.getText().length());
    }

    /**
     * A friendly device label (e.g. {@code "Samsung Galaxy S21"}) sent on login so
     * the session shows up named in the backend's session manager.
     */
    private static String deviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        // Many devices already prefix the model with the manufacturer ("Pixel 8"
        // vs "samsung SM-G991B"); avoid doubling it when so.
        if (!model.isEmpty() && model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return capitalize(model);
        }
        String name = (capitalize(manufacturer) + " " + model).trim();
        return name.isEmpty() ? "Android" : name;
    }

    private static String capitalize(String s) {
        if (TextUtils.isEmpty(s)) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}