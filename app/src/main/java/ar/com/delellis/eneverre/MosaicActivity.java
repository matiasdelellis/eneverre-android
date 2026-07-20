package ar.com.delellis.eneverre;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.videolan.libvlc.LibVLC;

import ar.com.delellis.eneverre.adapter.MosaicAdapter;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.model.Location;
import ar.com.delellis.eneverre.player.VlcPlayer;

/**
 * Live mosaic of all the cameras of a {@link Location}: a scrolling grid where
 * each visible cell streams RTSP simultaneously (see {@link MosaicAdapter}).
 * Tapping a cell opens the full single-camera {@link ViewActivity}.
 *
 * Streams follow the activity's visible lifetime: the adapter (and therefore
 * every cell's player) is attached in {@link #onStart} and detached in
 * {@link #onStop}, so nothing streams while the app is in the background. The
 * shared {@link LibVLC} engine is released once in {@link #onDestroy}.
 */
public class MosaicActivity extends AppCompatActivity implements MosaicAdapter.OnCellClickListener {

    private Location location;
    private LibVLC libVlc;
    private RecyclerView recyclerView;
    private GridLayoutManager layoutManager;
    private MosaicAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Recreated cold (process death): the in-memory ApiClient singleton is
        // gone. Bounce through the splash to re-init it, matching ViewActivity.
        try {
            ApiClient.getInstance();
        } catch (IllegalStateException e) {
            startActivity(new Intent(this, SplashActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_mosaic);

        Toolbar toolbar = findViewById(R.id.mosaic_toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        location = (Location) getIntent().getSerializableExtra(CamerasActivity.LOCATION_CAMERAS_DATA);

        recyclerView = findViewById(R.id.mosaic_list);
        TextView emptyView = findViewById(R.id.mosaic_empty);

        int cameraCount = (location == null) ? 0 : location.getCameras().count();
        if (cameraCount == 0) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            return;
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(location.getName());
        }

        layoutManager = new GridLayoutManager(this, spanCountFor(cameraCount));
        // Don't prefetch offscreen cells: prefetching would attach (and start
        // streaming) cells before they are visible, defeating the on-screen cap.
        layoutManager.setItemPrefetchEnabled(false);
        recyclerView.setLayoutManager(layoutManager);

        libVlc = VlcPlayer.newLibVlc(this);
        adapter = new MosaicAdapter(this, libVlc, location.getCameras(), this);

        // Reserve room below the last row for the gesture/navigation bar (the app
        // draws edge-to-edge on Android 15), matching CamerasActivity.
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
            return insets;
        });

        // Hide the toolbar in landscape so the grid uses the full screen, applied
        // now too (onConfigurationChanged only fires on a later rotation), matching
        // ViewActivity.
        updateToolbarForOrientation(getResources().getConfiguration().orientation);
    }

    /** Hides the toolbar in landscape to give the grid the whole screen. */
    private void updateToolbarForOrientation(int orientation) {
        boolean landscape = orientation == Configuration.ORIENTATION_LANDSCAPE;
        findViewById(R.id.app_bar).setVisibility(landscape ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Attaching the adapter starts the visible cells' streams; nothing runs
        // until the activity is actually on screen.
        if (adapter != null && recyclerView.getAdapter() == null) {
            recyclerView.setAdapter(adapter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Detaching every cell (via setAdapter(null)) stops all streams while the
        // activity is not visible, so no RTSP session lingers in the background.
        if (recyclerView != null) {
            recyclerView.setAdapter(null);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cells were already torn down in onStop; free the shared engine last.
        if (libVlc != null) {
            libVlc.release();
            libVlc = null;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (layoutManager != null && location != null) {
            layoutManager.setSpanCount(spanCountFor(location.getCameras().count()));
        }
        updateToolbarForOrientation(newConfig.orientation);
    }

    /**
     * Grid columns for the given camera count and orientation: portrait stacks
     * every camera full-width in a single column; landscape prefers a 2x2 quad
     * (two columns) for up to four cameras and goes to three columns beyond that.
     */
    private int spanCountFor(int cameraCount) {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (!landscape) {
            return 1;
        }
        if (cameraCount <= 1) {
            return 1;
        }
        return cameraCount < 5 ? 2 : 3;
    }

    @Override
    public void onCellClick(int position) {
        Intent liveIntent = new Intent(this, ViewActivity.class);
        liveIntent.putExtra(CamerasActivity.LOCATION_CAMERAS_DATA, location);
        liveIntent.putExtra(CamerasActivity.SELECTED_CAMERA_DATA, position);
        startActivity(liveIntent);
    }
}
