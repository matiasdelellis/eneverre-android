package ar.com.delellis.eneverre.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.SurfaceHolder;

import com.alexvas.rtsp.widget.RtspStatusListener;
import com.alexvas.rtsp.widget.RtspSurfaceView;

/**
 * The live view of one camera: a low-latency RTSP player
 * ({@link RtspSurfaceView} from rtsp-client-android) that draws straight on its
 * own surface — there is no separate player object, the view <em>is</em> the
 * player. It replaced libVLC once the library gained playback volume, which was
 * the only thing it was missing for this app.
 *
 * On top of the library widget it adds what the app needs and the widget does
 * not do by itself:
 *
 * <ul>
 *   <li><b>Credentials.</b> {@code Camera.getRtsp()} carries its (rotated)
 *       MediaMTX credentials inline — {@code rtsp://user:pass@host/path} —
 *       which is what libVLC expected. The library instead wants them out of
 *       band (it sends the URL verbatim in every RTSP request, so userinfo
 *       there would just leak and not authenticate), so {@link #playUri} splits
 *       them off the URL.
 *   <li><b>Aspect ratio.</b> The widget is a plain {@code SurfaceView}: the
 *       decoder stretches the picture to whatever bounds it gets. libVLC
 *       letterboxed, so {@link #onMeasure} shrinks the view to the stream's own
 *       ratio and the (black) parent shows through the bars.
 *   <li><b>Surface readiness.</b> Playback can only be handed a live surface,
 *       but callers start streaming from {@code onResume} / view attachment,
 *       before the surface exists. A URL set too early is remembered and
 *       started from {@code surfaceCreated} instead — and restarted if the
 *       surface is destroyed and recreated (rotation, Picture-in-Picture).
 *   <li><b>One error per session.</b> Every session ends with a callback,
 *       including the one we tore down ourselves and the one whose replacement
 *       is already starting — so {@link Listener#onError} is reported once, for
 *       a stream that really died, and a restart waits for its predecessor's
 *       teardown instead of inheriting it as a failure.
 * </ul>
 *
 * Volume is a gain in 0..1 in the library; this view keeps the 0–100 scale the
 * volume slider and the mute preference already speak.
 */
public class RtspPlayerView extends RtspSurfaceView {

    /** Playback state, always delivered on the main thread. */
    public interface Listener {
        /** Connecting (or reconnecting): nothing is on screen yet. */
        void onConnecting();

        /** First frame is on screen. */
        void onPlaying();

        /**
         * The stream failed or ended on its own. Fired at most once per
         * {@link #playUri} and never for a stop this app asked for.
         *
         * @param unauthorized the RTSP credentials were rejected (they rotate,
         *                     so the caller can refetch the camera and retry).
         */
        void onError(boolean unauthorized);
    }

    /**
     * How long to wait for a stopped session to report that it ended before
     * starting its replacement anyway.
     */
    private static final long STOP_TIMEOUT_MS = 1500;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Listener listener = null;

    /** Credential-free URL of the current stream, and its credentials. */
    private Uri uri = null;
    private String username = null;
    private String password = null;

    private boolean requestAudio = true;
    private int volumePercent = 100;

    /** Size of the decoded picture, 0 until the decoder reports it. */
    private int videoWidth = 0;
    private int videoHeight = 0;

    private boolean surfaceReady = false;
    /**
     * Set while a stream is waiting to start: for a surface to draw on, or for
     * the session it replaces to finish winding down.
     */
    private boolean startPending = false;
    /** Guards {@link Listener#onError} against a failure reported twice. */
    private boolean errorReported = false;
    /** Set while tearing down on purpose, so the teardown isn't an error. */
    private boolean stopping = false;

    /**
     * Starts a stream whose predecessor never reported that it ended. Only a
     * safety net: the library always ends a session with a terminal callback.
     */
    private final Runnable pendingStart = () -> {
        if (startPending && surfaceReady) {
            startPending = false;
            startStream();
        }
    };

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            surfaceReady = true;
            // A session still winding down starts the pending stream itself,
            // from its terminal callback.
            if (startPending && !isStarted()) {
                startPending = false;
                startStream();
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            // The widget's own callback stops the decoders here; the RTSP
            // session would stay up feeding a dead surface, so end it and
            // remember to start over once a surface is back.
            if (isStarted()) {
                stopStream();
                startPending = true;
            }
        }
    };

    private final RtspStatusListener statusListener = new RtspStatusListener() {
        @Override
        public void onRtspStatusConnecting() {
            notifyConnecting();
        }

        @Override
        public void onRtspStatusFailedUnauthorized() {
            mainHandler.post(() -> sessionEnded(true));
        }

        @Override
        public void onRtspStatusFailed(String message) {
            mainHandler.post(() -> sessionEnded(false));
        }

        @Override
        public void onRtspStatusDisconnected() {
            // A stream that ends by itself is as broken as one that failed:
            // there is nothing on screen either way.
            mainHandler.post(() -> sessionEnded(false));
        }

        @Override
        public void onRtspFirstFrameRendered() {
            // Reported from the decoder thread, not the main one.
            mainHandler.post(() -> {
                if (listener != null) {
                    listener.onPlaying();
                }
            });
        }

        @Override
        public void onRtspFrameSizeChanged(int width, int height) {
            // Reported from the decoder thread, not the main one.
            mainHandler.post(() -> setVideoSize(width, height));
        }
    };

    public RtspPlayerView(Context context) {
        super(context);
        setUpPlayer();
    }

    public RtspPlayerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setUpPlayer();
    }

    public RtspPlayerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setUpPlayer();
    }

    private void setUpPlayer() {
        getHolder().addCallback(surfaceCallback);
        setStatusListener(statusListener);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Whether to pull the camera's audio track at all. The mosaic cells don't
     * (several live audio tracks at once are unusable), which also keeps them
     * from decoding audio they would only mute. Takes effect on the next
     * {@link #playUri}.
     */
    public void setAudioEnabled(boolean enabled) {
        requestAudio = enabled;
    }

    /**
     * Starts (or restarts) playback of {@code uri}, whose inline credentials —
     * if any — are moved to the RTSP authentication headers.
     */
    public void playUri(Uri uri) {
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            int separator = userInfo.indexOf(':');
            username = separator < 0 ? userInfo : userInfo.substring(0, separator);
            password = separator < 0 ? null : userInfo.substring(separator + 1);
            this.uri = uri.buildUpon().encodedAuthority(hostAndPort(uri)).build();
        } else {
            username = null;
            password = null;
            this.uri = uri;
        }

        videoWidth = 0;
        videoHeight = 0;

        notifyConnecting();

        if (isStarted()) {
            // Replacing a running stream (a reconnect, or another camera in a
            // recycled cell): end it first and let its teardown callback start
            // this one, so its trailing "disconnected" can't be read as a
            // failure of the new session.
            startPending = true;
            stopStream();
            mainHandler.postDelayed(pendingStart, STOP_TIMEOUT_MS);
        } else if (surfaceReady) {
            startStream();
        } else {
            // No surface yet: surfaceCreated picks this up.
            startPending = true;
        }
    }

    /** Ends playback. No {@link Listener#onError} follows. */
    public void stopPlayback() {
        startPending = false;
        mainHandler.removeCallbacks(pendingStart);
        stopStream();
    }

    /**
     * A session reached its end — on its own, or because we stopped it. Runs on
     * the main thread; each session ends exactly once from the app's point of
     * view (see {@link #errorReported}).
     */
    private void sessionEnded(boolean unauthorized) {
        if (startPending && surfaceReady) {
            // This was the session a pending stream is waiting for.
            mainHandler.removeCallbacks(pendingStart);
            startPending = false;
            startStream();
            return;
        }
        // Our own teardown, or an end already reported: nothing to say.
        if (stopping || errorReported) {
            return;
        }
        errorReported = true;
        if (listener != null) {
            listener.onError(unauthorized);
        }
    }

    private void startStream() {
        if (uri == null) {
            return;
        }
        // A session starts clean: the callbacks of the previous one (our own
        // teardown, its failure) must not be attributed to this one.
        stopping = false;
        errorReported = false;

        init(uri, username, password, null, null);
        setVolumePercent(volumePercent);
        start(true, requestAudio, false);
    }

    private void stopStream() {
        if (!isStarted()) {
            return;
        }
        stopping = true;
        // The library's own stop(), which is final — hence stopPlayback().
        stop();
    }

    /** {@code host[:port]} of {@code uri}, i.e. its authority minus the credentials. */
    private static String hostAndPort(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        return uri.getPort() == -1 ? host : host + ":" + uri.getPort();
    }

    public boolean isPlaying() {
        return isStarted() || startPending;
    }

    public void mute(boolean muted) {
        setVolumePercent(muted ? 0 : 100);
    }

    /** Sets the playback volume on the app's 0–100 scale (the library takes a 0..1 gain). */
    public void setVolumePercent(int percent) {
        volumePercent = Math.max(0, Math.min(100, percent));
        setVolume(volumePercent / 100f);
    }

    private void notifyConnecting() {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onConnecting();
            }
        });
    }

    /** Re-letterboxes for a stream whose picture size just became known. */
    private void setVideoSize(int width, int height) {
        if (width <= 0 || height <= 0 || (videoWidth == width && videoHeight == height)) {
            return;
        }
        videoWidth = width;
        videoHeight = height;
        requestLayout();
    }

    /**
     * Shrinks the view to the stream's aspect ratio inside the space it was
     * given, so a picture that isn't shaped like its container is letterboxed
     * (over the parent's background) instead of stretched.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = getMeasuredWidth();
        int height = getMeasuredHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) {
            return;
        }

        float videoRatio = (float) videoWidth / videoHeight;
        float viewRatio = (float) width / height;
        float difference = videoRatio / viewRatio - 1f;

        // Under a percent of difference the bars would be invisible anyway;
        // filling the container avoids a one-pixel seam.
        if (Math.abs(difference) < 0.01f) {
            return;
        }

        if (difference > 0) {
            height = Math.round(width / videoRatio);
        } else {
            width = Math.round(height * videoRatio);
        }
        setMeasuredDimension(width, height);
    }
}
