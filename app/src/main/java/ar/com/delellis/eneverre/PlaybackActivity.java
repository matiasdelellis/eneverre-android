package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static com.alexvas.widget.TimelineView.INTERVAL_HOUR_6;

import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.alexvas.widget.TimelineView;

import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.Recording;
import ar.com.delellis.eneverre.player.VlcPlayer;
import ar.com.delellis.eneverre.util.Time;
import ar.com.delellis.eneverre.util.VideoTouchListener;

import com.alexvas.widget.TimelineView.TimeRecord;

public class PlaybackActivity extends AppCompatActivity {

    private static final String TAG = "PlaybackActivity";

    private VlcPlayer vlcPlayer = null;
    private VLCVideoLayout vlcVideoLayout = null;

    private TimelineView timelineView = null;

    private Camera currentCamera = null;

    private long lastTimeSelected = 0L;
    private long lastLength = 0L;
    private boolean timelineSelecting = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playback);

        Intent intent = getIntent();
        currentCamera = (Camera) intent.getSerializableExtra(CamerasActivity.CURRENT_CAMERA_DATA);
        List<Recording> recordings = (List<Recording>) intent.getSerializableExtra(VideoActivity.PLAYBACK_LIST_DATA);

        TimeRecord timeRecord = null;
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

        Toolbar videoToolbar = (Toolbar) findViewById(R.id.playback_toolbar);
        setSupportActionBar(videoToolbar);

        int orientation = getResources().getConfiguration().orientation;
        setOrientationLayout (orientation);

        timelineView = findViewById(R.id.timeline_view);
        timelineView.setInterval(INTERVAL_HOUR_6);

        timelineView.setBackgroundRecords(recordsBackgroundEvents);
        timelineView.setMajor1Records(recordsMajor1Events);

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

        timelineView.setCurrentWithAnimation(System.currentTimeMillis() - 5*1000L);
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
        Toolbar videoToolbar = (Toolbar) findViewById(R.id.playback_toolbar);
        FrameLayout frameLayout = findViewById(R.id.frameLayout);
        TimelineView timelineView = findViewById(R.id.timeline_view);

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            videoToolbar.setVisibility(VISIBLE);
            timelineView.setVisibility(VISIBLE);

            int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
            int videoWidth = currentCamera.getWidth();
            int videoHeight = currentCamera.getHeight();

            frameLayout.getLayoutParams().width = screenWidth;
            frameLayout.getLayoutParams().height = screenWidth * videoHeight / videoWidth;
        }
        else {
            videoToolbar.setVisibility(GONE);
            timelineView.setVisibility(GONE);

            frameLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0.0F));
        }
    }

    private void startPlayback(String startTime, double duration) {
        String playbackUrl = ApiClient.getInstance().getPlaybackUrl(currentCamera.getId(), startTime, duration);

        vlcPlayer.playUri(Uri.parse(playbackUrl));
    }
}