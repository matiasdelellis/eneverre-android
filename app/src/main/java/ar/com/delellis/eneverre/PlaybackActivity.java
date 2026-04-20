package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;

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

import com.google.android.material.floatingactionbutton.FloatingActionButton;

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
import ar.com.delellis.eneverre.util.AppPreferences;
import ar.com.delellis.eneverre.util.DateTimePickerDialog;
import ar.com.delellis.eneverre.util.Download;
import ar.com.delellis.eneverre.util.Time;
import ar.com.delellis.eneverre.util.VideoTouchListener;
import ar.com.delellis.eneverre.widget.TimelineView;
import ar.com.delellis.eneverre.widget.TimelineView.TimeRecord;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class PlaybackActivity extends AppCompatActivity {

    public static final int INTENT_PLAYBACK_VIEW = 200;

    private static final String TAG = "PlaybackActivity";

    private VlcPlayer vlcPlayer = null;
    private VLCVideoLayout vlcVideoLayout = null;

    private TimelineView timelineView = null;

    private Camera currentCamera = null;

    private List<Recording> recordings = null;

    private long lastTimeSelected = 0L;
    private long lastLength = 0L;
    private boolean timelineSelecting = false;
    private long lastOldRecording = -1L;

    private long startRecord = -1;

    private AppPreferences prefs = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playback);

        Intent intent = getIntent();
        currentCamera = (Camera) intent.getSerializableExtra(LiveViewActivity.CURRENT_CAMERA_DATA);

        Toolbar videoToolbar = findViewById(R.id.playback_toolbar);
        setSupportActionBar(videoToolbar);

        int orientation = getResources().getConfiguration().orientation;
        setOrientationLayout (orientation);

        timelineView = findViewById(R.id.timeline_view);

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

                if (startRecord > 0) {
                    ArrayList<TimeRecord> fakeRecordingEvents = new ArrayList<TimeRecord>();
                    long duration = newTime - startRecord - 100L;
                    TimeRecord recordEvent = new TimeRecord(startRecord, duration, null);
                    fakeRecordingEvents.add(recordEvent);

                    timelineView.setMajor1Records(fakeRecordingEvents);
                }
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

        findViewById(R.id.record_button).setOnClickListener(v -> {
            FloatingActionButton fab = (FloatingActionButton) v;
            if (startRecord < 0) {
                startRecord = timelineView.getCurrent();

                fab.setImageResource(R.drawable.ic_stop_circle_24);
                Toast.makeText(this, getString(R.string.starting_recording), LENGTH_SHORT).show();
            } else {
                long stopRecord = timelineView.getCurrent();

                String startDownload = Time.MStoRFC3339(startRecord);
                double duration = (double) (stopRecord - startRecord) / 1000.0;

                downloadPlayback(startDownload, duration);
                Toast.makeText(this, R.string.downloading_recording, LENGTH_LONG).show();

                fab.setImageResource(R.drawable.ic_screen_record_24);
                ArrayList<TimeRecord> fakeRecordingEvents = new ArrayList<TimeRecord>();
                timelineView.setMajor1Records(fakeRecordingEvents);
                startRecord = -1L;
            }
        });

        prefs = AppPreferences.getInstance(this);
        if (prefs.isGlobalMute()) {
            vlcPlayer.mute(true);
        }

        timelineView.setOnTimelineListener(new TimelineView.OnTimelineListener() {
            @Override
            public void onTimeSelecting() {
                Log.d(TAG, "onTimeSelecting");
                timelineSelecting = true;
            }

            @Override
            public void onTimeSelected(long l, @Nullable TimeRecord timeRecord) {
                Log.i(TAG, "onTimeSelected: " + Time.MStoFriendlyURL(l));
                if (timeRecord == null)  {
                    Toast.makeText(PlaybackActivity.this, R.string.there_is_no_recording, LENGTH_LONG).show();
                    return;
                }

                timelineSelecting = false;
                lastTimeSelected = l;

                startPlayback(Time.MStoRFC3339(lastTimeSelected), 10.0);
            }

            @Override
            public void onRequestMoreBackgroundData() {
                Log.d(TAG, "onRequestMoreBackgroundData");

                Toast.makeText(PlaybackActivity.this, R.string.looking_for_previous_recordings, LENGTH_SHORT).show();

                long since = lastOldRecording - (24 * 60 * 60 * 1000L);
                getRecordings(since, lastOldRecording, -1);
            }

            @Override
            public void onRequestMoreMajor1Data() {
                Log.d(TAG, "onRequestMoreMajor1Data");
                // Nop...
            }

            @Override
            public void onRequestMoreMajor2Data() {
                Log.d(TAG, "onRequestMoreMajor1Data");
                // Nop...
            }
        });

        findViewById(R.id.timeline_frame).setVisibility(GONE);

        long now = System.currentTimeMillis();
        long yesterday = now - (24 * 60 * 60 * 1000L);
        long select = now - (5 * 1000L);

        getRecordings(yesterday, now, select);
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
        Intent intent = new Intent();
        setResult(RESULT_OK, intent);
        finish();
        return true;
    }

    @Override
    public void onBackPressed() {
        Intent intent = new Intent();
        setResult(RESULT_OK, intent);
        finish();

        super.onBackPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.playback_top_app_bar, menu);
        if (prefs.isGlobalMute()) {
            menu.findItem(R.id.volume_action).setIcon(R.drawable.ic_muted_24);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.volume_action) {
            if (vlcPlayer.isMuted()) {
                vlcPlayer.mute(false);
                prefs.setGlobalMute(false);
                item.setIcon(R.drawable.ic_volume_24);
            } else {
                vlcPlayer.mute(true);
                prefs.setGlobalMute(true);
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
        } else if (itemId == R.id.go_to_datetime) {
            DateTimePickerDialog.show(this, calendar -> {
                timelineSelecting = false;
                lastTimeSelected = calendar.getTimeInMillis();
                timelineView.setCurrent(lastTimeSelected);

                String start = Time.MStoRFC3339(lastTimeSelected);
                startPlayback(start, 30.0);
            });
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

    private void getRecordings(long since, long to, long selectTime) {
        ApiClient apiClient = ApiClient.getInstance();
        ApiService apiService = ApiClient.getApiService();

        Call<List<Recording>> playbackCall = apiService.recordings(apiClient.getAuthorization(), currentCamera.getId(), Time.MStoRFC3339(since), Time.MStoRFC3339(to));
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
                timelineView.addBackgroundRecordsAtStart(recordsBackgroundEvents);

                // Fake events to test the widget
//                ArrayList<TimeRecord> recordsMajor1Events = new ArrayList<TimeRecord>();
//                for (Recording recording: recordings) {
//                    long startMs = Time.RFC3339toMS(recording.getStart());
//                    timeRecord = new TimeRecord(startMs, 60*1000L, recording);
//                    recordsMajor1Events.add(timeRecord);
//                }

                //timelineView.setMajor1Records(recordsMajor1Events);

                Recording firstRecording = recordings.get(0);
                lastOldRecording = Time.RFC3339toMS(firstRecording.getStart());

                findViewById(R.id.timeline_frame).setVisibility(VISIBLE);

                if (selectTime > 0)  {
                    timelineView.setCurrent(selectTime);
                }
            }

            @Override
            public void onFailure(Call<List<Recording>> call, Throwable throwable) {
                Toast.makeText(PlaybackActivity.this, R.string.error_get_recordings, LENGTH_LONG).show();
                Log.e(TAG, throwable.toString());
            }
        });
    }
}