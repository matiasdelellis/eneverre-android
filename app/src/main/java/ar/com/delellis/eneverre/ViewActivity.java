package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;

import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.model.Location;
import ar.com.delellis.eneverre.util.SecureStore;
import ar.com.delellis.eneverre.util.SystemBars;
import ar.com.delellis.eneverre.util.VideoLayout;

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

        // Recreated cold (process death): the in-memory ApiClient singleton is
        // gone. Bounce through the splash before the restored fragments try to
        // use it, otherwise LiveViewFragment/PlaybackFragment crash on getApiService().
        try {
            ApiClient.getInstance();
        } catch (IllegalStateException e) {
            startActivity(new Intent(this, SplashActivity.class));
            finish();
            return;
        }

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
        boolean startOnPlayback = intent.getBooleanExtra(EXTRA_START_ON_PLAYBACK, false)
                && SecureStore.getInstance().canPlayback();
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

        // Apply the initial chrome visibility for the current orientation:
        // onConfigurationChanged only fires on a later rotation, so launching
        // straight into landscape (e.g. from the mosaic) would otherwise keep
        // the toolbar and bottom bar showing until the first rotate.
        updateChromeForOrientation(getResources().getConfiguration().orientation);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The role can change server-side while the app is in the background;
        // re-evaluate the Playback tab so a demoted account loses it.
        updatePlaybackTabEnabled();
    }

    /**
     * Called by the visible fragment when a single-camera endpoint answered
     * {@code 404} and a fresh camera list confirmed the camera is gone — it left
     * this account's ACL, or an admin deleted it. The list is authoritative, so
     * close the view and ask {@link CamerasActivity} to reload instead of
     * retrying a camera this account may no longer see.
     */
    public void onCameraUnavailable(Camera camera) {
        Toast.makeText(this, R.string.error_camera_unavailable, LENGTH_LONG).show();
        setResult(CamerasActivity.RESULT_CAMERA_GONE);
        finish();
    }

    /** Hides the app chrome and the system bars in landscape for a full-screen video. */
    private void updateChromeForOrientation(int orientation) {
        setFullscreen(orientation == Configuration.ORIENTATION_LANDSCAPE);
    }

    /**
     * Full screen means the video gets the whole display: our own chrome
     * (toolbar, bottom tabs) and the system bars are hidden together, so
     * landscape does not keep a status/navigation strip over the picture.
     */
    private void setFullscreen(boolean fullscreen) {
        findViewById(R.id.app_bar).setVisibility(fullscreen ? GONE : VISIBLE);
        bottomNav.setVisibility(fullscreen ? GONE : VISIBLE);
        SystemBars.setHidden(this, fullscreen);
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
     * Enables the Playback tab only when the account's role allows playback
     * ({@code viewer} and {@code monitor} never do) and the current camera
     * supports it, so it can't be opened from Live for cameras with no
     * recordings. (Swiping into an unsupported camera while already on the tab
     * still shows the empty state.)
     */
    private void updatePlaybackTabEnabled() {
        boolean roleAllows = SecureStore.getInstance().canPlayback();
        boolean supported = roleAllows && currentCamera != null && currentCamera.hasPlayback();
        bottomNav.getMenu().findItem(R.id.nav_playback).setEnabled(supported);
        // A role change mid-session (viewer can never play back) must not leave
        // the tab selected on a screen it may not use.
        if (!roleAllows && bottomNav.getSelectedItemId() == R.id.nav_playback) {
            bottomNav.setSelectedItemId(R.id.nav_live);
        }
    }

    /**
     * Whether this device can enter Picture-in-Picture. PiP params/entry need
     * API 26+ (minSdk is 24) and the hardware/OS must advertise the feature —
     * Android TV and some phones don't. The menu action is gated on this too.
     *
     * The annotation is what tells lint that a true here means API 26+, so the
     * PiP calls guarded by it are not flagged as unsafe on minSdk.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
    public boolean isPipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /**
     * Called by the visible {@link LiveViewFragment} from its menu.
     *
     * @param sourceHint window-coordinate bounds of the video on screen, or
     *                   {@code null}. Without it the system has no idea which
     *                   part of the window survives the transition, so it
     *                   cross-fades the whole thing — and whatever the app has
     *                   not drawn yet at the new size (the window behind, the
     *                   task's starting window with the app icon) shows through
     *                   while the animation runs. With it, the animation is a
     *                   crop-and-scale of the picture itself, and on Android 12+
     *                   it also smooths the way back out.
     */
    public void enterPipMode(Camera camera, Rect sourceHint) {
        // Guard both the API level and the feature: without this, API 24/25 hit a
        // missing PictureInPictureParams and unsupported devices throw on entry.
        if (!isPipSupported()) {
            return;
        }
        Rational aspectRatio = VideoLayout.pipAspectRatio(camera.getWidth(), camera.getHeight());
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio);
        if (sourceHint != null && !sourceHint.isEmpty()) {
            builder.setSourceRectHint(sourceHint);
        }
        PictureInPictureParams params = builder.build();
        try {
            enterPictureInPictureMode(params);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Device declined PiP entry (unsupported state or rejected ratio);
            // stay in full-screen rather than crash.
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);

        if (isInPictureInPictureMode) {
            // The PiP window shows only the video.
            setFullscreen(true);
        } else {
            // Back at full size: restore what the current orientation asks for
            // (a rotation that happened while in PiP is applied here too).
            updateChromeForOrientation(getResources().getConfiguration().orientation);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (isInPictureInPictureMode()) {
            return;
        }

        updateChromeForOrientation(newConfig.orientation);
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
