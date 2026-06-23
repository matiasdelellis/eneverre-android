package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ar.com.delellis.eneverre.adapter.EventsAdapter;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.Event;
import ar.com.delellis.eneverre.api.model.EventsResponse;
import ar.com.delellis.eneverre.api.model.Recording;
import ar.com.delellis.eneverre.player.VlcPlayer;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.ApiError;
import ar.com.delellis.eneverre.util.AppPreferences;
import ar.com.delellis.eneverre.util.DateTimePickerDialog;
import ar.com.delellis.eneverre.util.Download;
import ar.com.delellis.eneverre.util.Snapshot;
import ar.com.delellis.eneverre.util.Time;
import ar.com.delellis.eneverre.util.VideoTouchListener;
import ar.com.delellis.eneverre.widget.TimelineView;
import ar.com.delellis.eneverre.widget.TimelineView.TimeRecord;
import okhttp3.ResponseBody;

/**
 * "Playback" tab of {@link ViewActivity}: timeline-driven recording playback
 * for a single camera. The host adds this fragment fresh when the tab is opened
 * and removes it when leaving, so the native player is released between visits.
 */
public class PlaybackFragment extends Fragment {

    private static final String TAG = "PlaybackFragment";
    private static final String ARG_CAMERA = "camera";

    private View root;

    private VlcPlayer vlcPlayer = null;
    private VLCVideoLayout vlcVideoLayout = null;

    private TimelineView timelineView = null;

    private Camera currentCamera = null;

    private List<Recording> recordings = null;

    private EventsAdapter eventsAdapter = null;
    private final List<Event> events = new ArrayList<>();
    private final Set<Long> eventIds = new HashSet<>();
    private boolean hasEvents = false;
    private boolean eventsLoaded = false;
    private boolean eventsLoading = false;

    private long lastTimeSelected = 0L;
    private long lastLength = 0L;
    private boolean timelineSelecting = false;
    private long lastOldRecording = -1L;

    private long startRecord = -1;

    /** True only while this page is the resumed (visible) one, so neighbour pages don't play. */
    private boolean viewResumed = false;

    private AppPreferences prefs = null;

    public static PlaybackFragment newInstance(Camera camera) {
        PlaybackFragment fragment = new PlaybackFragment();
        Bundle args = new Bundle();
        args.putSerializable(ARG_CAMERA, camera);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentCamera = (Camera) requireArguments().getSerializable(ARG_CAMERA);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_playback, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        root = view;

        prefs = AppPreferences.getInstance(requireContext());

        setOrientationLayout(getResources().getConfiguration().orientation);

        timelineView = view.findViewById(R.id.timeline_view);

        vlcVideoLayout = view.findViewById(R.id.vlc_playback_layout);
        VideoTouchListener touchListener = new VideoTouchListener(vlcVideoLayout);
        // Press and hold on the video to fast-forward at 2x; release to resume 1x.
        touchListener.setOnLongPressListener(new VideoTouchListener.OnLongPressListener() {
            @Override
            public void onLongPressStart() {
                if (vlcPlayer != null) {
                    vlcPlayer.setRate(2.0f);
                    root.findViewById(R.id.speed_badge).setVisibility(VISIBLE);
                }
            }

            @Override
            public void onLongPressEnd() {
                if (vlcPlayer != null) {
                    vlcPlayer.setRate(1.0f);
                    root.findViewById(R.id.speed_badge).setVisibility(GONE);
                }
            }
        });
        vlcVideoLayout.setOnTouchListener(touchListener);

        vlcPlayer = new VlcPlayer(requireContext());
        vlcPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.Buffering) {
                if (event.getBuffering() == 100f) {
                    root.findViewById(R.id.loading_progress).setVisibility(GONE);
                } else {
                    root.findViewById(R.id.loading_progress).setVisibility(VISIBLE);
                }
            } else if (event.type == MediaPlayer.Event.PositionChanged) {
                if (timelineSelecting) return;
                long newTime = lastTimeSelected + (long) (lastLength * event.getPositionChanged());
                timelineView.setCurrentWithAnimation(newTime);

                // Keep the shared moment current so swiping to another camera
                // resumes here regardless of pause/resume ordering.
                if (viewResumed) {
                    setSharedPlaybackTime(newTime);
                }

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
                root.findViewById(R.id.loading_progress).setVisibility(GONE);
            }
        });

        view.findViewById(R.id.record_button).setOnClickListener(v -> {
            FloatingActionButton fab = (FloatingActionButton) v;
            if (startRecord < 0) {
                startRecord = timelineView.getCurrent();

                fab.setImageResource(R.drawable.ic_stop_circle_24);
                int color = ContextCompat.getColor(fab.getContext(), R.color.record_red);
                fab.setImageTintList(ColorStateList.valueOf(color));

                Toast.makeText(requireContext(), getString(R.string.starting_recording), LENGTH_SHORT).show();
            } else {
                long stopRecord = timelineView.getCurrent();

                String startDownload = Time.MStoRFC3339(startRecord);
                double duration = (double) (stopRecord - startRecord) / 1000.0;

                downloadPlayback(startDownload, duration);
                Toast.makeText(requireContext(), R.string.downloading_recording, LENGTH_LONG).show();

                fab.setImageResource(R.drawable.ic_video_cam_24);
                int color = MaterialColors.getColor(
                        fab,
                        com.google.android.material.R.attr.colorOnSecondaryContainer);
                fab.setImageTintList(ColorStateList.valueOf(color));

                ArrayList<TimeRecord> fakeRecordingEvents = new ArrayList<TimeRecord>();
                timelineView.setMajor1Records(fakeRecordingEvents);
                startRecord = -1L;
            }
        });

        view.findViewById(R.id.take_snapshot).setOnClickListener(v -> takeSnapshot());

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
                if (timeRecord == null) {
                    Toast.makeText(requireContext(), R.string.there_is_no_recording, LENGTH_LONG).show();
                    return;
                }

                timelineSelecting = false;
                lastTimeSelected = l;
                setSharedPlaybackTime(l);

                startPlayback(Time.MStoRFC3339(lastTimeSelected), 10.0);
            }

            @Override
            public void onRequestMoreBackgroundData() {
                Log.d(TAG, "onRequestMoreBackgroundData");

                Toast.makeText(requireContext(), R.string.looking_for_previous_recordings, LENGTH_SHORT).show();

                long since = lastOldRecording - (24 * 60 * 60 * 1000L);
                getRecordings(since, lastOldRecording, -1);
                getEvents(since, lastOldRecording);
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

        eventsAdapter = new EventsAdapter(requireContext(), (event, startMsec) -> seekToEvent(startMsec));
        RecyclerView eventsRecycler = view.findViewById(R.id.events_recycler);
        eventsRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        eventsRecycler.setAdapter(eventsAdapter);

        requireActivity().addMenuProvider(playbackMenu, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        long now = System.currentTimeMillis();
        long yesterday = now - (24 * 60 * 60 * 1000L);
        long shared = sharedPlaybackTime();
        long select = shared > 0 ? shared : now - (5 * 1000L);

        // When swiping in from another camera, position the timeline at the
        // shared moment right away (instead of "now") so cameras stay in sync
        // even before recordings finish loading.
        if (shared > 0) {
            timelineView.setCurrent(shared);
        }

        getRecordings(yesterday, now, select);
        getEvents(yesterday, now);
    }

    @Override
    public void onResume() {
        super.onResume();
        viewResumed = true;

        vlcPlayer.detachViews();
        vlcPlayer.attachView(vlcVideoLayout);

        // Resume at the time shared by the previous camera (so swiping cameras
        // keeps the same moment), falling back to this page's last position.
        // On the very first open it stays idle until the user picks a moment.
        long seek = sharedPlaybackTime();
        if (seek <= 0) {
            seek = lastTimeSelected;
        }
        if (seek > 0) {
            lastTimeSelected = seek;
            if (timelineView != null) {
                timelineView.setCurrent(seek);
            }
            startPlayback(Time.MStoRFC3339(seek), 10.0);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        viewResumed = false;

        // Hand the current moment to the sibling cameras before leaving.
        if (timelineView != null) {
            long current = timelineView.getCurrent();
            if (current > 0) {
                setSharedPlaybackTime(current);
            }
        }

        vlcPlayer.stop();
        vlcPlayer.detachViews();
    }

    private PlaybackTimeHost timeHost() {
        Fragment parent = getParentFragment();
        return (parent instanceof PlaybackTimeHost) ? (PlaybackTimeHost) parent : null;
    }

    private long sharedPlaybackTime() {
        PlaybackTimeHost host = timeHost();
        return host != null ? host.getSharedPlaybackTime() : 0;
    }

    private void setSharedPlaybackTime(long timeMs) {
        PlaybackTimeHost host = timeHost();
        if (host != null) {
            host.setSharedPlaybackTime(timeMs);
        }
    }

    @Override
    public void onDestroyView() {
        if (vlcPlayer != null) {
            vlcPlayer.release();
            vlcPlayer = null;
        }
        super.onDestroyView();
    }

    private final MenuProvider playbackMenu = new MenuProvider() {
        @Override
        public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
            inflater.inflate(R.menu.playback_top_app_bar, menu);
            if (prefs.isGlobalMute()) {
                menu.findItem(R.id.volume_action).setIcon(R.drawable.ic_muted_24);
            }
        }

        @Override
        public boolean onMenuItemSelected(@NonNull MenuItem item) {
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
                DateTimePickerDialog.show(requireContext(), calendar -> {
                    timelineSelecting = false;
                    lastTimeSelected = calendar.getTimeInMillis();
                    timelineView.setCurrent(lastTimeSelected);
                    timelineView.invalidate();

                    String start = Time.MStoRFC3339(lastTimeSelected);
                    startPlayback(start, 30.0);
                });
                return true;
            }
            return false;
        }
    };

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setOrientationLayout(newConfig.orientation);
    }

    private void setOrientationLayout(int orientation) {
        FrameLayout frameLayout = root.findViewById(R.id.frameLayout);
        FrameLayout timelineFrame = root.findViewById(R.id.timeline_frame);

        updateEventsPanelVisibility(orientation);

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            timelineFrame.setVisibility(VISIBLE);

            int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
            int videoWidth = currentCamera.getWidth();
            int videoHeight = currentCamera.getHeight();

            frameLayout.getLayoutParams().width = screenWidth;
            frameLayout.getLayoutParams().height = screenWidth * videoHeight / videoWidth;
        } else {
            timelineFrame.setVisibility(GONE);

            frameLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0.0F));
        }
    }

    private void startPlayback(String startRFC333, double duration) {
        // Never play from a non-visible page (a neighbour in the pager).
        if (vlcPlayer == null || !viewResumed) {
            return;
        }
        String playbackUrl = ApiClient.getInstance().getPlaybackUrl(currentCamera.getId(), startRFC333, duration);

        vlcPlayer.playUri(android.net.Uri.parse(playbackUrl));
    }

    private void downloadPlayback(String startRFC333, double duration) {
        ApiClient.getApiService().recording(currentCamera.getId(), startRFC333, duration).enqueue(new ApiCallback<ResponseBody>(requireContext()) {
            @Override
            public void onSuccess(ResponseBody body) {
                if (body == null) {
                    onError(ApiError.NO_HTTP_CODE, null);
                    return;
                }

                String dateTime = Time.MStoFriendlyURL(timelineView.getCurrent());
                String fileName = Download.buildFileName(currentCamera.getId(), dateTime, "mp4");
                Download.saveClipAndShare(requireActivity(), body, fileName, currentCamera.getName(), null, (long) duration);
            }

            @Override
            public void onError(int httpCode, String message) {
                Toast.makeText(requireContext(), R.string.error_download, LENGTH_LONG).show();
            }
        });
    }

    private void getRecordings(long since, long to, long selectTime) {
        ApiClient.getApiService().recordings(currentCamera.getId(), Time.MStoRFC3339(since), Time.MStoRFC3339(to)).enqueue(new ApiCallback<List<Recording>>(requireContext()) {
            @Override
            public void onSuccess(List<Recording> body) {
                recordings = body;
                if (recordings == null || recordings.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.there_is_no_recording, LENGTH_LONG).show();
                    return;
                }

                Log.i(TAG, "Recording list size: " + recordings.size());

                TimeRecord timeRecord;
                ArrayList<TimeRecord> recordsBackgroundEvents = new ArrayList<TimeRecord>();
                for (Recording recording : recordings) {
                    long startMs = Time.RFC3339toMS(recording.getStart());
                    long durationMs = (long) (recording.getDuration() * 1000L);
                    timeRecord = new TimeRecord(startMs, durationMs, recording);
                    recordsBackgroundEvents.add(timeRecord);
                }
                timelineView.addBackgroundRecordsAtStart(recordsBackgroundEvents);

                Recording firstRecording = recordings.get(0);
                lastOldRecording = Time.RFC3339toMS(firstRecording.getStart());

                if (selectTime > 0) {
                    timelineView.setCurrent(selectTime);
                }
            }

            @Override
            public void onError(int httpCode, String message) {
                Toast.makeText(requireContext(), R.string.error_get_recordings, LENGTH_LONG).show();
            }
        });
    }

    private void getEvents(long since, long until) {
        eventsLoading = true;
        updateEventsPanelVisibility(getResources().getConfiguration().orientation);
        ApiClient.getApiService().events(currentCamera.getId(), Time.MStoRFC3339(since), Time.MStoRFC3339(until)).enqueue(new ApiCallback<EventsResponse>(requireContext()) {
            @Override
            public void onSuccess(EventsResponse body) {
                eventsLoading = false;
                if (body == null || body.getEvents() == null) {
                    eventsLoaded = true;
                    updateEventsPanelVisibility(getResources().getConfiguration().orientation);
                    return;
                }

                for (Event event : body.getEvents()) {
                    if (eventIds.add(event.getId())) {
                        events.add(event);
                    }
                }

                // Newest first for the list.
                Collections.sort(events, (a, b) -> Long.compare(
                        Time.RFC3339toMS(b.getStartTs()), Time.RFC3339toMS(a.getStartTs())));

                Log.i(TAG, "Event list size: " + events.size());

                eventsAdapter.updateEvents(events);
                renderEventsOnTimeline();

                eventsLoaded = true;
                hasEvents = !events.isEmpty();
                updateEventsPanelVisibility(getResources().getConfiguration().orientation);
            }

            @Override
            public void onError(int httpCode, String message) {
                eventsLoading = false;
                eventsLoaded = true;
                updateEventsPanelVisibility(getResources().getConfiguration().orientation);
                Toast.makeText(requireContext(), R.string.error_get_events, LENGTH_LONG).show();
            }
        });
    }

    private void renderEventsOnTimeline() {
        ArrayList<TimeRecord> eventRecords = new ArrayList<TimeRecord>();
        for (Event event : events) {
            long startMs = Time.RFC3339toMS(event.getStartTs());
            if (startMs <= 0) {
                continue;
            }
            long endMs = Time.RFC3339toMS(event.getEndTs());
            long durationMs = endMs > startMs ? endMs - startMs : 0;
            eventRecords.add(new TimeRecord(startMs, durationMs, event));
        }
        timelineView.setMajor2Records(eventRecords);
        timelineView.invalidate();
    }

    private void seekToEvent(long timeMs) {
        timelineSelecting = false;
        lastTimeSelected = timeMs;
        if (timelineView != null) {
            timelineView.setCurrent(timeMs);
            timelineView.invalidate();
        }
        setSharedPlaybackTime(timeMs);
        startPlayback(Time.MStoRFC3339(timeMs), 30.0);
    }

    private void updateEventsPanelVisibility(int orientation) {
        boolean showPanel = orientation == Configuration.ORIENTATION_PORTRAIT && (eventsLoading || eventsLoaded);
        root.findViewById(R.id.events_panel).setVisibility(showPanel ? VISIBLE : GONE);

        // Within the panel, show the spinner while loading, then swap between
        // the list and the empty state.
        root.findViewById(R.id.events_loading).setVisibility(eventsLoading ? VISIBLE : GONE);
        root.findViewById(R.id.events_recycler).setVisibility(!eventsLoading && hasEvents ? VISIBLE : GONE);
        root.findViewById(R.id.events_empty).setVisibility(!eventsLoading && !hasEvents ? VISIBLE : GONE);
    }

    private void takeSnapshot() {
        SurfaceView surfaceView = root.findViewById(org.videolan.R.id.surface_video);
        Snapshot.getSurfaceBitmap(surfaceView, new Snapshot.PixelCopyListener() {
            @Override
            public void onSurfaceBitmapReady(android.graphics.Bitmap bitmap) {
                String fileName = Download.buildFileName(currentCamera.getName(), Time.MStoFriendlyURL(System.currentTimeMillis()), "png");
                Download.saveSnapshotAndShare(requireActivity(), bitmap, fileName, currentCamera.getName());
            }

            @Override
            public void onSurfaceBitmapError(int errorCode) {
                Toast.makeText(requireContext(), R.string.error_snapshot, LENGTH_LONG).show();
            }
        });
    }
}
