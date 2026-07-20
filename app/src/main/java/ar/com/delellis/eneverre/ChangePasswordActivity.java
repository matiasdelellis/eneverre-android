package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.io.Serializable;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.ChangePasswordRequest;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.SecureStore;

/**
 * Mandatory password-change screen shown when the server flags the account with
 * {@code must_change_password} on login (see {@code doc/openapi.yaml}). The user
 * cannot reach the camera list until the change succeeds; the pending state is
 * persisted in {@link SecureStore} so the gate survives an app restart.
 */
public class ChangePasswordActivity extends AppCompatActivity {
    private static final String TAG = "ChangePasswordActivity";

    private EditText newPasswordText;
    private EditText confirmPasswordText;
    private ProgressBar progressBar;
    private Button changeButton;

    private SecureStore secureStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Recreated cold (process death): the API client is gone. Bounce through
        // the splash, which re-inits it and routes back here while the flag stands.
        try {
            ApiClient.getInstance();
        } catch (IllegalStateException e) {
            startActivity(new Intent(this, SplashActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_change_password);

        secureStore = SecureStore.getInstance(this);

        newPasswordText = findViewById(R.id.editNewPassword);
        confirmPasswordText = findViewById(R.id.editConfirmPassword);
        progressBar = findViewById(R.id.changePasswordProgressBar);
        progressBar.setVisibility(GONE);

        changeButton = findViewById(R.id.buttonChangePassword);
        changeButton.setOnClickListener(v -> attemptChange());

        // The change is mandatory: the back button can't skip it. Send the app to
        // the background instead of exposing whatever is behind this activity.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        });
    }

    private void attemptChange() {
        String newPassword = newPasswordText.getText().toString();
        String confirm = confirmPasswordText.getText().toString();

        if (newPassword.isEmpty() || confirm.isEmpty()) {
            newPasswordText.setError(getString(R.string.error_password_required));
            return;
        }
        if (!newPassword.equals(confirm)) {
            confirmPasswordText.setError(getString(R.string.error_passwords_mismatch));
            return;
        }

        progressBar.setVisibility(VISIBLE);
        changeButton.setEnabled(false);

        // current_password is not required for a must-change account; send only the new one.
        ChangePasswordRequest request = new ChangePasswordRequest(null, newPassword);
        ApiClient.getApiService().changePassword(request).enqueue(new ApiCallback<Void>(this) {
            @Override
            public void onSuccess(Void body) {
                Log.i(TAG, "Password changed; clearing the mandatory-change flag");
                secureStore.setMustChangePassword(false);
                Toast.makeText(ChangePasswordActivity.this, R.string.password_changed, Toast.LENGTH_SHORT).show();
                loadCamerasAndContinue();
            }

            @Override
            public void onError(int httpCode, String message) {
                progressBar.setVisibility(GONE);
                changeButton.setEnabled(true);
                Toast.makeText(ChangePasswordActivity.this, R.string.error_change_password, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** With the password changed, fetch the camera list and open the camera view. */
    private void loadCamerasAndContinue() {
        ApiClient.getApiService().cameras().enqueue(new ApiCallback<List<Camera>>(this) {
            @Override
            public void onSuccess(List<Camera> cameras) {
                if (cameras == null) {
                    // Empty/absent body: bounce through the splash to re-validate + re-list.
                    startActivity(new Intent(ChangePasswordActivity.this, SplashActivity.class));
                    finish();
                    return;
                }
                Intent intent = new Intent(ChangePasswordActivity.this, CamerasActivity.class);
                intent.putExtra(CamerasActivity.RAW_CAMERAS_LIST_DATA, (Serializable) cameras);
                startActivity(intent);
                finish();
            }

            @Override
            public void onError(int httpCode, String message) {
                // The password is already changed; just get the user to a usable
                // state via the splash (which validates the session and lists cameras).
                startActivity(new Intent(ChangePasswordActivity.this, SplashActivity.class));
                finish();
            }
        });
    }
}
