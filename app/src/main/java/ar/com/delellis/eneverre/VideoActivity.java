package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;

import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Rational;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;

import androidx.appcompat.app.AppCompatActivity;

import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.ApiService;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.Recording;
import ar.com.delellis.eneverre.util.Snapshot;
import ar.com.delellis.eneverre.util.VideoTouchListener;
import ar.com.delellis.eneverre.player.VlcPlayer;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;


public class VideoActivity extends AppCompatActivity {

    private static final String TAG = "VideoActivity";

    public static final String PLAYBACK_LIST_DATA = "PLAYBACK_LIST";

    private VlcPlayer vlcPlayer = null;
    private VLCVideoLayout vlcVideoLayout = null;

    private Camera currentCamera = null;

    private List<Recording> recordings = null;

    private ApiClient apiClient = null;
    private ApiService apiService = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video);

        Intent intent = getIntent();
        currentCamera = (Camera) intent.getSerializableExtra(CamerasActivity.CURRENT_CAMERA_DATA);

        apiClient = ApiClient.getInstance();
        apiService = ApiClient.getApiService();

        Toolbar videoToolbar = (Toolbar) findViewById(R.id.video_toolbar);
        setSupportActionBar(videoToolbar);

        int orientation = getResources().getConfiguration().orientation;
        setOrientationLayout (orientation);

        vlcPlayer = new VlcPlayer(this);
        vlcPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.Buffering) {
                if (event.getBuffering() == 100f) {
                    findViewById(R.id.loading_progress).setVisibility(GONE);
                } else {
                    findViewById(R.id.loading_progress).setVisibility(VISIBLE);
                }
            } else if (event.type == MediaPlayer.Event.EncounteredError) {
                findViewById(R.id.loading_progress).setVisibility(GONE);
                findViewById(R.id.reconnect_button).setVisibility(VISIBLE);
            }
        });

        vlcVideoLayout = findViewById(R.id.vlc_video_Layout);
        vlcVideoLayout.setOnTouchListener(new VideoTouchListener(vlcVideoLayout));

        findViewById(R.id.go_playback_button).setEnabled(false);
        if (currentCamera.hasPlayback()) {
            findViewById(R.id.go_playback_button).setVisibility(VISIBLE);
            updatePlayback();
        } else {
            findViewById(R.id.go_playback_button).setVisibility(GONE);
        }

        findViewById(R.id.go_playback_button).setOnClickListener(v -> {
            pausePlaying();

            Log.i(TAG, "Go to playback view");
            Intent goIntent = new Intent(VideoActivity.this, PlaybackActivity.class);
            goIntent.putExtra(PLAYBACK_LIST_DATA, (Serializable) recordings);
            goIntent.putExtra(CamerasActivity.CURRENT_CAMERA_DATA, (Serializable) currentCamera);
            startActivity(goIntent);
        });

        findViewById(R.id.reconnect_button).setVisibility(GONE);
        findViewById(R.id.reconnect_button).setOnClickListener(v -> {
            startPlaying();
            findViewById(R.id.reconnect_button).setVisibility(GONE);
        });

        findViewById(R.id.take_snapshot).setVisibility(VISIBLE);
        findViewById(R.id.take_snapshot).setOnClickListener(v -> {
            takeSnapshot();
        });

        findViewById(R.id.privacy_button).setVisibility(VISIBLE);
        findViewById(R.id.privacy_button).setOnClickListener(v -> {
            stopPlaying();
            setVideoPrivacy(true);
        });

        findViewById(R.id.exit_privacy_button).setVisibility(GONE);
        findViewById(R.id.exit_privacy_button).setOnClickListener(v -> {
            startPlaying();
            setVideoPrivacy(false);
        });

        findViewById(R.id.ptz_buttons).setVisibility(currentCamera.getPtz() ? VISIBLE : GONE);

        findViewById(R.id.ptz_home_button).setOnClickListener(v -> {
            Call<Void> homeCall = apiService.home(apiClient.getAuthorization(), currentCamera.getId());
            homeCall.enqueue(new VoidPtzCallback());
        });

        findViewById(R.id.ptz_left_button).setOnClickListener(v -> {
            Call<Void> leftCall = apiService.move(apiClient.getAuthorization(), currentCamera.getId(), -45, 0);
            leftCall.enqueue(new VoidPtzCallback());
        });

        findViewById(R.id.ptz_right_button).setOnClickListener(v -> {
            Call<Void> rightCall = apiService.move(apiClient.getAuthorization(), currentCamera.getId(), 45, 0);
            rightCall.enqueue(new VoidPtzCallback());
        });

        findViewById(R.id.ptz_up_button).setOnClickListener(v -> {
            Call<Void> upCall = apiService.move(apiClient.getAuthorization(), currentCamera.getId(), 0, -45);
            upCall.enqueue(new VoidPtzCallback());
        });

        findViewById(R.id.ptz_down_button).setOnClickListener(v -> {
            Call<Void> downCall = apiService.move(apiClient.getAuthorization(), currentCamera.getId(), 0, 45);
            downCall.enqueue(new VoidPtzCallback());
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        vlcPlayer.attachView(vlcVideoLayout);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(currentCamera.getName());
        }

        if (currentCamera.getPrivacy())
            setVideoPrivacy(true);
        else
            startPlaying();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (currentCamera.getPrivacy())
            return;

        if (vlcPlayer.isPaused())
            resumePlaying();
    }

    @Override
    protected void onStop() {
        super.onStop();

        vlcPlayer.stop();
        vlcPlayer.detachViews();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        vlcPlayer.release();
    }

    @Override
    public boolean onSupportNavigateUp() {
        Intent intent = new Intent();
        intent.putExtra(CamerasActivity.CURRENT_CAMERA_DATA, currentCamera);
        setResult(RESULT_OK, intent);
        finish();

        return true;
    }

    @Override
    public void onBackPressed() {
        Intent intent = new Intent();
        intent.putExtra(CamerasActivity.CURRENT_CAMERA_DATA, currentCamera);
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

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.pip_action) {
            Rational aspectRatio = new Rational(currentCamera.getWidth(), currentCamera.getHeight());
            PictureInPictureParams pipParams = new PictureInPictureParams.Builder().setAspectRatio(aspectRatio).build();
            enterPictureInPictureMode(pipParams);
            return true;
        } else if (itemId == R.id.volume_action) {
            if (vlcPlayer.isMuted()) {
                vlcPlayer.mute(false);
                item.setIcon(R.drawable.ic_volume_24);
            } else {
                vlcPlayer.mute(true);
                item.setIcon(R.drawable.ic_muted_24);
            }
            return true;
        } else if (itemId == R.id.recalibrate_ptz) {
            Call<Void> recalibrateCall = apiService.recalibrate(apiClient.getAuthorization(), currentCamera.getId());
            recalibrateCall.enqueue(new VoidPtzCallback());
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

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);

        if (isInPictureInPictureMode) {
            findViewById(R.id.frameLayout).setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0.0F));

            findViewById(R.id.video_toolbar).setVisibility(GONE);
            if (currentCamera.getPtz()) {
                findViewById(R.id.ptz_buttons).setVisibility(GONE);
            }
            findViewById(R.id.privacy_button).setVisibility(GONE);
            findViewById(R.id.take_snapshot).setVisibility(GONE);
        } else {
            findViewById(R.id.video_toolbar).setVisibility(VISIBLE);
            if (currentCamera.getPtz()) {
                findViewById(R.id.ptz_buttons).setVisibility(VISIBLE);
            }
            findViewById(R.id.privacy_button).setVisibility(VISIBLE);
            findViewById(R.id.take_snapshot).setVisibility(VISIBLE);
        }
    }

    private void setVideoPrivacy(boolean privacy) {
        findViewById(R.id.take_snapshot).setEnabled(!privacy);
        findViewById(R.id.privacy_button).setEnabled(!privacy);

        findViewById(R.id.exit_privacy_button).setVisibility(privacy ? VISIBLE : GONE);

        if (currentCamera.getPtz()) {
            findViewById(R.id.ptz_up_button).setEnabled(!privacy);
            findViewById(R.id.ptz_down_button).setEnabled(!privacy);
            findViewById(R.id.ptz_home_button).setEnabled(!privacy);
            findViewById(R.id.ptz_left_button).setEnabled(!privacy);
            findViewById(R.id.ptz_right_button).setEnabled(!privacy);
        }

        Call<Void> privacyCall = apiService.privacy(apiClient.getAuthorization(), currentCamera.getId(), privacy);
        privacyCall.enqueue(new VoidPtzCallback());

        currentCamera.setPrivacy(privacy);
        invalidateOptionsMenu();
    }

    private void setOrientationLayout(int orientation) {
        Toolbar videoToolbar = (Toolbar) findViewById(R.id.video_toolbar);
        FrameLayout frameLayout = findViewById(R.id.frameLayout);
        View ptzButtons = findViewById(R.id.ptz_buttons);

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            videoToolbar.setVisibility(VISIBLE);
            ptzButtons.setVisibility(currentCamera.getPtz() ? VISIBLE : GONE);

            int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
            int videoWidth = currentCamera.getWidth();
            int videoHeight = currentCamera.getHeight();

            frameLayout.getLayoutParams().width = screenWidth;
            frameLayout.getLayoutParams().height = screenWidth * videoHeight / videoWidth;
        }
        else {
            videoToolbar.setVisibility(GONE);
            ptzButtons.setVisibility(GONE);

            frameLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0.0F));
        }
    }

    private void takeSnapshot() {
        SurfaceView surfaceView = findViewById(org.videolan.R.id.surface_video);
        Snapshot.getSurfaceBitmap(surfaceView, new Snapshot.PixelCopyListener() {
            @Override
            public void onSurfaceBitmapReady(Bitmap bitmap) {
                File snapshotFile = Snapshot.getSnapshotFile(currentCamera.getName());
                try {
                    OutputStream out = new BufferedOutputStream(new FileOutputStream(snapshotFile));
                    bitmap.compress(Bitmap.CompressFormat.PNG, 0, out);
                    Snapshot.shareImage(VideoActivity.this, Uri.parse(snapshotFile.getPath()), currentCamera.getName());
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            }
            @Override
            public void onSurfaceBitmapError(String errorMsg) {
                Toast.makeText(VideoActivity.this, errorMsg, LENGTH_LONG).show();
            }
        });
    }

    private void startPlaying() {
        String videoUrl = currentCamera.getLive();
        Log.d(TAG, "Playing video url: " + videoUrl);
        vlcPlayer.playUri(Uri.parse(videoUrl));
    }

    private void pausePlaying() {
        Log.d(TAG, "Pause live stream");
        vlcPlayer.pause();
    }

    private void resumePlaying() {
        Log.d(TAG, "Resume live stream");
        vlcPlayer.resume();
    }

    private void stopPlaying() {
        vlcPlayer.stop();

        Log.d(TAG, "Stopping video");

        /*
         * NOTE: Stop vlc don't clear the image... Here we paint the surface black.
         *
         * FIXME: This works, but it changes the size of the canvas?.
         * The video is smaller than before.... but when you zoom in, it has much better quality.
         * I must investigate whether it can be used.
         */
        SurfaceView surfaceView = findViewById(org.videolan.R.id.surface_video);
        SurfaceHolder holder = surfaceView.getHolder();
        Canvas canvas = holder.lockCanvas();
        canvas.drawColor(Color.BLACK);
        holder.unlockCanvasAndPost(canvas);
    }

    private void updatePlayback () {
        Call<List<Recording>> playbackCall = apiService.recordings(ApiClient.getInstance().getAuthorization(), currentCamera.getId());
        playbackCall.enqueue(new Callback<List<Recording>>() {
            @Override
            public void onResponse(Call<List<Recording>> call, Response<List<Recording>> response) {
                recordings = response.body();
                Log.i(TAG, "Recording list size: " + recordings.size());
                findViewById(R.id.go_playback_button).setEnabled(true);
            }
            @Override
            public void onFailure(Call<List<Recording>> call, Throwable throwable) {
                // TODO: Show dialog with message
                Log.e(TAG, throwable.toString());
            }
        });
    }

    private static class VoidPtzCallback implements Callback<Void> {
        @Override
        public void onResponse(Call<Void> call, Response<Void> response) {
            // Yeah!.
        }
        @Override
        public void onFailure(Call<Void> call, Throwable throwable) {
            // D'Oh!.
        }
    }
}