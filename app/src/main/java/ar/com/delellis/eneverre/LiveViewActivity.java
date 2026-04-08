package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_SHORT;

import static ar.com.delellis.eneverre.CamerasActivity.SELECTED_CAMERA_DATA;

import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Rational;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import ar.com.delellis.eneverre.adapter.CameraPagerAdapter;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.model.Location;

public class LiveViewActivity extends AppCompatActivity implements LiveViewFragment.OnPrivacyChangeListener {

    private Location location;
    private Camera currentCamera;

    boolean liveMuted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live_view);

        int orientation = getResources().getConfiguration().orientation;
        setOrientationLayout (orientation);

        Toolbar videoToolbar = findViewById(R.id.video_toolbar);
        setSupportActionBar(videoToolbar);

        ViewPager2 viewPager = findViewById(R.id.viewPager);
        viewPager.setOffscreenPageLimit(1);

        Intent intent = getIntent();
        location = (Location) intent.getSerializableExtra(CamerasActivity.LOCATION_CAMERAS_DATA);

        CameraPagerAdapter adapter = new CameraPagerAdapter(this, location);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            public void onPageSelected(int position) {
                super.onPageSelected(position);

                currentCamera = location.getCameras().get(position);
                ActionBar actionBar = getSupportActionBar();
                if (actionBar != null) {
                    actionBar.setTitle(currentCamera.getName());
                }
            }
        });

        viewPager.setAdapter(adapter);
        int cameraSelected = intent.getIntExtra(SELECTED_CAMERA_DATA, 0);
        viewPager.setCurrentItem(cameraSelected, false);
    }

    @Override
    public boolean onSupportNavigateUp() {
        Intent intent = new Intent();
        intent.putExtra(CamerasActivity.LOCATION_CAMERAS_DATA, location);
        setResult(RESULT_OK, intent);
        finish();

        return true;
    }

    @Override
    public void onBackPressed() {
        Intent intent = new Intent();
        intent.putExtra(CamerasActivity.LOCATION_CAMERAS_DATA, location);
        setResult(RESULT_OK, intent);
        finish();

        super.onBackPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.video_top_app_bar, menu);

        menu.findItem(R.id.pip_action).setVisible(!currentCamera.getPrivacy());
        menu.findItem(R.id.volume_action).setVisible(!currentCamera.getPrivacy());
        menu.findItem(R.id.recalibrate_ptz).setVisible(currentCamera.getPtz());

        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.pip_action).setVisible(!currentCamera.getPrivacy());
        menu.findItem(R.id.volume_action).setVisible(!currentCamera.getPrivacy());
        if (currentCamera.getPtz()) {
            menu.findItem(R.id.recalibrate_ptz).setVisible(!currentCamera.getPrivacy());
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @SuppressLint("NewApi")
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.pip_action) {
            Rational aspectRatio = new Rational(currentCamera.getWidth(), currentCamera.getHeight());
            PictureInPictureParams pipParams = new PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build();
            enterPictureInPictureMode(pipParams);
            return true;
        } else if (itemId == R.id.volume_action) {
            if (liveMuted) {
                muteLive(false);
                item.setIcon(R.drawable.ic_volume_24);
            } else {
                muteLive(true);
                item.setIcon(R.drawable.ic_muted_24);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (isInPictureInPictureMode())
            return;

        setOrientationLayout(newConfig.orientation);
    }

    private void setOrientationLayout(int orientation) {
        Toolbar videoToolbar = findViewById(R.id.video_toolbar);
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            videoToolbar.setVisibility(VISIBLE);
        }
        else {
            videoToolbar.setVisibility(GONE);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        findViewById(R.id.video_toolbar).setVisibility(isInPictureInPictureMode ? GONE : VISIBLE);

        // FIXME: There's always one live video, but perhaps others can be seen with gestures
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof LiveViewFragment) {
                LiveViewFragment f = (LiveViewFragment) fragment;

                if (f.getView() != null) {
                    f.setPipMode(isInPictureInPictureMode);
                }
            }
        }
    }

    private void muteLive(boolean muted) {
        // FIXME: There's always one live/fragment and running
        for (Fragment fragment : getSupportFragmentManager().getFragments()) {
            if (fragment instanceof LiveViewFragment) {
                ((LiveViewFragment) fragment).setMuteLive(muted);
            }
        }
        liveMuted = muted;
    }

    @Override
    public void onPrivacyChanged(Camera camera, boolean enabled) {
        if (enabled) {
            Toast.makeText(this, R.string.privacy_enabled, LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.privacy_disabled, LENGTH_SHORT).show();
        }
    }
}