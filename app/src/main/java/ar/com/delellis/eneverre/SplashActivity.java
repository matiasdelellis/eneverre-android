package ar.com.delellis.eneverre;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.Serializable;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.ApiError;
import ar.com.delellis.eneverre.util.EventShareLink;
import ar.com.delellis.eneverre.util.SecureStore;

public class SplashActivity extends AppCompatActivity {

    /** Query param of a device-linking link (<host>/?usercode=XXXXXX). */
    private static final String PARAM_USER_CODE = "usercode";

    /** A shared event link this launch should open, or null for a normal start. */
    private EventShareLink.Parsed pendingLink = null;
    /** A device user code to authorize from a link, or null for a normal start. */
    private String pendingUserCode = null;

    private SecureStore secureStore = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        Uri data = getIntent().getData();
        pendingLink = data != null ? EventShareLink.parse(data) : null;
        pendingUserCode = data != null && data.isHierarchical()
                ? data.getQueryParameter(PARAM_USER_CODE) : null;

        secureStore = SecureStore.getInstance(this);
        if (!secureStore.hasCredentials()) {
            goToLoginActivicy();
            return;
        }

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

        // Renew the access token up front if it is about to expire, then validate
        // the session by listing cameras (the authenticator still covers a 401).
        ApiClient.getInstance().refreshSessionIfExpiringSoon(this::validateSession);
    }

    private void validateSession() {
        ApiClient.getApiService().cameras().enqueue(new ApiCallback<List<Camera>>(this) {
            @Override
            public void onSuccess(List<Camera> cameras) {
                if (cameras == null) {
                    goToLoginActivicy();
                    return;
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
                // If the server rejected the credentials, discard them so the
                // user re-enters valid ones. Transient errors (no network) keep them.
                if (ApiError.isUnauthorized(httpCode)) {
                    secureStore.clearCredentials();
                }
                goToLoginActivicy();
            }
        });
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