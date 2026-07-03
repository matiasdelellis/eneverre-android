package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;


import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.slider.Slider;

import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;


import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.ApiService;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.player.VlcPlayer;
import ar.com.delellis.eneverre.talk.TalkClient;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.ApiError;
import ar.com.delellis.eneverre.util.AppPreferences;
import ar.com.delellis.eneverre.util.Download;
import ar.com.delellis.eneverre.util.Snapshot;
import ar.com.delellis.eneverre.util.Time;
import ar.com.delellis.eneverre.util.VideoTouchListener;

import okhttp3.ResponseBody;

public class LiveViewFragment extends Fragment {

    private static final String ARG_CURRENT_CAMERA = "current_camera";

    private VlcPlayer vlcPlayer = null;
    private VLCVideoLayout vlcVideoLayout = null;
    private View fragmentView;

    private Camera currentCamera;

    private ApiService apiService = null;

    private OnPrivacyChangeListener privacyListener;

    private long startRecord = -1;
    private Bitmap startBitmap;

    /** Push-to-talk (two-way audio) session; non-null while talk mode is active. */
    private TalkClient talkClient = null;
    /** Whether the talk panel (swapped in for the PTZ controls) is showing. */
    private boolean talkMode = false;
    /** Current talk toast, kept so a newer one can cancel it instead of queueing behind it. */
    private Toast talkToast = null;
    /** Launched on the first talk press if the mic permission is missing. */
    private ActivityResultLauncher<String> requestMicPermission;

    AppPreferences prefs = null;

    /** Live controls in the host toolbar; only the resumed (visible) page contributes. */
    private final MenuProvider liveMenu = new MenuProvider() {
        @Override
        public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
            inflater.inflate(R.menu.video_top_app_bar, menu);
        }

        @Override
        public void onPrepareMenu(@NonNull Menu menu) {
            boolean privacy = currentCamera.getPrivacy();
            // The privacy toggle is offered only on cameras that support it.
            menu.findItem(R.id.privacy_action).setVisible(currentCamera.hasPrivacy() && !privacy);
            menu.findItem(R.id.pip_action).setVisible(!privacy);
            menu.findItem(R.id.volume_action).setVisible(!privacy);
            menu.findItem(R.id.recalibrate_ptz).setVisible(currentCamera.getPtz() && !privacy);
            menu.findItem(R.id.volume_action).setIcon(
                    prefs.isGlobalMute() ? R.drawable.ic_muted_24 : R.drawable.ic_volume_24);
        }

        @Override
        public boolean onMenuItemSelected(@NonNull MenuItem item) {
            int itemId = item.getItemId();
            if (itemId == R.id.privacy_action) {
                setVideoPrivacyLayout(true);
                stopLive();
                return true;
            } else if (itemId == R.id.pip_action) {
                ((ViewActivity) requireActivity()).enterPipMode(currentCamera);
                return true;
            } else if (itemId == R.id.volume_action) {
                boolean muted = !prefs.isGlobalMute();
                setMuteLive(muted);
                prefs.setGlobalMute(muted);
                item.setIcon(muted ? R.drawable.ic_muted_24 : R.drawable.ic_volume_24);
                return true;
            }
            return false;
        }
    };

    public LiveViewFragment() {
        // Required empty public constructor
    }

    public static LiveViewFragment newInstance(Camera camera) {
        LiveViewFragment fragment = new LiveViewFragment();
        Bundle args = new Bundle();
        args.putSerializable(ARG_CURRENT_CAMERA, camera);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            currentCamera = (Camera) getArguments().getSerializable(ARG_CURRENT_CAMERA);
        }

        // Registered here (before STARTED) as required by the Activity Result API.
        // The mic button requests the permission on first use, then opens talk mode.
        requestMicPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        enterTalkMode();
                    } else {
                        Toast.makeText(requireContext(), R.string.talk_permission_required, LENGTH_LONG).show();
                    }
                });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_live_view, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        fragmentView = view;

        apiService = ApiClient.getApiService();
        prefs = AppPreferences.getInstance(requireContext());

        requireActivity().addMenuProvider(liveMenu, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        int orientation = getResources().getConfiguration().orientation;
        setOrientationLayout(orientation);

        view.findViewById(R.id.reconnect_button).setVisibility(GONE);
        view.findViewById(R.id.exit_privacy_button).setVisibility(currentCamera.getPrivacy() ? VISIBLE : GONE);
        view.findViewById(R.id.loading_progress).setVisibility(currentCamera.getPrivacy() ? GONE : VISIBLE);
        // PTZ controls stay on screen even when the camera lacks PTZ support, just
        // disabled, so the live view doesn't look empty.
        setPtzEnabled(currentCamera.getPtz());

        vlcVideoLayout = view.findViewById(R.id.vlc_video_Layout);
        vlcVideoLayout.setOnTouchListener(new VideoTouchListener());

        view.findViewById(R.id.reconnect_button).setVisibility(GONE);
        view.findViewById(R.id.reconnect_button).setOnClickListener(v -> {
            view.findViewById(R.id.reconnect_button).setVisibility(GONE);
            startLive();
        });

        view.findViewById(R.id.exit_privacy_button).setVisibility(GONE);
        view.findViewById(R.id.exit_privacy_button).setOnClickListener(v -> {
            setVideoPrivacyLayout(false);

            prepareLive();
            setMuteLive(prefs.isGlobalMute());
            startLive();
        });

        view.findViewById(R.id.record_button).setVisibility(VISIBLE);
        view.findViewById(R.id.record_button).setOnClickListener(v -> {
            if (!isRecording()) {
                startLiveRecord();
            } else {
                downloadLiveRecord();
            }
        });

        view.findViewById(R.id.take_snapshot).setVisibility(VISIBLE);
        view.findViewById(R.id.take_snapshot).setOnClickListener(v -> {
            takeSnapshot();
        });

        // Two-way audio: the mic FAB (shown only for backchannel-capable cameras,
        // hidden in privacy mode) opens talk mode, which swaps the PTZ controls for
        // the talk panel below. It is hidden while the panel is open — the panel's
        // own close button ends the session.
        view.findViewById(R.id.talk_button).setOnClickListener(v -> toggleTalkMode());
        view.findViewById(R.id.talk_close_button).setOnClickListener(v -> exitTalkMode());

        // Mic gain: 50 = unity, 100 = 2× (see TalkClient.setMicGain).
        ((Slider) view.findViewById(R.id.mic_volume)).addOnChangeListener((slider, value, fromUser) -> {
            if (talkClient != null) {
                talkClient.setMicGain(value / 50f);
            }
        });

        // Camera volume drives the live VLC player directly (0–100).
        ((Slider) view.findViewById(R.id.camera_volume)).addOnChangeListener((slider, value, fromUser) -> {
            if (vlcPlayer != null) {
                vlcPlayer.setVolume((int) value);
            }
        });

        // Push-to-talk: enabled once the backchannel is ready; hold to transmit.
        FloatingActionButton pushToTalk = view.findViewById(R.id.push_to_talk_button);
        pushToTalk.setOnTouchListener((v, event) -> {
            if (talkClient == null) {
                return false;
            }
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    talkClient.setTransmitting(true);
                    setPushToTalkTransmitting((FloatingActionButton) v, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.performClick();
                    talkClient.setTransmitting(false);
                    setPushToTalkTransmitting((FloatingActionButton) v, false);
                    return true;
                default:
                    return false;
            }
        });

        view.findViewById(R.id.ptz_home_button).setOnClickListener(v -> {
            apiService.home(currentCamera.getId()).enqueue(commandCallback());
        });

        view.findViewById(R.id.ptz_left_button).setOnClickListener(v -> {
            apiService.move(currentCamera.getId(), -45f, 0f).enqueue(commandCallback());
        });

        view.findViewById(R.id.ptz_right_button).setOnClickListener(v -> {
            apiService.move(currentCamera.getId(), 45f, 0f).enqueue(commandCallback());
        });

        view.findViewById(R.id.ptz_up_button).setOnClickListener(v -> {
            apiService.move(currentCamera.getId(), 0f, -45f).enqueue(commandCallback());
        });

        view.findViewById(R.id.ptz_down_button).setOnClickListener(v -> {
            apiService.move(currentCamera.getId(), 0f, 45f).enqueue(commandCallback());
        });

        setPipModeLayout(false);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (currentCamera.getPrivacy())
            setVideoPrivacyLayout(true);
        else {
            prepareLive();
            setMuteLive(prefs.isGlobalMute());
            startLive();
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        // Never keep the mic hot in the background, even in Picture-in-Picture.
        exitTalkMode();

        if (!requireActivity().isInPictureInPictureMode()) {
            stopLive();
        }
    }

    @Override
    public void onDestroyView() {
        // Safety net: onPause keeps the player alive while in Picture-in-Picture
        // so it keeps streaming in the mini-window. When that window is then
        // dismissed the activity is torn down without another onPause, so this
        // is the only place that guarantees the native player is released.
        exitTalkMode();
        stopLive();
        super.onDestroyView();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (fragmentView != null) {
            setOrientationLayout(newConfig.orientation);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (fragmentView != null) {
            setPipModeLayout(isInPictureInPictureMode);
        }
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        if (context instanceof OnPrivacyChangeListener) {
            privacyListener = (OnPrivacyChangeListener) context;
        } else if (getActivity() instanceof OnPrivacyChangeListener) {
            // Nested inside LiveContainerFragment: fall back to the host activity.
            privacyListener = (OnPrivacyChangeListener) getActivity();
        } else {
            throw new RuntimeException("Activity must implement OnPrivacyChangeListener");
        }
    }

    private void setOrientationLayout(int orientation) {
        FrameLayout frameLayout = fragmentView.findViewById(R.id.frameLayout);

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
            int videoWidth = currentCamera.getWidth();
            int videoHeight = currentCamera.getHeight();

            frameLayout.getLayoutParams().width = screenWidth;
            frameLayout.getLayoutParams().height = screenWidth * videoHeight / videoWidth;
        }
        else {
            frameLayout.setLayoutParams(
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0.0F)
            );
        }
        // Show the PTZ controls or the talk panel (both portrait-only) as appropriate.
        updateControlRegionVisibility();
    }

    private void setVideoPrivacyLayout(boolean privacy) {
        fragmentView.findViewById(R.id.take_snapshot).setEnabled(!privacy);
        fragmentView.findViewById(R.id.record_button).setEnabled(!privacy);

        // Two-way audio makes no sense while privacy is on: leave talk mode and hide it.
        if (privacy) {
            exitTalkMode();
        }

        fragmentView.findViewById(R.id.exit_privacy_button).setVisibility(privacy ? VISIBLE : GONE);

        // Black overlay hides the last (frozen) frame while privacy is on, instead
        // of blanking the VLC surface (that lockCanvas blank left the video narrow
        // when leaving privacy because it changed the surface buffer geometry).
        fragmentView.findViewById(R.id.privacy_cover).setVisibility(privacy ? VISIBLE : GONE);

        setPtzEnabled(currentCamera.getPtz() && !privacy);

        apiService.privacy(currentCamera.getId(), privacy).enqueue(commandCallback());

        currentCamera.setPrivacy(privacy);
        privacyListener.onPrivacyChanged(currentCamera, privacy);
        updateTalkButtonVisibility();

        // Privacy hides the pip/volume/recalibrate actions — refresh the menu.
        requireActivity().invalidateOptionsMenu();
    }

    /** Enables/disables the PTZ buttons, dimming them when disabled so they read as inactive. */
    private void setPtzEnabled(boolean enabled) {
        float alpha = enabled ? 1.0f : 0.4f;
        int[] ids = {
                R.id.ptz_home_button, R.id.ptz_up_button, R.id.ptz_down_button,
                R.id.ptz_left_button, R.id.ptz_right_button
        };
        for (int id : ids) {
            View button = fragmentView.findViewById(id);
            button.setEnabled(enabled);
            button.setAlpha(alpha);
        }
    }

    private void takeSnapshot() {
        SurfaceView surfaceView = fragmentView.findViewById(org.videolan.R.id.surface_video);
        Snapshot.getSurfaceBitmap(surfaceView, new Snapshot.PixelCopyListener() {
            @Override
            public void onSurfaceBitmapReady(Bitmap bitmap) {
                String fileName = Download.buildFileName(currentCamera.getName(), Time.MStoFriendlyURL(System.currentTimeMillis()), "png");
                Download.saveSnapshotAndShare(requireActivity(), bitmap, fileName, currentCamera.getName());
            }
            @Override
            public void onSurfaceBitmapError(int errorCode) {
                Toast.makeText(requireContext(), R.string.error_snapshot, LENGTH_LONG).show();
            }
        });
    }

    public void prepareLive() {
        if (vlcPlayer != null)
            return;

        vlcPlayer = new VlcPlayer(requireContext());
        vlcPlayer.setEventListener(event -> {
            if (event.type == MediaPlayer.Event.Buffering) {
                if (event.getBuffering() == 100f) {
                    fragmentView.findViewById(R.id.loading_progress).setVisibility(GONE);
                } else {
                    fragmentView.findViewById(R.id.loading_progress).setVisibility(VISIBLE);
                }
            } else if (event.type == MediaPlayer.Event.EncounteredError) {
                fragmentView.findViewById(R.id.loading_progress).setVisibility(GONE);
                fragmentView.findViewById(R.id.reconnect_button).setVisibility(VISIBLE);
            }
        });

        vlcPlayer.attachView(vlcVideoLayout);
    }

    private void startLive() {
        String videoUrl = currentCamera.getRtsp();
        vlcPlayer.playUri(Uri.parse(videoUrl));
    }

    public void stopLive() {
        if (vlcPlayer != null) {
            vlcPlayer.stop();
            vlcPlayer.detachViews();
            vlcPlayer.release();
            vlcPlayer = null;
        }
    }

    public void setMuteLive(boolean muted) {
        if (vlcPlayer != null) {
            vlcPlayer.mute(muted);
        }
    }

    /** Mic-button click: open talk mode, or close it if already active. */
    private void toggleTalkMode() {
        if (talkMode) {
            exitTalkMode();
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // Granting triggers the launcher callback, which then calls enterTalkMode().
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        enterTalkMode();
    }

    /**
     * Opens the backchannel and swaps the PTZ controls for the talk panel. The
     * push-to-talk button stays disabled until the session reports it is ready.
     */
    private void enterTalkMode() {
        if (talkMode) {
            return;
        }
        String talkUrl = ApiClient.getInstance().getTalkUrl(currentCamera.getId());
        String authHeader = ApiClient.getInstance().getAuthorizationHeader();
        if (authHeader == null) {
            showTalkToast(R.string.talk_error, LENGTH_SHORT);
            return;
        }

        talkMode = true;
        updateTalkButtonVisibility();
        updateControlRegionVisibility();

        // The push-to-talk button stays disabled until the backchannel is ready.
        // Align the live player with the current camera-volume slider up front.
        fragmentView.findViewById(R.id.push_to_talk_button).setEnabled(false);
        if (vlcPlayer != null) {
            vlcPlayer.setVolume((int) ((Slider) fragmentView.findViewById(R.id.camera_volume)).getValue());
        }

        showTalkToast(R.string.talk_connecting, LENGTH_SHORT);

        // Prefer AAC (16 kHz wideband) when the camera advertises it; else PCM/G.711.
        boolean useAac = currentCamera.supportsTalkAac();
        talkClient = new TalkClient(talkUrl, authHeader, useAac, new TalkClient.Listener() {
            @Override
            public void onReady() {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (!talkMode) return;
                    talkClient.setMicGain(((Slider) fragmentView.findViewById(R.id.mic_volume)).getValue() / 50f);
                    fragmentView.findViewById(R.id.push_to_talk_button).setEnabled(true);
                    showTalkToast(R.string.talk_ready, LENGTH_SHORT);
                });
            }

            @Override
            public void onEnd(@Nullable String reason) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    // Surface only real failures (HTTP status), not a normal release/close.
                    if (reason != null && reason.startsWith("HTTP")) {
                        int msg = reason.contains("409") ? R.string.talk_busy : R.string.talk_error;
                        showTalkToast(msg, LENGTH_LONG);
                    }
                    exitTalkMode();
                });
            }
        });
        talkClient.start();
    }

    /** Closes the session and restores the PTZ controls. Idempotent. */
    private void exitTalkMode() {
        if (talkClient != null) {
            TalkClient client = talkClient;
            talkClient = null;
            client.stop();
        }
        talkMode = false;
        if (fragmentView != null) {
            updateTalkButtonVisibility();
            updateControlRegionVisibility();
        }
    }

    /**
     * Shows a talk-status toast, cancelling the previous one first. Otherwise the
     * "ready" toast would queue behind the still-visible "connecting" one and only
     * appear seconds later, even though the camera already accepts audio.
     */
    private void showTalkToast(int resId, int duration) {
        if (talkToast != null) {
            talkToast.cancel();
        }
        talkToast = Toast.makeText(requireContext(), resId, duration);
        talkToast.show();
    }

    /**
     * The mic FAB opens talk mode, so it is shown only when that is possible: a
     * backchannel-capable camera, not in privacy, not in PiP, and not already in
     * talk mode (the panel's own close button ends the session).
     */
    private void updateTalkButtonVisibility() {
        boolean pip = requireActivity().isInPictureInPictureMode();
        boolean show = currentCamera.hasTalk() && !currentCamera.getPrivacy() && !talkMode && !pip;
        fragmentView.findViewById(R.id.talk_button).setVisibility(show ? VISIBLE : GONE);
    }

    /** Recolors the push-to-talk FAB red while held, so the user sees the mic is live. */
    private void setPushToTalkTransmitting(FloatingActionButton fab, boolean transmitting) {
        int background = transmitting
                ? ContextCompat.getColor(fab.getContext(), R.color.record_red)
                : MaterialColors.getColor(fab, com.google.android.material.R.attr.colorPrimaryContainer);
        int icon = transmitting
                ? android.graphics.Color.WHITE
                : MaterialColors.getColor(fab, com.google.android.material.R.attr.colorOnPrimaryContainer);
        fab.setBackgroundTintList(ColorStateList.valueOf(background));
        fab.setImageTintList(ColorStateList.valueOf(icon));
    }

    /**
     * Reconciles which control region is shown below the video: the talk panel in
     * talk mode, the PTZ controls otherwise. Both are portrait-only (as PTZ always
     * was) and hidden in Picture-in-Picture.
     */
    private void updateControlRegionVisibility() {
        boolean portrait = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        boolean pip = requireActivity().isInPictureInPictureMode();
        boolean showRegion = portrait && !pip;
        fragmentView.findViewById(R.id.ptz_buttons).setVisibility(showRegion && !talkMode ? VISIBLE : GONE);
        fragmentView.findViewById(R.id.talk_controls).setVisibility(showRegion && talkMode ? VISIBLE : GONE);
    }

    private void setPipModeLayout(boolean enabled) {
        if (enabled) {
            exitTalkMode();
            fragmentView.findViewById(R.id.frameLayout).setLayoutParams(
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0.0F)
            );
            fragmentView.findViewById(R.id.record_button).setVisibility(GONE);
            fragmentView.findViewById(R.id.take_snapshot).setVisibility(GONE);
        } else {
            fragmentView.findViewById(R.id.record_button).setVisibility(VISIBLE);
            fragmentView.findViewById(R.id.take_snapshot).setVisibility(VISIBLE);
        }
        // Mic FAB and the PTZ/talk region follow PiP + orientation.
        updateTalkButtonVisibility();
        updateControlRegionVisibility();
    }

    private boolean isRecording() {
        return startRecord > 0;
    }

    private void startLiveRecord() {
        FloatingActionButton fab = fragmentView.findViewById(R.id.record_button);

        fab.setImageResource(R.drawable.ic_stop_circle_24);
        int color = ContextCompat.getColor(fab.getContext(), R.color.record_red);
        fab.setImageTintList(ColorStateList.valueOf(color));

        SurfaceView surfaceView = fragmentView.findViewById(org.videolan.R.id.surface_video);
        Snapshot.getSurfaceBitmap(surfaceView, new Snapshot.PixelCopyListener() {
            @Override
            public void onSurfaceBitmapReady(Bitmap bitmap) {
                startBitmap = bitmap;
            }
            @Override
            public void onSurfaceBitmapError(int errorCode) {
                startBitmap = null;
            }
        });

        startRecord = System.currentTimeMillis();
        Toast.makeText(requireContext(), getString(R.string.starting_recording), LENGTH_SHORT).show();
    }

    private void downloadLiveRecord() {
        long _startRecord = startRecord;
        long stopRecord = System.currentTimeMillis();

        fragmentView.postDelayed(() -> {
            double duration = (double) (stopRecord - _startRecord) / 1000.0;
            downloadPlayback(_startRecord, duration);
        }, 2500);

        FloatingActionButton fab = fragmentView.findViewById(R.id.record_button);
        fab.setImageResource(R.drawable.ic_video_cam_24);
        int color = MaterialColors.getColor(
                fab,
                com.google.android.material.R.attr.colorOnSecondaryContainer);
        fab.setImageTintList(ColorStateList.valueOf(color));

        Toast.makeText(requireContext(), R.string.recording_downloaded_soon, LENGTH_LONG).show();

        startRecord = -1L;
    }

    private void downloadPlayback(long startRecord, double duration) {
        String startDownload = Time.MStoRFC3339(startRecord);

        apiService.recording(currentCamera.getId(), startDownload, duration).enqueue(new ApiCallback<ResponseBody>(requireContext()) {
            @Override
            public void onSuccess(ResponseBody body) {
                if (body == null) {
                    onError(ApiError.NO_HTTP_CODE, null);
                    return;
                }

                String dateTime = Time.MStoFriendlyURL(startRecord);
                String fileName = Download.buildFileName(currentCamera.getId(), dateTime, "mp4");
                Download.saveClipAndShare(requireActivity(), body, fileName, currentCamera.getName(), startBitmap, (long) duration);
            }

            @Override
            public void onError(int httpCode, String message) {
                Toast.makeText(requireContext(), R.string.error_download, LENGTH_LONG).show();
            }
        });
    }

    /**
     * Callback for fire-and-forget commands (PTZ, privacy) whose response body is
     * empty. Success needs no action; failures surface a message via {@link ApiCallback}.
     */
    private ApiCallback<Void> commandCallback() {
        return new ApiCallback<Void>(requireContext()) {
            @Override
            public void onSuccess(Void body) {
                // Nothing to do: the command was accepted.
            }
        };
    }

    public interface OnPrivacyChangeListener {
        void onPrivacyChanged(Camera camera, boolean enabled);
    }
}
