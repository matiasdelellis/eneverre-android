package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;

import android.content.Intent;
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
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ar.com.delellis.eneverre.adapter.EventsAdapter;
import ar.com.delellis.eneverre.adapter.OnEventClickListener;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.Event;
import ar.com.delellis.eneverre.api.model.EventsResponse;
import ar.com.delellis.eneverre.api.model.Recording;
import ar.com.delellis.eneverre.player.Media3Player;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.ApiError;
import ar.com.delellis.eneverre.util.AppPreferences;
import ar.com.delellis.eneverre.util.DateTimePickerDialog;
import ar.com.delellis.eneverre.util.Download;
import ar.com.delellis.eneverre.util.EventShareLink;
import ar.com.delellis.eneverre.util.SecureStore;
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

    private Media3Player player = null;
    private PlayerView playerView = null;

    /**
     * Full chunk plan for the current playback session: absolute start (ms) and
     * duration (ms) of every chunk from the chosen moment forward. Cheap to hold
     * (two longs each); chunks are turned into player {@link MediaItem}s lazily.
     */
    private long[] chunkStartsMs = null;
    private long[] chunkDursMs = null;
    /** How many of the planned chunks have actually been queued into the player. */
    private int enqueuedCount = 0;
    /** Moment to start playing once recordings finish loading (swipe-in resume). */
    private long pendingSeekMs = 0L;

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

        playerView = view.findViewById(R.id.player_view);
        playerView.setUseController(false);
        VideoTouchListener touchListener = new VideoTouchListener();
        // Press and hold on the video to fast-forward at 2x; release to resume 1x.
        touchListener.setOnLongPressListener(new VideoTouchListener.OnLongPressListener() {
            @Override
            public void onLongPressStart() {
                if (player != null) {
                    player.setRate(2.0f);
                    root.findViewById(R.id.speed_badge).setVisibility(VISIBLE);
                }
            }

            @Override
            public void onLongPressEnd() {
                if (player != null) {
                    player.setRate(1.0f);
                    root.findViewById(R.id.speed_badge).setVisibility(GONE);
                }
            }
        });
        playerView.setOnTouchListener(touchListener);

        player = new Media3Player(requireContext(), ApiClient.getInstance().getAuthorizationHeader());
        player.setListener(new Media3Player.Listener() {
            @Override
            public void onBuffering(boolean buffering) {
                root.findViewById(R.id.loading_progress).setVisibility(buffering ? VISIBLE : GONE);
            }

            @Override
            public void onProgress(int itemIndex, long positionMs) {
                if (timelineSelecting) return;
                if (chunkStartsMs == null || itemIndex < 0 || itemIndex >= chunkStartsMs.length) return;

                long newTime = chunkStartsMs[itemIndex] + positionMs;
                lastTimeSelected = newTime;
                timelineView.setCurrentWithAnimation(newTime);

                // Follow the event the timeline marks as current (highlight while
                // it plays, clear once playback moves past it).
                syncEventHighlight();

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
            }

            @Override
            public void onItemTransition(int itemIndex) {
                // Crossed into a new chunk: keep the queue topped up so playback
                // continues seamlessly past what's currently enqueued.
                enqueueMoreIfNeeded(itemIndex);
            }

            @Override
            public void onEnded() {
                // Caught up to the end of the loaded recordings.
                root.findViewById(R.id.loading_progress).setVisibility(GONE);
            }

            @Override
            public void onError(@Nullable String message) {
                Log.e(TAG, "Player error (skipping chunk): " + message);
                // The player is about to skip past the failed chunk. If it was the
                // tail of the queue (e.g. the last chunk before a recording gap),
                // append the next recording's chunks first so there IS a next item
                // to skip to — otherwise a transient 502 there would be mistaken
                // for the end of the available footage.
                enqueueNextBatch();
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
            player.mute(true);
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
                syncEventHighlight();

                playFrom(l);
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

        eventsAdapter = new EventsAdapter(requireContext(), new OnEventClickListener() {
            @Override
            public void onEventClick(Event event, long startMsec) {
                seekToEvent(event, startMsec);
            }

            @Override
            public void onEventLongClick(View anchor, Event event, long startMsec) {
                showEventOptions(anchor, event, startMsec);
            }
        });
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

        player.attachView(playerView);

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
            // Recordings may not be loaded yet on a fresh swipe-in; if so defer
            // playback until getRecordings() finishes (see pendingSeekMs).
            if (hasBackgroundRecords()) {
                playFrom(seek);
            } else {
                pendingSeekMs = seek;
            }
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

        player.stop();
        player.detachView(playerView);
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
        if (player != null) {
            player.release();
            player = null;
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
            // Sharing is only meaningful on a fixed https build (see shareMoment).
            if (!BuildConfig.API_HOST.startsWith("https://")) {
                menu.findItem(R.id.share_moment).setVisible(false);
            }
        }

        @Override
        public boolean onMenuItemSelected(@NonNull MenuItem item) {
            int itemId = item.getItemId();
            if (itemId == R.id.volume_action) {
                if (player.isMuted()) {
                    player.mute(false);
                    prefs.setGlobalMute(false);
                    item.setIcon(R.drawable.ic_volume_24);
                } else {
                    player.mute(true);
                    prefs.setGlobalMute(true);
                    item.setIcon(R.drawable.ic_muted_24);
                }
                return true;
            } else if (itemId == R.id.pause_action) {
                if (player.isPaused()) {
                    player.resume();
                    item.setIcon(R.drawable.ic_pause_24);
                } else {
                    player.pause();
                    item.setIcon(R.drawable.ic_play_24);
                }
                return true;
            } else if (itemId == R.id.go_to_datetime) {
                DateTimePickerDialog.show(requireContext(), calendar -> {
                    timelineSelecting = false;
                    lastTimeSelected = calendar.getTimeInMillis();
                    timelineView.setCurrent(lastTimeSelected);
                    timelineView.invalidate();
                    syncEventHighlight();

                    playFrom(lastTimeSelected);
                });
                return true;
            } else if (itemId == R.id.share_moment) {
                shareMoment(timelineView.getCurrent());
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

    private boolean hasBackgroundRecords() {
        return timelineView != null
                && timelineView.getBackgroundRecords() != null
                && !timelineView.getBackgroundRecords().isEmpty();
    }

    /** Each queued playback request spans at most this many ms. */
    private static final long CHUNK_MS = 10_000L;
    /** Chunks queued into the player up front when playback starts. */
    private static final int INITIAL_CHUNKS = 18; // ~3 min
    /** Chunks appended each time the queue is topped up. */
    private static final int BATCH_CHUNKS = 12; // ~2 min
    /** Top up the queue once playback gets this close to its tail. */
    private static final int PREFETCH_THRESHOLD = 6; // ~1 min ahead

    /**
     * Starts (gapless) playback from {@code timeMs}: builds a chunk plan over the
     * loaded recordings (from that moment forward), queues the first chunks, and
     * lets ExoPlayer transition through them with no gap. The rest of the plan is
     * appended on the fly as playback advances (see {@link #enqueueMoreIfNeeded}),
     * so the live playlist stays small regardless of how long the span is.
     *
     * <p>Replaces VLC's fixed-window + restart-on-EndReached approach, which tore
     * down and rebuilt the pipeline at each boundary (the source of the gaps).
     * Chunks are kept small because the backend generates each clip on demand and
     * 502s on very long playback windows; pre-queuing them is what keeps playback
     * gapless despite the small requests.
     */
    private void playFrom(long timeMs) {
        // Never play from a non-visible page (a neighbour in the pager).
        if (player == null || !viewResumed) {
            return;
        }
        ArrayList<TimeRecord> records = (timelineView != null) ? timelineView.getBackgroundRecords() : null;
        if (records == null || records.isEmpty()) {
            return;
        }

        // Recordings ascending by start, so the plan plays forward in time.
        ArrayList<TimeRecord> ordered = new ArrayList<>(records);
        Collections.sort(ordered, (a, b) -> Long.compare(a.timestampMsec, b.timestampMsec));

        // Plan every chunk up front (cheap: just two longs each); MediaItems are
        // built lazily from this plan as the queue is topped up.
        List<Long> starts = new ArrayList<>();
        List<Long> durs = new ArrayList<>();
        for (TimeRecord tr : ordered) {
            long segStart = tr.timestampMsec;
            long segEnd = segStart + tr.durationMsec;
            if (segEnd <= timeMs) {
                continue; // segment entirely before the requested moment
            }
            // First relevant recording starts exactly at timeMs; later ones at
            // their own start (timeMs is already behind them).
            long pos = Math.max(segStart, timeMs);
            while (pos < segEnd) {
                long chunkMs = Math.min(CHUNK_MS, segEnd - pos);
                starts.add(pos);
                durs.add(chunkMs);
                pos += chunkMs;
            }
        }

        if (starts.isEmpty()) {
            Toast.makeText(requireContext(), R.string.there_is_no_recording, LENGTH_LONG).show();
            return;
        }

        chunkStartsMs = new long[starts.size()];
        chunkDursMs = new long[durs.size()];
        for (int i = 0; i < starts.size(); i++) {
            chunkStartsMs[i] = starts.get(i);
            chunkDursMs[i] = durs.get(i);
        }
        enqueuedCount = 0;

        // First chunk starts exactly at timeMs, so no per-item seek is needed.
        int first = Math.min(INITIAL_CHUNKS, chunkStartsMs.length);
        player.setPlaylist(buildItems(0, first), 0, 0);
        enqueuedCount = first;
    }

    /** Builds {@link MediaItem}s for chunk plan indices {@code [from, to)}. */
    private List<MediaItem> buildItems(int from, int to) {
        String camId = currentCamera.getId();
        List<MediaItem> items = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            double durationSec = chunkDursMs[i] / 1000.0;
            String url = ApiClient.getInstance().getPlaybackStreamUrl(camId, Time.MStoRFC3339(chunkStartsMs[i]), durationSec);
            items.add(MediaItem.fromUri(url));
        }
        return items;
    }

    /**
     * Appends the next batch of planned chunks once playback nears the tail of the
     * queue, so it continues seamlessly. No-op once the whole plan is queued.
     */
    private void enqueueMoreIfNeeded(int currentIndex) {
        if (player == null || chunkStartsMs == null || enqueuedCount >= chunkStartsMs.length) {
            return;
        }
        if (currentIndex < enqueuedCount - PREFETCH_THRESHOLD) {
            return; // still comfortably ahead
        }
        enqueueNextBatch();
    }

    /** Appends the next plan batch to the player. No-op once the plan is exhausted. */
    private void enqueueNextBatch() {
        if (player == null || chunkStartsMs == null || enqueuedCount >= chunkStartsMs.length) {
            return;
        }
        int to = Math.min(enqueuedCount + BATCH_CHUNKS, chunkStartsMs.length);
        player.addMediaItems(buildItems(enqueuedCount, to));
        enqueuedCount = to;
    }

    private void downloadPlayback(String startRFC333, double duration) {
        downloadPlayback(startRFC333, duration, timelineView.getCurrent());
    }

    private void downloadPlayback(String startRFC333, double duration, long fileTimeMs) {
        String dateTime = Time.MStoFriendlyURL(fileTimeMs);
        ApiClient.getApiService().recording(currentCamera.getId(), startRFC333, duration).enqueue(new ApiCallback<ResponseBody>(requireContext()) {
            @Override
            public void onSuccess(ResponseBody body) {
                if (body == null) {
                    onError(ApiError.NO_HTTP_CODE, null);
                    return;
                }

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

                // Recordings are now loaded: honour a playback request that
                // arrived before they were available (swipe-in resume).
                if (pendingSeekMs > 0 && viewResumed) {
                    long seek = pendingSeekMs;
                    pendingSeekMs = 0;
                    playFrom(seek);
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

    private void seekToEvent(Event event, long timeMs) {
        timelineSelecting = false;
        lastTimeSelected = timeMs;
        if (timelineView != null) {
            timelineView.setCurrent(timeMs);
            timelineView.invalidate();
        }
        setSharedPlaybackTime(timeMs);
        syncEventHighlight();

        playFrom(timeMs);
    }

    /** Long-press menu for an event row: play from it or download its clip. */
    private void showEventOptions(View anchor, Event event, long startMsec) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.getMenu().add(Menu.NONE, 1, 0, R.string.play);
        popup.getMenu().add(Menu.NONE, 2, 1, R.string.download);
        // Sharing only makes sense on a fixed https build: the link domain is the
        // baked API_HOST, and only an https host is caught back (a verified App
        // Link) — an http link would have no handler and just open the browser.
        if (BuildConfig.API_HOST.startsWith("https://")) {
            popup.getMenu().add(Menu.NONE, 3, 2, R.string.share);
        }
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    seekToEvent(event, startMsec);
                    return true;
                case 2:
                    downloadEvent(event, startMsec);
                    return true;
                case 3:
                    shareMoment(startMsec);
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    /** Shares a link (host + camera + moment) to the given time via any app. */
    private void shareMoment(long timeMs) {
        String host = SecureStore.getInstance(requireContext()).getConfigHost();
        if (host == null || timeMs <= 0) {
            return;
        }

        String link = EventShareLink.build(host, currentCamera.getId(), timeMs);

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, getString(R.string.share_event_message, link));
        startActivity(Intent.createChooser(send, getString(R.string.share)));
    }

    /** Downloads the clip spanning the event (start_ts .. end_ts). */
    private void downloadEvent(Event event, long startMsec) {
        long endMsec = Time.RFC3339toMS(event.getEndTs());
        if (endMsec <= startMsec) {
            Toast.makeText(requireContext(), R.string.there_is_no_recording, LENGTH_LONG).show();
            return;
        }

        double duration = (double) (endMsec - startMsec) / 1000.0;
        downloadPlayback(Time.MStoRFC3339(startMsec), duration, startMsec);
        Toast.makeText(requireContext(), R.string.downloading_recording, LENGTH_LONG).show();
    }

    /**
     * Highlights in the list whichever event the timeline currently sits on
     * (reusing its automatic event selection), or clears it when none. This
     * keeps the highlight on while the event plays and drops it once playback
     * moves past the event.
     */
    private void syncEventHighlight() {
        if (eventsAdapter == null || timelineView == null) {
            return;
        }
        TimeRecord record = timelineView.getCurrentMajor2Record();
        long id = (record != null && record.object instanceof Event)
                ? ((Event) record.object).getId() : -1L;
        eventsAdapter.setHighlightedEventId(id);
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

    @OptIn(markerClass = UnstableApi.class)
    private void takeSnapshot() {
        View videoSurface = playerView.getVideoSurfaceView();
        if (!(videoSurface instanceof SurfaceView)) {
            Toast.makeText(requireContext(), R.string.error_snapshot, LENGTH_LONG).show();
            return;
        }
        SurfaceView surfaceView = (SurfaceView) videoSurface;
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
