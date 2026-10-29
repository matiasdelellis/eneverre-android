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

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputLayout;

import java.io.Serializable;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.ChangePasswordRequest;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.SecureStore;

/**
 * Password change, in two modes:
 * <ul>
 *   <li><b>Mandatory</b> (default): shown when the server flags the account with
 *       {@code must_change_password} on login. It asks only for the new password,
 *       cannot be skipped with Back, and opens the camera list when it succeeds.</li>
 *   <li><b>Voluntary</b> ({@link #EXTRA_VOLUNTARY}): opened from Settings. It also
 *       asks for the current password, can be cancelled, and simply returns to
 *       Settings on success.</li>
 * </ul>
 */
public class ChangePasswordActivity extends AppCompatActivity {
    private static final String TAG = "ChangePasswordActivity";

    /** Opens the voluntary mode: ask for the current password and return to Settings. */
    public static final String EXTRA_VOLUNTARY = "VOLUNTARY";

    private boolean voluntary;

    private TextInputLayout currentPasswordLayout;
    private TextInputLayout newPasswordLayout;
    private TextInputLayout confirmPasswordLayout;
    private EditText currentPasswordText;
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

        voluntary = getIntent().getBooleanExtra(EXTRA_VOLUNTARY, false);

        setContentView(R.layout.activity_change_password);

        MaterialToolbar toolbar = findViewById(R.id.change_password_toolbar);
        toolbar.setTitle(voluntary ? R.string.change_password_button : R.string.change_password_title);
        if (voluntary) {
            toolbar.setNavigationOnClickListener(v -> finish());
            findViewById(R.id.change_password_message).setVisibility(GONE);
            findViewById(R.id.current_password_layout).setVisibility(VISIBLE);
        } else {
            // The change is mandatory: Back cannot skip it. Send the app to the
            // background instead of exposing whatever is behind this activity.
            toolbar.setNavigationIcon(null);
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    moveTaskToBack(true);
                }
            });
        }

        secureStore = SecureStore.getInstance(this);

        currentPasswordLayout = findViewById(R.id.current_password_layout);
        newPasswordLayout = findViewById(R.id.new_password_layout);
        confirmPasswordLayout = findViewById(R.id.confirm_password_layout);
        currentPasswordText = findViewById(R.id.editCurrentPassword);
        newPasswordText = findViewById(R.id.editNewPassword);
        confirmPasswordText = findViewById(R.id.editConfirmPassword);
        progressBar = findViewById(R.id.changePasswordProgressBar);
        progressBar.setVisibility(GONE);

        changeButton = findViewById(R.id.buttonChangePassword);
        changeButton.setOnClickListener(v -> attemptChange());
    }

    private void attemptChange() {
        currentPasswordLayout.setError(null);
        newPasswordLayout.setError(null);
        confirmPasswordLayout.setError(null);

        String current = currentPasswordText.getText().toString();
        String newPassword = newPasswordText.getText().toString();
        String confirm = confirmPasswordText.getText().toString();

        if (voluntary && current.isEmpty()) {
            currentPasswordLayout.setError(getString(R.string.error_current_password_required));
            return;
        }
        if (newPassword.isEmpty() || confirm.isEmpty()) {
            newPasswordLayout.setError(getString(R.string.error_password_required));
            return;
        }
        if (!newPassword.equals(confirm)) {
            confirmPasswordLayout.setError(getString(R.string.error_passwords_mismatch));
            return;
        }

        progressBar.setVisibility(VISIBLE);
        changeButton.setEnabled(false);

        // current_password is not required for a must-change account; a voluntary
        // change sends it so the server can verify the session owner.
        ChangePasswordRequest request = new ChangePasswordRequest(
                voluntary ? current : null, newPassword);
        ApiClient.getApiService().changePassword(request).enqueue(new ApiCallback<Void>(this) {
            @Override
            public void onSuccess(Void body) {
                Log.i(TAG, "Password changed; clearing the mandatory-change flag");
                secureStore.setMustChangePassword(false);
                Toast.makeText(ChangePasswordActivity.this, R.string.password_changed, Toast.LENGTH_SHORT).show();
                if (voluntary) {
                    // Back to Settings, which is where the change came from.
                    finish();
                    return;
                }
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

    /** With the mandatory password changed, fetch the camera list and open the camera view. */
    private void loadCamerasAndContinue() {
        if (secureStore.isMonitor()) {
            // A monitor account has no camera list to fetch.
            startActivity(new Intent(ChangePasswordActivity.this, MonitorActivity.class));
            finish();
            return;
        }
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
