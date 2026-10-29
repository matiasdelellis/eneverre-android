package ar.com.delellis.eneverre;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.Serializable;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.Status;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.ApiError;
import ar.com.delellis.eneverre.util.AppLock;
import ar.com.delellis.eneverre.util.EventShareLink;
import ar.com.delellis.eneverre.util.Logout;
import ar.com.delellis.eneverre.util.SecureStore;

public class SplashActivity extends AppCompatActivity {

    /** Query param of a device-linking link (<host>/?usercode=XXXXXX). */
    private static final String PARAM_USER_CODE = "usercode";

    /** A shared event link this launch should open, or null for a normal start. */
    private EventShareLink.Parsed pendingLink = null;
    /** A device user code to authorize from a link, or null for a normal start. */
    private String pendingUserCode = null;

    private SecureStore secureStore = null;

    private ProgressBar progress = null;
    private View errorPanel = null;
    private TextView errorTitle = null;
    private TextView errorMessage = null;
    private Button retryButton = null;
    private Button logoutButton = null;

    /** Guards the 403-driven role refresh so it cannot loop on a persistent denial. */
    private boolean roleRefreshAttempted = false;

    /** A lock screen is on its way; do not launch a second one. */
    private boolean unlockInFlight = false;
    /** The stored session validation has started (or will never be needed). */
    private boolean validationStarted = false;

    /** Unlock result: continue validating only when the user actually unlocked. */
    private final ActivityResultLauncher<Intent> unlockLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                unlockInFlight = false;
                if (result.getResultCode() == RESULT_OK) {
                    initApiAndValidate();
                } else {
                    // Closed without unlocking: never fall through to the app.
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        progress = findViewById(R.id.splash_progress);
        errorPanel = findViewById(R.id.splash_error);
        errorTitle = findViewById(R.id.splash_error_title);
        errorMessage = findViewById(R.id.splash_error_message);
        retryButton = findViewById(R.id.splash_retry_button);
        logoutButton = findViewById(R.id.splash_logout_button);
        retryButton.setOnClickListener(v -> validateStoredSession());
        logoutButton.setOnClickListener(v -> Logout.confirm(this));

        Uri data = getIntent().getData();
        pendingLink = data != null ? EventShareLink.parse(data) : null;
        pendingUserCode = data != null && data.isHierarchical()
                ? data.getQueryParameter(PARAM_USER_CODE) : null;

        secureStore = SecureStore.getInstance(this);
        if (!secureStore.hasCredentials()) {
            goToLoginActivicy();
            return;
        }
        // Validation starts in onResume: by then the app-lock lifecycle callback
        // has already decided whether this return from the background (or this
        // cold start) has to go through the lock screen first.
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // The opt-in app lock gates everything: authenticate before touching the
        // API or showing anything from the stored session.
        if (AppLock.getInstance().isLocked()) {
            requestUnlock();
            return;
        }
        initApiAndValidate();
    }

    /** Opens the lock screen; unlocking resumes the stored-session validation. */
    private void requestUnlock() {
        if (unlockInFlight) {
            return;
        }
        unlockInFlight = true;
        unlockLauncher.launch(new Intent(this, LockActivity.class));
    }

    /** Initializes the API client with the stored session and validates it. */
    private void initApiAndValidate() {
        if (validationStarted || isGone()) {
            return;
        }
        validationStarted = true;
        try {
            ApiClient.getInstance(
                    secureStore.getConfigHost(),
                    secureStore.getAccessToken(),
                    secureStore.getRefreshToken(),
                    secureStore.getAccessExpiresAt()
            );
        } catch (IllegalArgumentException e) {
            // Stored host is unusable: re-authenticate.
            goToLoginActivicy();
            return;
        }
        validateStoredSession();
    }

    /**
     * Validates the stored session against the server, showing progress. Also
     * the action behind the error panel's Retry: an unreachable server is
     * transient, so the credentials are kept and the same session is retried.
     */
    private void validateStoredSession() {
        // A fresh attempt gets its own single 403-driven role refresh.
        roleRefreshAttempted = false;
        errorPanel.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);

        // Renew the access token up front if it is about to expire, then validate
        // the session by listing cameras (the authenticator still covers a 401).
        ApiClient.getInstance().refreshSessionIfExpiringSoon(this::validateSession);
    }

    private void validateSession() {
        if (isGone()) {
            return;
        }
        if (secureStore.isMonitor()) {
            validateMonitorSession();
            return;
        }
        ApiClient.getApiService().cameras().enqueue(new ApiCallback<List<Camera>>(this) {
            @Override
            public void onSuccess(List<Camera> cameras) {
                if (isGone()) {
                    return;
                }
                if (cameras == null) {
                    goToLoginActivicy();
                    return;
                }
                if (secureStore.mustChangePassword()) {
                    // A mandatory password change is still outstanding from login;
                    // gate here too so a cold start can't skip it.
                    startActivity(new Intent(SplashActivity.this, ChangePasswordActivity.class));
                    finish();
                    return;
                }
                if (pendingLink != null && !secureStore.canPlayback()) {
                    // A shared event opens the Playback tab, which a viewer (or a
                    // monitor) may not use: drop it instead of failing later.
                    Toast.makeText(SplashActivity.this, R.string.shared_event_unavailable, Toast.LENGTH_LONG).show();
                    pendingLink = null;
                }
                if (pendingLink != null && EventShareLink.launch(SplashActivity.this, cameras, pendingLink)) {
                    finish();
                    return;
                }
                if (pendingLink != null) {
                    // The link points at a camera this account cannot see
                    // (no access, or a different backend).
                    Toast.makeText(SplashActivity.this, R.string.shared_event_unavailable, Toast.LENGTH_LONG).show();
                }
                goToCamerasActivity(cameras);
            }
            @Override
            public void onError(int httpCode, String message) {
                if (isGone()) {
                    return;
                }
                // A 401 never reaches here: ApiCallback clears the dead session
                // and restarts the task on login. Anything else is either a role
                // issue or a server/connectivity problem.
                //
                // A 403 means the role no longer grants the capability (it may
                // have changed server-side while the app was closed), not that
                // the session is dead. Refresh the role once and re-validate,
                // which routes a monitor account to its status screen.
                if (httpCode == 403 && !roleRefreshAttempted) {
                    roleRefreshAttempted = true;
                    ApiClient.getInstance().refreshRole(() -> {
                        if (isGone()) {
                            return;
                        }
                        validateSession();
                    });
                    return;
                }
                // A persistent 403 is not a connectivity problem: signing in again
                // re-reads the role, which is the least surprising thing to offer.
                if (httpCode == 403) {
                    goToLoginActivicy();
                    return;
                }
                // Keep the credentials on a failed validation: the session may
                // still be valid, so the user retries from the error panel.
                showServerUnavailable(httpCode);
            }
        });
    }

    /**
     * A monitor account cannot list cameras ({@code /api/cameras} answers 403),
     * so the session is validated with {@code /api/status} instead and the app
     * opens the status-only screen.
     */
    private void validateMonitorSession() {
        ApiClient.getApiService().status().enqueue(new ApiCallback<Status>(this) {
            @Override
            public void onSuccess(Status status) {
                if (isGone()) {
                    return;
                }
                if (secureStore.mustChangePassword()) {
                    startActivity(new Intent(SplashActivity.this, ChangePasswordActivity.class));
                } else {
                    startActivity(new Intent(SplashActivity.this, MonitorActivity.class));
                }
                finish();
            }

            @Override
            public void onError(int httpCode, String message) {
                if (isGone()) {
                    return;
                }
                // A 401 never reaches here: ApiCallback handles it centrally.
                // A 403 on /api/status means the cached role is stale (no longer a
                // monitor, or no longer allowed to read it): re-read it once and
                // re-dispatch to the screen that role actually belongs on.
                if (httpCode == 403 && !roleRefreshAttempted) {
                    roleRefreshAttempted = true;
                    ApiClient.getInstance().refreshRole(() -> {
                        if (isGone()) {
                            return;
                        }
                        validateSession();
                    });
                    return;
                }
                showServerUnavailable(httpCode);
            }
        });
    }

    /**
     * The session could not be validated because of the server (unreachable host,
     * timeout, or an error response) rather than because the credentials are bad.
     * Stay on the splash with the session intact and offer a retry, instead of
     * dropping to the login screen as if it had expired.
     */
    private void showServerUnavailable(int httpCode) {
        progress.setVisibility(View.GONE);
        errorPanel.setVisibility(View.VISIBLE);
        boolean unreachable = httpCode == ApiError.NO_HTTP_CODE || httpCode >= 500;
        errorTitle.setText(unreachable
                ? R.string.splash_server_unreachable
                : R.string.splash_server_error);
        errorMessage.setText(getString(
                unreachable
                        ? R.string.splash_server_unreachable_message
                        : R.string.splash_server_error_message,
                secureStore.getConfigHost()));
    }

    /** True once this screen is on its way out: late responses are ignored. */
    private boolean isGone() {
        return isFinishing() || isDestroyed();
    }

    private void goToCamerasActivity(List<Camera> cameras) {
        Intent intent = new Intent(SplashActivity.this, CamerasActivity.class);
        intent.putExtra(CamerasActivity.RAW_CAMERAS_LIST_DATA, (Serializable) cameras);
        // A device-linking link is only honoured for an already logged-in user;
        // pass it through so the camera list asks to confirm the authorization.
        if (pendingUserCode != null) {
            intent.putExtra(CamerasActivity.EXTRA_PENDING_USER_CODE, pendingUserCode);
        }
        startActivity(intent);
        finish();
    }
    private void goToLoginActivicy() {
        // A shared event link is only honoured for an already logged-in user; if
        // we end up here it is dropped (the user just lands on login).
        Intent intent = new Intent(SplashActivity.this, LoginActivity.class);
        startActivity(intent);
        finish();
    }
}
