package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;

import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.model.Location;

/**
 * Single host for a location's cameras, with bottom tabs:
 * <ul>
 *   <li><b>Live</b> — {@link LiveContainerFragment}, a swipeable pager over the
 *       location's cameras.</li>
 *   <li><b>Playback</b> — {@link PlaybackContainerFragment}, a swipeable pager
 *       over the same cameras for recording playback.</li>
 * </ul>
 *
 * Both pagers stay synchronized on the same camera position. Tab lifecycle is
 * driven with {@link FragmentTransaction#setMaxLifecycle}: the hidden tab is
 * capped to STARTED so its visible page pauses (live stops; playback stops),
 * leaving at most one player streaming at a time.
 */
public class ViewActivity extends AppCompatActivity
        implements LiveViewFragment.OnPrivacyChangeListener, OnCameraChangeListener {

    private static final String TAG_LIVE = "live";
    private static final String TAG_PLAYBACK = "playback";

    /** Open straight on the Playback tab (e.g. from a shared event link). */
    public static final String EXTRA_START_ON_PLAYBACK = "START_ON_PLAYBACK";
    /** Epoch millis to seek the playback to when {@link #EXTRA_START_ON_PLAYBACK}. */
    public static final String EXTRA_INITIAL_TIME_MS = "INITIAL_TIME_MS";

    private Location location;
    private Camera currentCamera;
    private int currentPosition = 0;

    private LiveContainerFragment liveContainer;
    private PlaybackContainerFragment playbackContainer;
    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view);

        Toolbar toolbar = findViewById(R.id.video_toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finishWithResult());

        Intent intent = getIntent();
        location = (Location) intent.getSerializableExtra(CamerasActivity.LOCATION_CAMERAS_DATA);
        currentPosition = intent.getIntExtra(CamerasActivity.SELECTED_CAMERA_DATA, 0);

        bottomNav = findViewById(R.id.bottom_nav);

        FragmentManager fm = getSupportFragmentManager();
        liveContainer = (LiveContainerFragment) fm.findFragmentByTag(TAG_LIVE);
        playbackContainer = (PlaybackContainerFragment) fm.findFragmentByTag(TAG_PLAYBACK);
        if (liveContainer == null) {
            liveContainer = LiveContainerFragment.newInstance(location, currentPosition);
        }

        // A fresh deep link (shared event) opens directly on Playback at the
        // event's moment. On a restore we ignore it and land on Live: restoring
        // straight into Playback would need the current camera, which only
        // arrives asynchronously from the pager.
        boolean startOnPlayback = intent.getBooleanExtra(EXTRA_START_ON_PLAYBACK, false);
        if (savedInstanceState == null && startOnPlayback) {
            long initialTimeMs = intent.getLongExtra(EXTRA_INITIAL_TIME_MS, 0L);
            playbackContainer = PlaybackContainerFragment.newInstance(location, currentPosition, initialTimeMs);
            showPlayback();
            bottomNav.setSelectedItemId(R.id.nav_playback);
        } else {
            showLive();
            bottomNav.setSelectedItemId(R.id.nav_live);
        }

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_live) {
                showLive();
                return true;
            } else if (id == R.id.nav_playback) {
                showPlayback();
                return true;
            }
            return false;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (bottomNav.getSelectedItemId() == R.id.nav_playback) {
                    bottomNav.setSelectedItemId(R.id.nav_live);
                } else {
                    finishWithResult();
                }
            }
        });
    }

    private void showLive() {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();

        if (!liveContainer.isAdded()) {
            tx.add(R.id.tab_container, liveContainer, TAG_LIVE);
        } else {
            tx.show(liveContainer);
        }
        tx.setMaxLifecycle(liveContainer, Lifecycle.State.RESUMED);

        if (playbackContainer != null && playbackContainer.isAdded()) {
            tx.hide(playbackContainer);
            tx.setMaxLifecycle(playbackContainer, Lifecycle.State.STARTED);
        }
        tx.commit();

        liveContainer.setCurrentItem(currentPosition);
        updateTitle();
    }

    private void showPlayback() {
        if (playbackContainer == null) {
            playbackContainer = PlaybackContainerFragment.newInstance(location, currentPosition);
        }

        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();

        if (!playbackContainer.isAdded()) {
            tx.add(R.id.tab_container, playbackContainer, TAG_PLAYBACK);
        } else {
            tx.show(playbackContainer);
        }
        tx.setMaxLifecycle(playbackContainer, Lifecycle.State.RESUMED);

        if (liveContainer.isAdded()) {
            tx.hide(liveContainer);
            tx.setMaxLifecycle(liveContainer, Lifecycle.State.STARTED);
        }
        tx.commit();

        playbackContainer.setCurrentItem(currentPosition);
        updateTitle();
    }

    private void updateTitle() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null && currentCamera != null) {
            actionBar.setTitle(currentCamera.getName());
        }
    }

    @Override
    public void onCameraChanged(Camera camera, int position) {
        currentCamera = camera;
        currentPosition = position;
        updateTitle();
        updatePlaybackTabEnabled();
    }

    /**
     * Enables the Playback tab only when the current camera supports it, so it
     * can't be opened from Live for cameras with no recordings. (Swiping into an
     * unsupported camera while already on the tab still shows the empty state.)
     */
    private void updatePlaybackTabEnabled() {
        boolean supported = currentCamera != null && currentCamera.hasPlayback();
        bottomNav.getMenu().findItem(R.id.nav_playback).setEnabled(supported);
    }

    /**
     * Whether this device can enter Picture-in-Picture. PiP params/entry need
     * API 26+ (minSdk is 24) and the hardware/OS must advertise the feature —
     * Android TV and some phones don't. The menu action is gated on this too.
     */
    public boolean isPipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /** Called by the visible {@link LiveViewFragment} from its menu. */
    public void enterPipMode(Camera camera) {
        // Guard both the API level and the feature: without this, API 24/25 hit a
        // missing PictureInPictureParams and unsupported devices throw on entry.
        if (!isPipSupported()) {
            return;
        }
        Rational aspectRatio = new Rational(camera.getWidth(), camera.getHeight());
        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build();
        enterPictureInPictureMode(params);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);

        findViewById(R.id.app_bar).setVisibility(isInPictureInPictureMode ? GONE : VISIBLE);
        bottomNav.setVisibility(isInPictureInPictureMode ? GONE : VISIBLE);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (isInPictureInPictureMode()) {
            return;
        }

        boolean landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        findViewById(R.id.app_bar).setVisibility(landscape ? GONE : VISIBLE);
        bottomNav.setVisibility(landscape ? GONE : VISIBLE);
    }

    private void finishWithResult() {
        Intent intent = new Intent();
        intent.putExtra(CamerasActivity.LOCATION_CAMERAS_DATA, location);
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finishWithResult();
        return true;
    }

    @Override
    public void onPrivacyChanged(Camera camera, boolean enabled) {
        // The camera the fragment toggled is a serialized copy, so fold the new
        // privacy state back into this activity's location model (matched by id).
        // finishWithResult() then hands the up-to-date state to the camera list.
        location.getCameras().update(camera);

        if (enabled) {
            Toast.makeText(this, R.string.privacy_enabled, LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.privacy_disabled, LENGTH_SHORT).show();
        }
    }
}
