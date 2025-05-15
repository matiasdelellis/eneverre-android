package ar.com.delellis.eneverre;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import java.io.Serializable;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.util.SecureStore;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class SplashActivity extends AppCompatActivity {
    private static final String TAG = "SplashActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        SecureStore secureStore = SecureStore.getInstance(this);
        if (!secureStore.hasCredentials()) {
            goToLoginActivicy();
        }

        ApiClient apiClient = ApiClient.getInstance(
                secureStore.getConfigHost(),
                secureStore.getConfigUsername(),
                secureStore.getConfigPassword()
        );

        Call<List<Camera>> camerasCall = ApiClient.getApiService().cameras(apiClient.getAuthorization());
        camerasCall.enqueue(new Callback<List<Camera>>() {
            @Override
            public void onResponse(Call<List<Camera>> call, Response<List<Camera>> response) {
                List<Camera> cameras = response.body();
                goToCamerasActivity(cameras);
            }
            @Override
            public void onFailure(Call<List<Camera>> call, Throwable throwable) {
                // TODO: Show dialog with message
                Log.e(TAG, throwable.toString());
                goToLoginActivicy();
            }
        });
    }

    private void goToCamerasActivity(List<Camera> cameras) {
        Intent intent = new Intent(SplashActivity.this, CamerasActivity.class);
        intent.putExtra(CamerasActivity.CAMERAS_LIST_DATA, (Serializable) cameras);
        startActivity(intent);
        finish();
    }
    private void goToLoginActivicy() {
        Intent intent = new Intent(SplashActivity.this, LoginActivity.class);
        startActivity(intent);
        finish();
    }
}