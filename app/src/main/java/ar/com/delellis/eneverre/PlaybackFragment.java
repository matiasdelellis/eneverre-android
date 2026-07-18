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
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

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
import ar.com.delellis.eneverre.api.model.RecordingsTimeline;
import ar.com.delellis.eneverre.player.GaplessPlaybackController;
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

    private GaplessPlaybackController controller = null;
    private PlayerView playerView = null;

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

    /**
     * Recorded extent reported by {@code recordings/timeline}: oldest recorded
     * moment and newest, in ms (-1 until loaded). {@link #recordingsStartMs}
     * bounds the backward pagination so we stop requesting empty history.
     */
    private long recordingsStartMs = -1L;
    private long recordingsEndMs = -1L;

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

        // Cameras without playback capability show a "no recordings" empty state
        // instead of the player; nothing else in this fragment is wired up.
        if (!currentCamera.hasPlayback()) {
            showPlaybackUnsupported();
            return;
        }

        setOrientationLayout(getResources().getConfiguration().orientation);

        timelineView = view.findViewById(R.id.timeline_view);

        playerView = view.findViewById(R.id.player_view);
        playerView.setUseController(false);
        VideoTouchListener touchListener = new VideoTouchListener();
        // Press and hold on the video to fast-forward at 2x; release to resume 1x.
        touchListener.setOnLongPressListener(new VideoTouchListener.OnLongPressListener() {
            @Override
            public void onLongPressStart() {
                if (controller != null) {
                    controller.setRate(2.0f);
                    root.findViewById(R.id.speed_badge).setVisibility(VISIBLE);
                }
            }

            @Override
            public void onLongPressEnd() {
                if (controller != null) {
                    controller.setRate(1.0f);
                    root.findViewById(R.id.speed_badge).setVisibility(GONE);
                }
            }
        });
        playerView.setOnTouchListener(touchListener);

        controller = new GaplessPlaybackController(requireContext(),
                ApiClient.getInstance().getAuthorizationHeader(), currentCamera.getId(),
                new GaplessPlaybackController.Listener() {
                    @Override
                    public void onBuffering(boolean buffering) {
                        root.findViewById(R.id.loading_progress).setVisibility(buffering ? VISIBLE : GONE);
                    }

                    @Override
                    public void onRetryableError() {
                        Snackbar.make(root, R.string.error_server, Snackbar.LENGTH_INDEFINITE)
                                .setAction(R.string.retry, v -> {
                                    if (controller != null) {
                                        controller.retry();
                                    }
                                })
                                .show();
                    }

                    @Override
                    public void onPositionMs(long absoluteMs) {
                        if (timelineSelecting) return;

                        lastTimeSelected = absoluteMs;
                        timelineView.setCurrentWithAnimation(absoluteMs);

                        // Follow the event the timeline marks as current (highlight
                        // while it plays, clear once playback moves past it).
                        syncEventHighlight();

                        // Keep the shared moment current so swiping to another camera
                        // resumes here regardless of pause/resume ordering.
                        if (viewResumed) {
                            setSharedPlaybackTime(absoluteMs);
                        }

                        if (startRecord > 0) {
                            ArrayList<TimeRecord> fakeRecordingEvents = new ArrayList<TimeRecord>();
                            long duration = absoluteMs - startRecord - 100L;
                            fakeRecordingEvents.add(new TimeRecord(startRecord, duration, null));
                            timelineView.setMajor1Records(fakeRecordingEvents);
                        }
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
            controller.mute(true);
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

                // Stop paging once we've reached the oldest recorded moment, so
                // we don't fire endless 24h requests into empty history.
                if (recordingsStartMs > 0 && lastOldRecording > 0
                        && lastOldRecording <= recordingsStartMs) {
                    Log.d(TAG, "Reached oldest recording; no more background data");
                    return;
                }

                Toast.makeText(requireContext(), R.string.looking_for_previous_recordings, LENGTH_SHORT).show();

                long since = lastOldRecording - (24 * 60 * 60 * 1000L);
                if (recordingsStartMs > 0 && since < recordingsStartMs) {
                    since = recordingsStartMs;
                }
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

        long shared = sharedPlaybackTime();

        // When swiping in from another camera, position the timeline at the
        // shared moment right away (instead of "now") so cameras stay in sync
        // even before recordings finish loading.
        if (shared > 0) {
            timelineView.setCurrent(shared);
        }

        loadInitialRecordings(shared);
    }

    /**
     * Anchors the initial view on the recorded extent. Asks
     * {@code recordings/timeline} for the newest/oldest recorded moment, then
     * loads the last 24h of recordings ending at the newest one and drops the
     * cursor there — so we open on real footage instead of "now", which usually
     * sits past the last recording.
     *
     * <p>timeline shares the same availability as recordings/list and
     * recordings/get (all gated on the recording engine), so a failure here is a
     * genuine load error, not a "degrade to a fixed window" case: if timeline is
     * unavailable, list/get would fail too. A camera with no recordings yet is
     * reported by timeline as a 200 with null start/end, handled here.
     *
     * @param shared the moment shared by a sibling camera (swipe-in), or &le; 0.
     */
    private void loadInitialRecordings(long shared) {
        ApiClient.getApiService().recordingsTimeline(currentCamera.getId())
                .enqueue(new ApiCallback<RecordingsTimeline>(requireContext()) {
                    @Override
                    public void onSuccess(RecordingsTimeline body) {
                        long end = (body != null) ? Time.RFC3339toMS(body.getEnd()) : -1L;
                        long start = (body != null) ? Time.RFC3339toMS(body.getStart()) : -1L;
                        recordingsEndMs = end;
                        recordingsStartMs = start;

                        // No recordings yet: timeline reports null start/end.
                        if (end <= 0) {
                            Toast.makeText(requireContext(), R.string.there_is_no_recording, LENGTH_LONG).show();
                            return;
                        }

                        long since = end - (24 * 60 * 60 * 1000L);
                        // Don't query before the oldest recording exists.
                        if (start > 0 && since < start) {
                            since = start;
                        }
                        long select = shared > 0 ? shared : end;

                        getRecordings(since, end, select);
                        getEvents(since, end);
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        Toast.makeText(requireContext(), R.string.error_get_recordings, LENGTH_LONG).show();
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (controller == null) {
            return; // camera has no playback (see showPlaybackUnsupported)
        }
        viewResumed = true;

        controller.attach(playerView);

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
        if (controller == null) {
            return; // camera has no playback (see showPlaybackUnsupported)
        }
        viewResumed = false;

        // Hand the current moment to the sibling cameras before leaving.
        if (timelineView != null) {
            long current = timelineView.getCurrent();
            if (current > 0) {
                setSharedPlaybackTime(current);
            }
        }

        controller.stop();
        controller.detach(playerView);
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
        if (controller != null) {
            controller.release();
            controller = null;
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
                if (controller.isMuted()) {
                    controller.mute(false);
                    prefs.setGlobalMute(false);
                    item.setIcon(R.drawable.ic_volume_24);
                } else {
                    controller.mute(true);
                    prefs.setGlobalMute(true);
                    item.setIcon(R.drawable.ic_muted_24);
                }
                return true;
            } else if (itemId == R.id.pause_action) {
                if (controller.isPaused()) {
                    controller.resume();
                    item.setIcon(R.drawable.ic_pause_24);
                } else {
                    controller.pause();
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
        if (controller == null) {
            return; // camera has no playback (see showPlaybackUnsupported)
        }
        setOrientationLayout(newConfig.orientation);
    }

    /** Hides the player UI and shows the "no recordings" empty state. */
    private void showPlaybackUnsupported() {
        root.findViewById(R.id.playback_content).setVisibility(GONE);
        root.findViewById(R.id.record_button).setVisibility(GONE);
        root.findViewById(R.id.take_snapshot).setVisibility(GONE);
        root.findViewById(R.id.playback_unsupported).setVisibility(VISIBLE);
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

    /**
     * Starts gapless playback from {@code timeMs} over the loaded recordings. The
     * {@link GaplessPlaybackController} owns the chunk planning and queueing; here
     * we only feed it the recordings and report when there's nothing to play.
     */
    private void playFrom(long timeMs) {
        // Never play from a non-visible page (a neighbour in the pager).
        if (controller == null || !viewResumed) {
            return;
        }
        ArrayList<TimeRecord> records = (timelineView != null) ? timelineView.getBackgroundRecords() : null;
        if (records == null || records.isEmpty()) {
            return;
        }
        if (!controller.playFrom(records, timeMs)) {
            Toast.makeText(requireContext(), R.string.there_is_no_recording, LENGTH_LONG).show();
        }
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

    /** Page size for the events endpoint (server caps `limit` at 1000). */
    private static final int EVENTS_PAGE_SIZE = 500;

    private void getEvents(long since, long until) {
        eventsLoading = true;
        updateEventsPanelVisibility(getResources().getConfiguration().orientation);
        // The server defaults `limit` to 100 and reports the full match count as
        // `total`; page through with `offset` so a busy window isn't silently
        // truncated at the first 100 events.
        fetchEventsPage(since, until, 0);
    }

    private void fetchEventsPage(long since, long until, int offset) {
        ApiClient.getApiService().events(currentCamera.getId(), Time.MStoRFC3339(since),
                Time.MStoRFC3339(until), EVENTS_PAGE_SIZE, offset).enqueue(new ApiCallback<EventsResponse>(requireContext()) {
            @Override
            public void onSuccess(EventsResponse body) {
                if (body == null || body.getEvents() == null) {
                    finishEventsLoad();
                    return;
                }

                List<Event> page = body.getEvents();
                for (Event event : page) {
                    if (eventIds.add(event.getId())) {
                        events.add(event);
                    }
                }

                // Keep paging while the server still has more than we've pulled
                // (a short/empty page also means we've reached the end).
                int fetched = offset + page.size();
                if (!page.isEmpty() && fetched < body.getTotal()) {
                    fetchEventsPage(since, until, fetched);
                    return;
                }

                finishEventsLoad();
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

    private void finishEventsLoad() {
        eventsLoading = false;

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
