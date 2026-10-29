package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.Locale;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Status;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.Logout;

/**
 * Home for a {@code monitor} account: the only role with no camera access.
 *
 * <p>{@code monitor} exists for integrations, not for the phone UI: it may read
 * {@code /api/status} and {@code /api/metrics} and nothing else, so
 * {@code /api/cameras} answers {@code 403}. Instead of failing at login, the app
 * lands here and shows what the account can actually see — that the server and
 * its media engine are alive — with a refresh and a way to sign out.
 */
public class MonitorActivity extends AppCompatActivity {

    private LinearProgressIndicator progress;
    private TextView serverValue;
    private TextView uptimeValue;
    private TextView camerasValue;
    private TextView recordingValue;
    private TextView storageValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Recreated cold (process death): the in-memory ApiClient singleton is
        // gone. Bounce through the splash to re-init it before any request.
        try {
            ApiClient.getInstance();
        } catch (IllegalStateException e) {
            startActivity(new Intent(this, SplashActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_monitor);

        MaterialToolbar toolbar = findViewById(R.id.monitor_toolbar);
        setSupportActionBar(toolbar);

        progress = findViewById(R.id.monitor_progress);
        serverValue = findViewById(R.id.monitor_server_value);
        uptimeValue = findViewById(R.id.monitor_uptime_value);
        camerasValue = findViewById(R.id.monitor_cameras_value);
        recordingValue = findViewById(R.id.monitor_recording_value);
        storageValue = findViewById(R.id.monitor_storage_value);

        MaterialButton refresh = findViewById(R.id.monitor_refresh_button);
        refresh.setOnClickListener(v -> loadStatus());

        MaterialButton logout = findViewById(R.id.monitor_logout_button);
        logout.setOnClickListener(v -> Logout.confirm(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadStatus();
    }

    private void loadStatus() {
        progress.setVisibility(VISIBLE);
        ApiClient.getApiService().status().enqueue(new ApiCallback<Status>(this) {
            @Override
            public void onSuccess(Status body) {
                progress.setVisibility(GONE);
                if (body == null) {
                    Toast.makeText(MonitorActivity.this, R.string.monitor_unavailable, LENGTH_LONG).show();
                    return;
                }
                bind(body);
            }

            @Override
            public void onError(int httpCode, String message) {
                progress.setVisibility(GONE);
                // A 401 is handled centrally by ApiCallback (clear + login).
                Toast.makeText(MonitorActivity.this, R.string.monitor_unavailable, LENGTH_LONG).show();
            }
        });
    }

    private void bind(Status status) {
        String service = status.getService() != null ? status.getService() : "eneverre";
        if (status.getVersion() != null) {
            service = service + " " + status.getVersion();
        }
        serverValue.setText(service);
        uptimeValue.setText(formatUptime(status.getUptimeSeconds()));

        Status.Totals totals = status.getTotals();
        if (totals != null) {
            camerasValue.setText(getString(R.string.monitor_cameras_value,
                    totals.getConnected(), totals.getCameras()));
            recordingValue.setText(status.isRecordingEnabled()
                    ? getString(R.string.monitor_recording_on, totals.getRecording())
                    : getString(R.string.off));
        } else {
            camerasValue.setText(R.string.monitor_value_unknown);
            recordingValue.setText(R.string.monitor_value_unknown);
        }

        Status.Storage storage = status.getStorage();
        if (storage != null) {
            storageValue.setText(getString(R.string.monitor_storage_value,
                    Formatter.formatShortFileSize(this, storage.getFreeBytes()),
                    Formatter.formatShortFileSize(this, storage.getTotalBytes())));
        } else {
            storageValue.setText(R.string.monitor_storage_unknown);
        }
    }

    /** "3d 04h 12m", "4h 12m" or "12m": short enough for one line at any age. */
    private static String formatUptime(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) {
            return String.format(Locale.getDefault(), "%dd %02dh %02dm", days, hours, minutes);
        }
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%dh %02dm", hours, minutes);
        }
        return String.format(Locale.getDefault(), "%dm", minutes);
    }
}

