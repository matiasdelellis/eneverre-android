package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;

import static com.alexvas.widget.TimelineView.INTERVAL_HOUR_6;

import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.alexvas.widget.TimelineView;

import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.ApiService;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.Recording;
import ar.com.delellis.eneverre.player.VlcPlayer;
import ar.com.delellis.eneverre.util.Download;
import ar.com.delellis.eneverre.util.Time;
import ar.com.delellis.eneverre.util.VideoTouchListener;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

import com.alexvas.widget.TimelineView.TimeRecord;

public class PlaybackActivity extends AppCompatActivity {

    private static final String TAG = "PlaybackActivity";

    private VlcPlayer vlcPlayer = null;
    private VLCVideoLayout vlcVideoLayout = null;

    private TimelineView timelineView = null;

    private Camera currentCamera = null;

    private List<Recording> recordings = null;

    private long lastTimeSelected = 0L;
    private long lastLength = 0L;
    private boolean timelineSelecting = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playback);

        Intent intent = getIntent();
        currentCamera = (Camera) intent.getSerializableExtra(CamerasActivity.CURRENT_CAMERA_DATA);

        Toolbar videoToolbar = findViewById(R.id.playback_toolbar);
        setSupportActionBar(videoToolbar);

        int orientation = getResources().getConfiguration().orientation;
        setOrientationLayout (orientation);

        timelineView = findViewById(R.id.timeline_view);
        timelineView.setInterval(INTERVAL_HOUR_6);

        vlcVideoLayout = findViewById(R.id.vlc_playback_layout);
        vlcVideoLayout.setOnTouchListener(new VideoTouchListener(vlcVideoLayout));

        vlcPlayer = new VlcPlayer(this);
        vlcPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.Buffering) {
                if (event.getBuffering() == 100f) {
                    findViewById(R.id.loading_progress).setVisibility(GONE);
                } else {
                    findViewById(R.id.loading_progress).setVisibility(VISIBLE);
                }
            } else if (event.type == MediaPlayer.Event.PositionChanged) {
                if (timelineSelecting) return;
                long newTime = lastTimeSelected + (long) (lastLength*event.getPositionChanged());
                timelineView.setCurrentWithAnimation(newTime);
            } else if (event.type == MediaPlayer.Event.LengthChanged) {
                lastLength = event.getLengthChanged();
            } else if (event.type == MediaPlayer.Event.EndReached) {
                lastTimeSelected += lastLength;
                startPlayback(Time.MStoRFC3339(lastTimeSelected), 10.0);
                timelineView.setCurrentWithAnimation(lastTimeSelected);
            } else if (event.type == MediaPlayer.Event.EncounteredError) {
                findViewById(R.id.loading_progress).setVisibility(GONE);
            }
        });

        timelineView.setOnTimelineListener(new TimelineView.OnTimelineListener() {
            @Override
            public void onTimeSelecting() {
                timelineSelecting = true;
            }

            @Override
            public void onTimeSelected(long l, @Nullable TimeRecord timeRecord) {
                if (timeRecord == null) return;

                timelineSelecting = false;
                lastTimeSelected = l;

                startPlayback(Time.MStoRFC3339(lastTimeSelected), 10.0);
            }

            @Override
            public void onRequestMoreBackgroundData() {
                // Nop...
            }

            @Override
            public void onRequestMoreMajor1Data() {
                // Nop...
            }

            @Override
            public void onRequestMoreMajor2Data() {
                // Nop...
            }
        });

        findViewById(R.id.timeline_frame).setVisibility(GONE);

        initializeRecordings();
    }

    @Override
    protected void onStart() {
        super.onStart();

        vlcPlayer.detachViews();
        vlcPlayer.attachView(vlcVideoLayout);
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
        finish();

        return true;
    }

    @Override
    public void onBackPressed() {
        finish();

        super.onBackPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.playback_top_app_bar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.volume_action) {
            if (vlcPlayer.isMuted()) {
                vlcPlayer.mute(false);
                item.setIcon(R.drawable.ic_volume_24);
            } else {
                vlcPlayer.mute(true);
                item.setIcon(R.drawable.ic_muted_24);
            }
            return true;
        } else if (itemId == R.id.pause_action) {
            if (vlcPlayer.isPaused()) {
                vlcPlayer.resume();
                item.setIcon(R.drawable.ic_pause_24);
            } else {
                vlcPlayer.pause();
                item.setIcon(R.drawable.ic_play_24);
            }
            return true;
        } else if (itemId == R.id.download) {
            if (!vlcPlayer.isPaused()) {
                vlcPlayer.pause();
                item.setIcon(R.drawable.ic_play_24);
            }
            Toast.makeText(getApplicationContext(), R.string.downloading, LENGTH_LONG).show();
            String start = Time.MStoRFC3339(timelineView.getCurrent());
            downloadPlayback(start, 30.0);
            return true;
        } else if (itemId == R.id.increase_scale_action) {
            timelineView.increaseIntervalWithAnimation();
            return true;
        } else if (itemId == R.id.decrease_scale_action) {
            timelineView.decreaseIntervalWithAnimation();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        setOrientationLayout(newConfig.orientation);
    }

    private void setOrientationLayout(int orientation) {
        Toolbar videoToolbar = findViewById(R.id.playback_toolbar);
        FrameLayout frameLayout = findViewById(R.id.frameLayout);
        FrameLayout timelineFrame = findViewById(R.id.timeline_frame);

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            videoToolbar.setVisibility(VISIBLE);
            timelineFrame.setVisibility(VISIBLE);

            int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
            int videoWidth = currentCamera.getWidth();
            int videoHeight = currentCamera.getHeight();

            frameLayout.getLayoutParams().width = screenWidth;
            frameLayout.getLayoutParams().height = screenWidth * videoHeight / videoWidth;
        }
        else {
            videoToolbar.setVisibility(GONE);
            timelineFrame.setVisibility(GONE);

            frameLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0.0F));
        }
    }

    private void startPlayback(String startRFC333, double duration) {
        String playbackUrl = ApiClient.getInstance().getPlaybackUrl(currentCamera.getId(), startRFC333, duration);

        vlcPlayer.playUri(Uri.parse(playbackUrl));
    }

    private void downloadPlayback(String startRFC333, double duration) {
        ApiClient apiClient = ApiClient.getInstance();
        ApiService apiService = ApiClient.getApiService();

        Call<ResponseBody> recordingCall = apiService.recording(apiClient.getAuthorization(), currentCamera.getId(), startRFC333, duration);
        recordingCall.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (!response.isSuccessful()) {
                    Toast.makeText(PlaybackActivity.this, R.string.error_download, LENGTH_LONG).show();
                    return;
                }

                String dateTime = Time.MStoFriendlyURL(timelineView.getCurrent());
                File downloadFile = Download.getDownloadFile(currentCamera.getId(), dateTime,"mp4");
                try {
                    Download.writeFile(response.body().bytes(), downloadFile);
                    Download.share(PlaybackActivity.this, Uri.parse(downloadFile.getPath()), currentCamera.getName(), "video/mp4");
                } catch (IOException e) {
                    Toast.makeText(PlaybackActivity.this, R.string.error_download, LENGTH_LONG).show();
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable throwable) {
                Toast.makeText(PlaybackActivity.this, R.string.error_download, LENGTH_LONG).show();
            }
        });
    }

    private void initializeRecordings() {
        ApiClient apiClient = ApiClient.getInstance();
        ApiService apiService = ApiClient.getApiService();

        Call<List<Recording>> playbackCall = apiService.recordings(apiClient.getAuthorization(), currentCamera.getId());
        playbackCall.enqueue(new Callback<List<Recording>>() {
            @Override
            public void onResponse(Call<List<Recording>> call, Response<List<Recording>> response) {
                recordings = response.body();

                Log.i(TAG, "Recording list size: " + recordings.size());

                TimeRecord timeRecord;
                ArrayList<TimeRecord> recordsBackgroundEvents = new ArrayList<TimeRecord>();
                for (Recording recording: recordings) {
                    long startMs = Time.RFC3339toMS(recording.getStart());
                    long durationMs = (long) (recording.getDuration() * 1000L);
                    timeRecord = new TimeRecord(startMs, durationMs, recording);
                    recordsBackgroundEvents.add(timeRecord);
                }

                // Fake events to test the widget
                ArrayList<TimeRecord> recordsMajor1Events = new ArrayList<TimeRecord>();
                for (Recording recording: recordings) {
                    long startMs = Time.RFC3339toMS(recording.getStart());
                    timeRecord = new TimeRecord(startMs, 60*1000L, recording);
                    recordsMajor1Events.add(timeRecord);
                }

                timelineView.setBackgroundRecords(recordsBackgroundEvents);
                timelineView.setMajor1Records(recordsMajor1Events);

                findViewById(R.id.timeline_frame).setVisibility(VISIBLE);

                timelineView.setCurrent(System.currentTimeMillis() - 5*1000L);
            }
            @Override
            public void onFailure(Call<List<Recording>> call, Throwable throwable) {
                Toast.makeText(PlaybackActivity.this, R.string.error_get_recordings, LENGTH_LONG).show();
                Log.e(TAG, throwable.toString());
            }
        });
    }
}