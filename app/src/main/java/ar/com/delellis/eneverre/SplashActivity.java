package ar.com.delellis.eneverre;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import java.io.Serializable;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.ApiError;
import ar.com.delellis.eneverre.util.SecureStore;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        SecureStore secureStore = SecureStore.getInstance(this);
        if (!secureStore.hasCredentials()) {
            goToLoginActivicy();
            return;
        }

        try {
            ApiClient.getInstance(
                    secureStore.getConfigHost(),
                    secureStore.getConfigUsername(),
                    secureStore.getConfigPassword()
            );
        } catch (IllegalArgumentException e) {
            // Stored host is unusable: re-authenticate.
            goToLoginActivicy();
            return;
        }

        ApiClient.getApiService().cameras().enqueue(new ApiCallback<List<Camera>>(this) {
            @Override
            public void onSuccess(List<Camera> cameras) {
                if (cameras == null) {
                    goToLoginActivicy();
                    return;
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
        startActivity(intent);
        finish();
    }
    private void goToLoginActivicy() {
        Intent intent = new Intent(SplashActivity.this, LoginActivity.class);
        startActivity(intent);
        finish();
    }
}