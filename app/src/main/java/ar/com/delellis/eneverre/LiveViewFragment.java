package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;


import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;

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
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;


import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.ApiService;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.player.VlcPlayer;
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
            menu.findItem(R.id.pip_action).setVisible(!privacy);
            menu.findItem(R.id.volume_action).setVisible(!privacy);
            menu.findItem(R.id.recalibrate_ptz).setVisible(currentCamera.getPtz() && !privacy);
            menu.findItem(R.id.volume_action).setIcon(
                    prefs.isGlobalMute() ? R.drawable.ic_muted_24 : R.drawable.ic_volume_24);
        }

        @Override
        public boolean onMenuItemSelected(@NonNull MenuItem item) {
            int itemId = item.getItemId();
            if (itemId == R.id.pip_action) {
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
        view.findViewById(R.id.ptz_buttons).setVisibility(currentCamera.getPtz() ? VISIBLE : GONE);

        vlcVideoLayout = view.findViewById(R.id.vlc_video_Layout);
        vlcVideoLayout.setOnTouchListener(new VideoTouchListener(vlcVideoLayout));

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

        view.findViewById(R.id.privacy_button).setVisibility(VISIBLE);
        view.findViewById(R.id.privacy_button).setOnClickListener(v -> {
            setVideoPrivacyLayout(true);
            stopLive(true);
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

        view.findViewById(R.id.ptz_home_button).setOnClickListener(v -> {
            apiService.home(currentCamera.getId()).enqueue(commandCallback());
        });

        view.findViewById(R.id.ptz_left_button).setOnClickListener(v -> {
            apiService.move(currentCamera.getId(), -45, 0).enqueue(commandCallback());
        });

        view.findViewById(R.id.ptz_right_button).setOnClickListener(v -> {
            apiService.move(currentCamera.getId(), 45, 0).enqueue(commandCallback());
        });

        view.findViewById(R.id.ptz_up_button).setOnClickListener(v -> {
            apiService.move(currentCamera.getId(), 0, -45).enqueue(commandCallback());
        });

        view.findViewById(R.id.ptz_down_button).setOnClickListener(v -> {
            apiService.move(currentCamera.getId(), 0, 45).enqueue(commandCallback());
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

        if (!requireActivity().isInPictureInPictureMode()) {
            stopLive(false);
        }
    }

    @Override
    public void onDestroyView() {
        // Safety net: onPause keeps the player alive while in Picture-in-Picture
        // so it keeps streaming in the mini-window. When that window is then
        // dismissed the activity is torn down without another onPause, so this
        // is the only place that guarantees the native player is released.
        stopLive(false);
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
        View ptzButtons = fragmentView.findViewById(R.id.ptz_buttons);

        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            ptzButtons.setVisibility(currentCamera.getPtz() ? VISIBLE : GONE);

            int screenWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
            int videoWidth = currentCamera.getWidth();
            int videoHeight = currentCamera.getHeight();

            frameLayout.getLayoutParams().width = screenWidth;
            frameLayout.getLayoutParams().height = screenWidth * videoHeight / videoWidth;
        }
        else {
            ptzButtons.setVisibility(GONE);
            frameLayout.setLayoutParams(
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0.0F)
            );
        }
    }

    private void setVideoPrivacyLayout(boolean privacy) {
        fragmentView.findViewById(R.id.take_snapshot).setEnabled(!privacy);
        fragmentView.findViewById(R.id.privacy_button).setEnabled(!privacy);
        fragmentView.findViewById(R.id.record_button).setEnabled(!privacy);

        fragmentView.findViewById(R.id.exit_privacy_button).setVisibility(privacy ? VISIBLE : GONE);

        if (currentCamera.getPtz()) {
            fragmentView.findViewById(R.id.ptz_up_button).setEnabled(!privacy);
            fragmentView.findViewById(R.id.ptz_down_button).setEnabled(!privacy);
            fragmentView.findViewById(R.id.ptz_home_button).setEnabled(!privacy);
            fragmentView.findViewById(R.id.ptz_left_button).setEnabled(!privacy);
            fragmentView.findViewById(R.id.ptz_right_button).setEnabled(!privacy);
        }

        apiService.privacy(currentCamera.getId(), privacy).enqueue(commandCallback());

        currentCamera.setPrivacy(privacy);
        privacyListener.onPrivacyChanged(currentCamera, privacy);

        // Privacy hides the pip/volume/recalibrate actions — refresh the menu.
        requireActivity().invalidateOptionsMenu();
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

    public void stopLive(boolean clearView) {
        if (vlcPlayer != null) {
            vlcPlayer.stop();
            vlcPlayer.detachViews();
            vlcPlayer.release();
            vlcPlayer = null;
        }

        /*
         * FIXME: This works, but it changes the size of the canvas?.
         * The video is smaller than before.... but when you zoom in, it has much better quality.
         * I must investigate whether it can be used.
         */
        if (clearView) {
            SurfaceView surfaceView = fragmentView.findViewById(org.videolan.R.id.surface_video);
            if (surfaceView == null)
                return;
            SurfaceHolder holder = surfaceView.getHolder();
            Canvas canvas = holder.lockCanvas();
            canvas.drawColor(Color.BLACK);
            holder.unlockCanvasAndPost(canvas);
        }
    }

    public void setMuteLive(boolean muted) {
        if (vlcPlayer != null) {
            vlcPlayer.mute(muted);
        }
    }

    private void setPipModeLayout(boolean enabled) {
        if (enabled) {
            fragmentView.findViewById(R.id.frameLayout).setLayoutParams(
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0.0F)
            );
            if (currentCamera.getPtz()) {
                fragmentView.findViewById(R.id.ptz_buttons).setVisibility(GONE);
            }
            fragmentView.findViewById(R.id.record_button).setVisibility(GONE);
            fragmentView.findViewById(R.id.privacy_button).setVisibility(GONE);
            fragmentView.findViewById(R.id.take_snapshot).setVisibility(GONE);
        } else {
            if (currentCamera.getPtz()) {
                fragmentView.findViewById(R.id.ptz_buttons).setVisibility(VISIBLE);
            }
            fragmentView.findViewById(R.id.record_button).setVisibility(VISIBLE);
            fragmentView.findViewById(R.id.privacy_button).setVisibility(VISIBLE);
            fragmentView.findViewById(R.id.take_snapshot).setVisibility(VISIBLE);
        }
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
