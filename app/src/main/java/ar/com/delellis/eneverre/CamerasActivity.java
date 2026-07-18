package ar.com.delellis.eneverre;

import static android.widget.Toast.LENGTH_LONG;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import ar.com.delellis.eneverre.adapter.LocationsAdapter;
import ar.com.delellis.eneverre.adapter.OnCameraClickListener;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.UserCode;
import ar.com.delellis.eneverre.api.model.VerifyStatus;
import ar.com.delellis.eneverre.model.Cameras;
import ar.com.delellis.eneverre.model.Location;
import ar.com.delellis.eneverre.model.Locations;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.ApiError;
import ar.com.delellis.eneverre.util.SecureStore;
import ar.com.delellis.eneverre.util.UpdateChecker;
import ar.com.delellis.eneverre.util.UserCodePickerDialog;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class CamerasActivity extends AppCompatActivity implements OnCameraClickListener {

    public static final String RAW_CAMERAS_LIST_DATA = "RAW_CAMERA_LIST";
    public static final String LOCATION_CAMERAS_DATA = "LOCATION_CAMERAS";

    public static final String SELECTED_CAMERA_DATA = "SELECTED_CAMERA";

    /** A device user code to confirm and authorize, delivered from a link. */
    public static final String EXTRA_PENDING_USER_CODE = "PENDING_USER_CODE";

    private Locations locations = null;

    private LocationsAdapter locationsAdapter = null;

    private final ActivityResultLauncher<Intent> liveViewLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Location location = (Location) result.getData().getSerializableExtra(LOCATION_CAMERAS_DATA);
                    applyCameraChanges(location);
                }
            });

    /**
     * Folds privacy (and any other mutable per-camera state) reported back by
     * {@link ViewActivity} into the master {@link #locations} model, so changes
     * made inside the app take effect on the camera list immediately without a
     * server round-trip. The returned {@link Location} is a serialized copy, so
     * we merge by id via {@link Cameras#update(Camera)} rather than by reference.
     */
    private void applyCameraChanges(Location changed) {
        if (changed == null || locations == null) {
            return;
        }
        Location master = locations.get(changed.getName());
        if (master == null) {
            return;
        }
        Cameras changedCameras = changed.getCameras();
        for (int i = 0; i < changedCameras.count(); i++) {
            master.getCameras().update(changedCameras.get(i));
        }
        locationsAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cameras);

        Toolbar myToolbar = findViewById(R.id.cameras_toolbar);
        setSupportActionBar(myToolbar);

        Intent intent = getIntent();
        List<Camera> cameraList = (List<Camera>) intent.getSerializableExtra(RAW_CAMERAS_LIST_DATA);

        RecyclerView recyclerView = findViewById(R.id.camera_list_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        locations = new Locations(cameraList);
        locationsAdapter = new LocationsAdapter(this, locations, this);
        recyclerView.setAdapter(locationsAdapter);

        // Opened from a device-linking link (<host>/?usercode=XXXXXX): confirm
        // before authorizing, instead of asking the user to type the code.
        String pendingUserCode = intent.getStringExtra(EXTRA_PENDING_USER_CODE);
        if (pendingUserCode != null && !pendingUserCode.isEmpty()) {
            confirmDeviceLink(pendingUserCode.toUpperCase());
        }

        // Fire the auto-update check from the first long-lived activity on
        // the post-login flow: SplashActivity and LoginActivity both finish
        // before the response can come back, so the dialog would be dropped
        // if fired from there. Runs at most once per cold start.
        UpdateChecker.checkForUpdate(this);
    }

    private void confirmDeviceLink(String userCode) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.link_device)
                .setMessage(getString(R.string.link_device_confirm, userCode))
                .setPositiveButton(R.string.accept, (dialog, which) -> onUserCodeRequest(userCode))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.cameras_top_app_bar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }
        else if (itemId == R.id.linkDevice) {
            UserCodePickerDialog.show(this, code -> {
                onUserCodeRequest(code);
            });
            return true;
        }
        else if (itemId == R.id.sessions) {
            startActivity(new Intent(this, SessionsActivity.class));
            return true;
        }
        else if (itemId == R.id.logout) {
            confirmLogout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmLogout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.logout_confirm_title)
                .setMessage(R.string.logout_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.logout, (dialog, which) -> performLogout())
                .show();
    }

    private void performLogout() {
        // Best-effort server-side revocation of the current token; don't block
        // the UI on it (if offline the token simply lapses at its 30-day TTL).
        ApiClient.getApiService().logout().enqueue(new Callback<Void>() {
            @Override
            public void onResponse(Call<Void> call, Response<Void> response) { }
            @Override
            public void onFailure(Call<Void> call, Throwable t) { }
        });

        SecureStore.getInstance(this).clearCredentials();

        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public void onCameraClick(Camera camera, int position) {
        Location location = locations.get(camera.getLocation());

        Intent liveIntent = new Intent(CamerasActivity.this, ViewActivity.class);
        liveIntent.putExtra(LOCATION_CAMERAS_DATA, location);
        liveIntent.putExtra(SELECTED_CAMERA_DATA, position);
        liveViewLauncher.launch(liveIntent);
    }

    private void onUserCodeRequest(String user_code) {
        UserCode userCode = new UserCode(user_code);

        ApiClient.getApiService().device_verify(userCode).enqueue(new ApiCallback<VerifyStatus>(this) {
            @Override
            public void onSuccess(VerifyStatus verifyStatus) {
                if (verifyStatus == null) {
                    onError(ApiError.NO_HTTP_CODE, null);
                    return;
                }

                String status = verifyStatus.getStatus();
                if ("approved".equals(status)) {
                    String deviceName = verifyStatus.getDeviceName();
                    String message = deviceName != null && !deviceName.trim().isEmpty()
                            ? getString(R.string.approved_device_named, deviceName.trim())
                            : getString(R.string.approved_device);
                    Toast.makeText(CamerasActivity.this, message, LENGTH_LONG).show();
                } else if ("expired".equals(status)) {
                    Toast.makeText(CamerasActivity.this, R.string.the_code_has_expired, LENGTH_LONG).show();
                } else if ("invalid".equals(status)) {
                    Toast.makeText(CamerasActivity.this, R.string.invalid_user_code, LENGTH_LONG).show();
                }
            }
            @Override
            public void onError(int httpCode, String message) {
                Toast.makeText(CamerasActivity.this, R.string.device_linking_failed, LENGTH_LONG).show();
            }
        });
    }
}