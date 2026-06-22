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

import androidx.appcompat.app.AppCompatActivity;

import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.util.ApiCallback;
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

        usernameText.setText(secureStore.getConfigUsername());
        passwordText.setText(secureStore.getConfigPassword());

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

        ApiClient.getInstance(host, username, password);

        ApiClient.getApiService().cameras().enqueue(new ApiCallback<List<Camera>>(this) {
            @Override
            public void onSuccess(List<Camera> cameras) {
                Log.i(TAG, "Valid login: Saving credentials");
                secureStore.setConfigHost(host);
                secureStore.setConfigUsername(username);
                secureStore.setConfigPassword(password);

                Log.i(TAG, "Go to cameras view");
                Intent intent = new Intent(LoginActivity.this, CamerasActivity.class);
                intent.putExtra(CamerasActivity.RAW_CAMERAS_LIST_DATA, (Serializable) cameras);
                startActivity(intent);
                finish();
            }

            @Override
            public void onError(int httpCode, String message) {
                Toast.makeText(LoginActivity.this, message, Toast.LENGTH_LONG).show();
                progressBar.setVisibility(GONE);
                logingButton.setEnabled(true);
            }
        });
    }
}