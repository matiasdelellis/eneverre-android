package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.widget.Toast.LENGTH_LONG;

import static ar.com.delellis.eneverre.PlaybackActivity.INTENT_PLAYBACK_VIEW;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.ApiService;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.player.VlcPlayer;
import ar.com.delellis.eneverre.util.AppPreferences;
import ar.com.delellis.eneverre.util.Download;
import ar.com.delellis.eneverre.util.Snapshot;
import ar.com.delellis.eneverre.util.Time;
import ar.com.delellis.eneverre.util.VideoTouchListener;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class LiveViewFragment extends Fragment {
    private static final String TAG = "LiveViewFragment";

    private static final String ARG_CURRENT_CAMERA = "current_camera";

    private VlcPlayer vlcPlayer = null;
    private VLCVideoLayout vlcVideoLayout = null;
    private View fragmentView;

    private Camera currentCamera;

    private ApiClient apiClient = null;
    private ApiService apiService = null;

    private OnPrivacyChangeListener privacyListener;

    private boolean pipMode = false;

    AppPreferences prefs = null;

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

        apiClient = ApiClient.getInstance();
        apiService = ApiClient.getApiService();

        int orientation = getResources().getConfiguration().orientation;
        setOrientationLayout(orientation);

        view.findViewById(R.id.reconnect_button).setVisibility(GONE);
        view.findViewById(R.id.exit_privacy_button).setVisibility(currentCamera.getPrivacy() ? VISIBLE : GONE);
        view.findViewById(R.id.loading_progress).setVisibility(currentCamera.getPrivacy() ? GONE : VISIBLE);
        view.findViewById(R.id.ptz_buttons).setVisibility(currentCamera.getPtz() ? VISIBLE : GONE);

        vlcVideoLayout = view.findViewById(R.id.vlc_video_Layout);
        vlcVideoLayout.setOnTouchListener(new VideoTouchListener(vlcVideoLayout));

        view.findViewById(R.id.go_playback_button).setVisibility(currentCamera.hasPlayback() ? VISIBLE : GONE);
        view.findViewById(R.id.go_playback_button).setOnClickListener(v -> {
            Intent goIntent = new Intent(requireActivity(), PlaybackActivity.class);
            goIntent.putExtra(LiveViewActivity.CURRENT_CAMERA_DATA, currentCamera);
            startActivityForResult(goIntent, INTENT_PLAYBACK_VIEW);
        });

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

        view.findViewById(R.id.take_snapshot).setVisibility(VISIBLE);
        view.findViewById(R.id.take_snapshot).setOnClickListener(v -> {
            takeSnapshot();
        });

        view.findViewById(R.id.ptz_home_button).setOnClickListener(v -> {
            Call<Void> homeCall = apiService.home(apiClient.getAuthorization(), currentCamera.getId());
            homeCall.enqueue(new VoidPtzCallback());
        });

        view.findViewById(R.id.ptz_left_button).setOnClickListener(v -> {
            Call<Void> leftCall = apiService.move(apiClient.getAuthorization(), currentCamera.getId(), -45, 0);
            leftCall.enqueue(new VoidPtzCallback());
        });

        view.findViewById(R.id.ptz_right_button).setOnClickListener(v -> {
            Call<Void> rightCall = apiService.move(apiClient.getAuthorization(), currentCamera.getId(), 45, 0);
            rightCall.enqueue(new VoidPtzCallback());
        });

        view.findViewById(R.id.ptz_up_button).setOnClickListener(v -> {
            Call<Void> upCall = apiService.move(apiClient.getAuthorization(), currentCamera.getId(), 0, -45);
            upCall.enqueue(new VoidPtzCallback());
        });

        view.findViewById(R.id.ptz_down_button).setOnClickListener(v -> {
            Call<Void> downCall = apiService.move(apiClient.getAuthorization(), currentCamera.getId(), 0, 45);
            downCall.enqueue(new VoidPtzCallback());
        });

        prefs = AppPreferences.getInstance(requireContext());

        applyPipModeLayout();
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
        stopLive(false);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        if (context instanceof OnPrivacyChangeListener) {
            privacyListener = (OnPrivacyChangeListener) context;
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
            frameLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0.0F));
        }
    }

    private void setVideoPrivacyLayout(boolean privacy) {
        fragmentView.findViewById(R.id.take_snapshot).setEnabled(!privacy);
        fragmentView.findViewById(R.id.privacy_button).setEnabled(!privacy);

        fragmentView.findViewById(R.id.exit_privacy_button).setVisibility(privacy ? VISIBLE : GONE);

        if (currentCamera.getPtz()) {
            fragmentView.findViewById(R.id.ptz_up_button).setEnabled(!privacy);
            fragmentView.findViewById(R.id.ptz_down_button).setEnabled(!privacy);
            fragmentView.findViewById(R.id.ptz_home_button).setEnabled(!privacy);
            fragmentView.findViewById(R.id.ptz_left_button).setEnabled(!privacy);
            fragmentView.findViewById(R.id.ptz_right_button).setEnabled(!privacy);
        }

        Call<Void> privacyCall = apiService.privacy(apiClient.getAuthorization(), currentCamera.getId(), privacy);
        privacyCall.enqueue(new VoidPtzCallback());

        currentCamera.setPrivacy(privacy);
        privacyListener.onPrivacyChanged(currentCamera, privacy);
    }

    private void takeSnapshot() {
        SurfaceView surfaceView = fragmentView.findViewById(org.videolan.R.id.surface_video);
        Snapshot.getSurfaceBitmap(surfaceView, new Snapshot.PixelCopyListener() {
            @Override
            public void onSurfaceBitmapReady(Bitmap bitmap) {
                File snapshotFile = Download.getDownloadFile(currentCamera.getName(), Time.MStoFriendlyURL(System.currentTimeMillis()), "png");
                try {
                    OutputStream out = new BufferedOutputStream(new FileOutputStream(snapshotFile));
                    bitmap.compress(Bitmap.CompressFormat.PNG, 0, out);
                    Download.share(requireContext(), Uri.parse(snapshotFile.getPath()), currentCamera.getName(), "image/png");
                } catch (FileNotFoundException e) {
                    Toast.makeText(requireContext(), R.string.error_snapshot, LENGTH_LONG).show();
                }
            }
            @Override
            public void onSurfaceBitmapError(int errorCode) {
                Toast.makeText(requireContext(), R.string.error_snapshot, LENGTH_LONG).show();
            }
        });
    }

    public void prepareLive() {
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
        String videoUrl = currentCamera.getLive();
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

    public void setPipModeLayout(boolean enabled) {
        pipMode = enabled;
        applyPipModeLayout();
    }

    private void applyPipModeLayout() {
        if (pipMode) {
            fragmentView.findViewById(R.id.frameLayout).setLayoutParams(
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0.0F)
            );
            fragmentView.findViewById(R.id.ptz_buttons).setVisibility(GONE);
            fragmentView.findViewById(R.id.privacy_button).setVisibility(GONE);
            fragmentView.findViewById(R.id.take_snapshot).setVisibility(GONE);
        } else {
            if (currentCamera.getPtz()) {
                fragmentView.findViewById(R.id.ptz_buttons).setVisibility(VISIBLE);
            }
            fragmentView.findViewById(R.id.privacy_button).setVisibility(VISIBLE);
            fragmentView.findViewById(R.id.take_snapshot).setVisibility(VISIBLE);
        }
    }

    private static class VoidPtzCallback implements Callback<Void> {
        @Override
        public void onResponse(Call<Void> call, Response<Void> response) {
            Log.i(TAG, "Apply ptz");
        }
        @Override
        public void onFailure(Call<Void> call, Throwable throwable) {
            Log.e(TAG, "Fail to apply ptz");
        }
    }

    public interface OnPrivacyChangeListener {
        void onPrivacyChanged(Camera camera, boolean enabled);
    }
}
