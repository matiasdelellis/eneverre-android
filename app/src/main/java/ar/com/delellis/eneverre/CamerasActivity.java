package ar.com.delellis.eneverre;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import ar.com.delellis.eneverre.adapter.LocationsAdapter;
import ar.com.delellis.eneverre.adapter.OnCameraClickListener;
import ar.com.delellis.eneverre.adapter.OnLocationClickListener;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.model.Cameras;
import ar.com.delellis.eneverre.model.Location;
import ar.com.delellis.eneverre.model.Locations;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.DeviceLinker;
import ar.com.delellis.eneverre.util.SecureStore;
import ar.com.delellis.eneverre.util.UpdateChecker;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class CamerasActivity extends AppCompatActivity
        implements OnCameraClickListener, OnLocationClickListener {

    public static final String RAW_CAMERAS_LIST_DATA = "RAW_CAMERA_LIST";
    public static final String LOCATION_CAMERAS_DATA = "LOCATION_CAMERAS";

    public static final String SELECTED_CAMERA_DATA = "SELECTED_CAMERA";

    /** A device user code to confirm and authorize, delivered from a link. */
    public static final String EXTRA_PENDING_USER_CODE = "PENDING_USER_CODE";

    private Locations locations = null;

    private LocationsAdapter locationsAdapter = null;

    private RecyclerView recyclerView = null;
    private SwipeRefreshLayout swipeRefresh = null;
    private TextView emptyView = null;

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

    /**
     * (Re)builds the location model and adapter from a flat camera list and
     * toggles the empty state. Tolerates a {@code null} list (treated as empty)
     * so a blank/absent server response can't crash {@link Locations}.
     */
    private void showCameras(List<Camera> cameraList) {
        if (cameraList == null) {
            cameraList = new ArrayList<>();
        }
        locations = new Locations(cameraList);
        locationsAdapter = new LocationsAdapter(this, locations, this, this);
        recyclerView.setAdapter(locationsAdapter);

        boolean empty = locations.count() == 0;
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** Re-fetches the camera list from the server for pull-to-refresh. */
    private void refreshCameras() {
        ApiClient.getApiService().cameras().enqueue(new ApiCallback<List<Camera>>(this) {
            @Override
            public void onSuccess(List<Camera> cameras) {
                swipeRefresh.setRefreshing(false);
                showCameras(cameras);
            }
            @Override
            public void onError(int httpCode, String message) {
                swipeRefresh.setRefreshing(false);
                super.onError(httpCode, message);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Recreated cold (process death): the in-memory ApiClient singleton is
        // gone, so bounce through the splash to re-init it before any request.
        try {
            ApiClient.getInstance();
        } catch (IllegalStateException e) {
            startActivity(new Intent(this, SplashActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_cameras);

        Toolbar myToolbar = findViewById(R.id.cameras_toolbar);
        setSupportActionBar(myToolbar);

        Intent intent = getIntent();
        List<Camera> cameraList = (List<Camera>) intent.getSerializableExtra(RAW_CAMERAS_LIST_DATA);

        recyclerView = findViewById(R.id.camera_list_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        emptyView = findViewById(R.id.cameras_empty);

        swipeRefresh = findViewById(R.id.cameras_refresh);
        swipeRefresh.setOnRefreshListener(this::refreshCameras);

        // Reserve room below the last row for the gesture/navigation bar: with
        // targetSdk 35 Android 15 draws the app edge-to-edge, so the system bar
        // insets are ours to consume.
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
            return insets;
        });

        showCameras(cameraList);

        // Opened from a device-linking link (<host>/?usercode=XXXXXX): confirm
        // before authorizing, instead of asking the user to type the code.
        String pendingUserCode = intent.getStringExtra(EXTRA_PENDING_USER_CODE);
        if (pendingUserCode != null && !pendingUserCode.isEmpty()) {
            DeviceLinker.confirm(this, pendingUserCode.toUpperCase());
        }

        // Fire the auto-update check from the first long-lived activity on
        // the post-login flow: SplashActivity and LoginActivity both finish
        // before the response can come back, so the dialog would be dropped
        // if fired from there. Runs at most once per cold start.
        UpdateChecker.checkForUpdate(this);
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

    @Override
    public void onMosaicClick(Location location) {
        Intent mosaicIntent = new Intent(CamerasActivity.this, MosaicActivity.class);
        mosaicIntent.putExtra(LOCATION_CAMERAS_DATA, location);
        startActivity(mosaicIntent);
    }
}