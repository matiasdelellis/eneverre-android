package ar.com.delellis.eneverre;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ar.com.delellis.eneverre.adapter.LocationsAdapter;
import ar.com.delellis.eneverre.adapter.OnCameraClickListener;
import ar.com.delellis.eneverre.adapter.OnLocationClickListener;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.CameraRefresher;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.model.Cameras;
import ar.com.delellis.eneverre.model.Location;
import ar.com.delellis.eneverre.model.Locations;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.DeviceLinker;
import ar.com.delellis.eneverre.util.SecureStore;
import ar.com.delellis.eneverre.util.UpdateChecker;

public class CamerasActivity extends AppCompatActivity
        implements OnCameraClickListener, OnLocationClickListener {

    public static final String RAW_CAMERAS_LIST_DATA = "RAW_CAMERA_LIST";
    public static final String LOCATION_CAMERAS_DATA = "LOCATION_CAMERAS";

    /** Every location plus the one to open first: the mosaic pages through them. */
    public static final String ALL_LOCATIONS_DATA = "ALL_LOCATIONS";
    public static final String SELECTED_LOCATION_DATA = "SELECTED_LOCATION";

    public static final String SELECTED_CAMERA_DATA = "SELECTED_CAMERA";

    /**
     * Result from {@link ViewActivity} when the open camera is no longer
     * accessible — it left this account's ACL, or an admin deleted it. The
     * camera list is authoritative, so this triggers a reload instead of leaving
     * a dead entry on screen.
     */
    public static final int RESULT_CAMERA_GONE = RESULT_FIRST_USER;

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
                if (result.getResultCode() == RESULT_CAMERA_GONE) {
                    // The camera is no longer ours to show: reload the list.
                    refreshCameras();
                    return;
                }
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
        if (cameraList != null) {
            // The list that just arrived carries fresh signed RTSP path tokens:
            // remember when, so a live view knows how long its URL may be reused.
            CameraRefresher.markFresh();
        } else {
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
                if (httpCode == 403) {
                    // The role may have changed mid-session: a monitor account has
                    // no camera access at all, so re-read the role and, if that is
                    // what happened, move to the status-only screen.
                    ApiClient.getInstance().refreshRole(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (SecureStore.getInstance(CamerasActivity.this).isMonitor()) {
                            startActivity(new Intent(CamerasActivity.this, MonitorActivity.class));
                            finish();
                        } else {
                            Toast.makeText(CamerasActivity.this, message, Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }
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

        // Defensive: a monitor account has no camera list. Login/Splash route it
        // here already, but a role change or a stale task could land us here.
        if (SecureStore.getInstance(this).isMonitor()) {
            startActivity(new Intent(this, MonitorActivity.class));
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
            DeviceLinker.confirm(this, pendingUserCode.toUpperCase(Locale.ROOT));
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
        if (item.getItemId() == R.id.settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
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
        // The mosaic pages through every location, starting on the tapped one.
        Intent mosaicIntent = new Intent(CamerasActivity.this, MosaicActivity.class);
        mosaicIntent.putExtra(ALL_LOCATIONS_DATA, locations);
        mosaicIntent.putExtra(SELECTED_LOCATION_DATA, Math.max(0, locations.indexOf(location.getName())));
        startActivity(mosaicIntent);
    }
}