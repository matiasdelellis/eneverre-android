package ar.com.delellis.eneverre.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import ar.com.delellis.eneverre.BuildConfig;
import ar.com.delellis.eneverre.util.VideoTouchListener;

import com.alexvas.rtsp.codec.VideoDecodeThread;
import com.alexvas.rtsp.codec.VideoDecoderSurfaceThread;
import com.alexvas.rtsp.codec.VideoFrameQueue;
import com.alexvas.rtsp.widget.RtspProcessor;
import com.alexvas.rtsp.widget.RtspStatusListener;
import com.limelight.binding.video.MediaCodecHelper;

/**
 * The live view of one camera: a low-latency RTSP player over
 * <a href="https://github.com/alexeyvasilyev/rtsp-client-android">rtsp-client-android</a>.
 * The view <em>is</em> the player — there is no separate player object — and one
 * of these serves both the single-camera view and every mosaic cell.
 *
 * <p>It drives the library's {@link RtspProcessor} directly instead of using the
 * library's own {@code RtspSurfaceView} widget, so that the picture lands on a
 * {@link TextureView}. That is the whole point: a {@code SurfaceView} is a
 * separate compositor layer that the window merely punches a hole for, so it
 * ignores the clipping of its parents — magnifying one mosaic cell spilled the
 * enlarged picture over the neighbouring cells, through <em>their</em> holes. A
 * {@code TextureView} draws inside the view hierarchy: it clips like any view,
 * and {@link #setTransform} zooms the <b>content</b> without the view growing at
 * all. The cost is a texture copy per frame, which a {@code SurfaceView} avoids.
 *
 * <p>On top of the library it adds what the app needs and the library does not
 * do by itself:
 *
 * <ul>
 *   <li><b>Credentials.</b> {@code Camera.getRtsp()} carries its (rotated)
 *       MediaMTX credentials inline — {@code rtsp://user:pass@host/path} —
 *       which is what libVLC expected. The library instead wants them out of
 *       band (it sends the URL verbatim in every RTSP request, so userinfo
 *       there would just leak and not authenticate), so {@link #playUri} splits
 *       them off the URL.
 *   <li><b>Aspect ratio.</b> The decoder stretches the picture to whatever
 *       bounds it gets, so {@link #applyContentTransform()} fits it to the
 *       stream's own ratio in the content matrix — never by measuring the view
 *       differently, which is a size of ours that outlives a layout pass that
 *       skips it. The view always fills what the layout gave it and the (black)
 *       parent shows through the bars, which is why it is not opaque.
 *   <li><b>Surface readiness.</b> Playback can only be handed a live surface,
 *       but callers start streaming from {@code onResume} / view attachment,
 *       before the texture exists. A URL set too early is remembered and
 *       started once it does — and restarted if the texture is destroyed and
 *       recreated (rotation, Picture-in-Picture).
 *   <li><b>One error per session.</b> Every session ends with a callback,
 *       including the one we tore down ourselves and the one whose replacement
 *       is already starting — so {@link Listener#onError} is reported once, for
 *       a stream that really died, and a restart waits for its predecessor's
 *       teardown instead of inheriting it as a failure.
 * </ul>
 *
 * <p>Volume is a gain in 0..1 in the library; this view keeps the 0–100 scale
 * the volume slider and the mute preference already speak.
 */
public class RtspPlayerView extends TextureView implements VideoTouchListener.TransformTarget {

    private static final String TAG = "RtspPlayerView";

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

    /** The texture the decoder renders into; null until it is available. */
    private Surface surface = null;
    /** Its size, i.e. what the decoder gets configured for. */
    private int surfaceWidth = 1920;
    private int surfaceHeight = 1080;

    /**
     * The session, which asks {@link #newDecodeThread} for its decoder once the
     * RTSP connection is up. (The library declares that parameter as a Kotlin
     * function type, i.e. an anonymous {@code FunctionN} interface whose name
     * says nothing — hence the method reference to a named one.)
     */
    private final RtspProcessor processor = new RtspProcessor(this::newDecodeThread);

    /**
     * Builds the thread that decodes this session's video into our texture.
     * Called by the library once the stream's format is known, which is why it
     * reads the surface from the field rather than closing over one: by then
     * the texture may not be the one that existed when the session started.
     *
     * @param mimeType                the stream's video format, e.g. {@code video/avc}
     * @param rotation                degrees the picture has to be rotated by
     * @param frames                  queue the RTSP thread fills with frames to decode
     * @param decoderListener         where the decoder reports size and first frame
     * @param decoderType             hardware or software decoding
     * @param frameRateStabilization  whether the decoder smooths playback
     */
    private VideoDecodeThread newDecodeThread(
            String mimeType,
            Integer rotation,
            VideoFrameQueue frames,
            VideoDecodeThread.VideoDecoderListener decoderListener,
            VideoDecodeThread.DecoderType decoderType,
            Boolean frameRateStabilization) {
        return new VideoDecoderSurfaceThread(
                surface,
                mimeType,
                surfaceWidth,
                surfaceHeight,
                rotation,
                frames,
                decoderListener,
                decoderType,
                frameRateStabilization);
    }

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
    /**
     * The camera's resolution as the backend reports it. Used to fit the
     * picture until — and if — the decoder reports its own size: the shape of
     * the stream is known before a single frame arrives, so there is no need to
     * show it stretched in the meantime, and a decoder that never reports (or
     * reports a padded geometry) does not leave the fit wrong.
     */
    private int hintWidth = 0;
    private int hintHeight = 0;

    /** Last fit reported to the log, so it is not repeated on every gesture. */
    private float loggedFitX = 0f;
    private float loggedFitY = 0f;

    /** Digital zoom: content magnification and pan, applied as a matrix. */
    private float zoomScale = 1f;
    private float zoomPanX = 0f;
    private float zoomPanY = 0f;

    /**
     * Set while a stream is waiting to start: for a texture to draw on, or for
     * the session it replaces to finish winding down.
     */
    private boolean startPending = false;
    /** Guards {@link Listener#onError} against a failure reported twice. */
    private boolean errorReported = false;
    /**
     * Sessions we stopped whose terminal callback has not arrived yet. That
     * callback lands on the main thread well after the session stops counting
     * as "started", so a stream started in that window — which is exactly what
     * the grid does when it tears its cells down and rebuilds them — would
     * otherwise inherit the dead session's goodbye and report it as its own
     * failure. Counted rather than flagged so that a goodbye is still
     * recognised as the old session's even if it arrives after we gave up
     * waiting for it.
     */
    private int pendingGoodbyes = 0;

    /**
     * Starts a stream whose predecessor is taking too long to say it ended.
     * The goodbye stays owed, so it is still not mistaken for this session's.
     */
    private final Runnable pendingStart = () -> {
        if (startPending && surface != null && !processor.isStarted()) {
            startPending = false;
            startStream();
        }
    };

    private final SurfaceTextureListener textureListener = new SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            surface = new Surface(texture);
            surfaceWidth = width;
            surfaceHeight = height;
            maybeStart();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            surfaceWidth = width;
            surfaceHeight = height;
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            // The decoders must let go before the texture does — they are
            // writing into it. Then end the session, which would otherwise keep
            // feeding a dead surface, and remember to start over if a texture
            // comes back.
            processor.stopDecoders();
            if (processor.isStarted()) {
                stopStream();
                // Pick the stream back up if a texture comes back (rotation,
                // Picture-in-Picture); a caller that really wanted it stopped
                // clears this through stopPlayback().
                startPending = true;
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
            // True: the framework releases the SurfaceTexture itself.
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
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
        setUpPlayer(context);
    }

    public RtspPlayerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setUpPlayer(context);
    }

    public RtspPlayerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setUpPlayer(context);
    }

    private void setUpPlayer(Context context) {
        // The picture is fitted inside the view by the content matrix, so the
        // slack around it has to let the (black) parent through.
        setOpaque(false);
        // Primes the library's decoder lookup (device quirks, low-latency
        // support); its own widget does this from its constructor too.
        MediaCodecHelper.initialize(context, "");
        processor.setStatusListener(statusListener);
        setSurfaceTextureListener(textureListener);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * The picture's expected shape, from the camera's metadata. Superseded by
     * whatever the decoder reports for the stream actually playing.
     */
    public void setVideoAspect(int width, int height) {
        if (width <= 0 || height <= 0 || (hintWidth == width && hintHeight == height)) {
            return;
        }
        hintWidth = width;
        hintHeight = height;
        applyContentTransform();
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

        // The decoder's geometry belongs to the session that is ending; until
        // the new one reports its own, fit by the camera's metadata.
        videoWidth = 0;
        videoHeight = 0;
        applyContentTransform();

        notifyConnecting();

        startPending = true;
        maybeStart();
    }

    /** Ends playback. No {@link Listener#onError} follows. */
    public void stopPlayback() {
        startPending = false;
        mainHandler.removeCallbacks(pendingStart);
        stopStream();
    }

    /**
     * Starts the pending stream as soon as it can actually run: there has to be
     * a texture to draw on, and no session of ours still winding down (whose
     * callbacks would otherwise land on this one). Both cases come back here —
     * from {@code onSurfaceTextureAvailable} and from {@link #sessionEnded}.
     */
    private void maybeStart() {
        if (!startPending || surface == null) {
            return;
        }
        if (processor.isStarted()) {
            // Replacing a running stream (a reconnect, or another camera in a
            // recycled cell): end it and continue once it says it is done.
            stopStream();
            armPendingStart();
            return;
        }
        if (pendingGoodbyes > 0) {
            armPendingStart();
            return;
        }
        startPending = false;
        startStream();
    }

    private void armPendingStart() {
        mainHandler.removeCallbacks(pendingStart);
        mainHandler.postDelayed(pendingStart, STOP_TIMEOUT_MS);
    }

    public boolean isPlaying() {
        return processor.isStarted() || startPending;
    }

    /**
     * A session reached its end — on its own, or because we stopped it. Runs on
     * the main thread; each session ends exactly once from the app's point of
     * view (see {@link #errorReported}).
     */
    private void sessionEnded(boolean unauthorized) {
        boolean ourOwnStop = pendingGoodbyes > 0;
        if (ourOwnStop) {
            pendingGoodbyes--;
        }

        if (startPending) {
            // This is the session a pending stream was waiting for.
            mainHandler.removeCallbacks(pendingStart);
            maybeStart();
            return;
        }
        // Our own teardown, or an end already reported: nothing to say.
        if (ourOwnStop || errorReported) {
            return;
        }
        errorReported = true;
        if (listener != null) {
            listener.onError(unauthorized);
        }
    }

    private void startStream() {
        if (uri == null || surface == null) {
            return;
        }
        // A session starts clean; by here the previous one has said goodbye.
        errorReported = false;

        processor.init(uri, username, password, null, RtspProcessor.DEFAULT_SOCKET_TIMEOUT);
        processor.setVolume(volumePercent / 100f);
        processor.start(true, requestAudio, false);
    }

    private void stopStream() {
        if (!processor.isStarted()) {
            return;
        }
        pendingGoodbyes++;
        processor.stop();
    }

    /** {@code host[:port]} of {@code uri}, i.e. its authority minus the credentials. */
    private static String hostAndPort(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        return uri.getPort() == -1 ? host : host + ":" + uri.getPort();
    }

    public void mute(boolean muted) {
        setVolumePercent(muted ? 0 : 100);
    }

    /** Sets the playback volume on the app's 0–100 scale (the library takes a 0..1 gain). */
    public void setVolumePercent(int percent) {
        volumePercent = Math.max(0, Math.min(100, percent));
        processor.setVolume(volumePercent / 100f);
    }

    /**
     * The frame on screen, or {@code null} if there is nothing to grab yet. The
     * picture comes out whole and unzoomed, at view resolution — the zoom is a
     * transform of this view, not of the stream.
     */
    public Bitmap snapshot() {
        if (!isAvailable() || getWidth() <= 0 || getHeight() <= 0) {
            return null;
        }
        return getBitmap();
    }

    private void notifyConnecting() {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onConnecting();
            }
        });
    }

    /** Re-fits for a stream whose picture size just became known. */
    private void setVideoSize(int width, int height) {
        if (width <= 0 || height <= 0 || (videoWidth == width && videoHeight == height)) {
            return;
        }
        videoWidth = width;
        videoHeight = height;
        applyContentTransform();
    }

    /**
     * Magnifies the picture inside the view instead of magnifying the view: the
     * bounds never change, so a zoomed mosaic cell cannot reach over its
     * neighbours. Pan is in unzoomed view pixels, like a scroll offset.
     */
    @Override
    public void onVideoTransform(float scale, float panX, float panY) {
        zoomScale = scale;
        zoomPanX = panX;
        zoomPanY = panY;
        applyContentTransform();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // The fit and the zoom pivot are both relative to bounds that just
        // changed — which is the whole reason this lives in the matrix and not
        // in onMeasure. The view always fills what the layout gives it, so
        // there is no measurement of ours left to go stale behind a rotation.
        applyContentTransform();
    }

    /**
     * Fits the picture inside the view and applies the digital zoom, in one
     * matrix over the <b>content</b>: the view keeps whatever bounds the layout
     * gave it. A stream that isn't shaped like its cell is letterboxed over the
     * parent's background (hence {@code setOpaque(false)}) instead of stretched,
     * and a magnified cell still cannot reach over its neighbours.
     */
    private void applyContentTransform() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        int pictureWidth = videoWidth > 0 ? videoWidth : hintWidth;
        int pictureHeight = videoHeight > 0 ? videoHeight : hintHeight;

        float fitX = 1f;
        float fitY = 1f;
        if (pictureWidth > 0 && pictureHeight > 0) {
            float videoRatio = (float) pictureWidth / pictureHeight;
            float viewRatio = (float) width / height;
            if (videoRatio > viewRatio) {
                // Wider than its box: full width, bars above and below.
                fitY = viewRatio / videoRatio;
            } else {
                fitX = videoRatio / viewRatio;
            }
        }

        // Logged only when the fit itself moves — not on every frame of a
        // pinch — so it stays readable while chasing a layout that looks off.
        if (BuildConfig.DEBUG && (fitX != loggedFitX || fitY != loggedFitY)) {
            loggedFitX = fitX;
            loggedFitY = fitY;
            Log.d(TAG, "fit: view=" + width + "x" + height
                    + " decoder=" + videoWidth + "x" + videoHeight
                    + " camera=" + hintWidth + "x" + hintHeight
                    + " scale=" + fitX + "x" + fitY);
        }

        // Panning may only go as far as the picture actually overflows the
        // view, which the fit shrinks — the gesture bounds alone don't know it.
        float panX = clampPan(zoomPanX, width * fitX, width);
        float panY = clampPan(zoomPanY, height * fitY, height);

        Matrix matrix = new Matrix();
        matrix.setScale(fitX * zoomScale, fitY * zoomScale, width / 2f, height / 2f);
        matrix.postTranslate(-panX * zoomScale, -panY * zoomScale);
        setTransform(matrix);
        invalidate();
    }

    /**
     * Clamps a pan offset to however much of a {@code contentSize}-wide picture
     * falls outside a {@code viewSize}-wide view once magnified. A letterboxed
     * picture overflows less than the view is wide, and may not overflow at all
     * — then there is nothing to pan along that axis.
     */
    private float clampPan(float pan, float contentSize, float viewSize) {
        float overflow = (contentSize * zoomScale - viewSize) / (2f * zoomScale);
        if (overflow <= 0f) {
            return 0f;
        }
        return Math.max(-overflow, Math.min(overflow, pan));
    }
}
