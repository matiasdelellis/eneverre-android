package ar.com.delellis.eneverre.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.PlayerView;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ar.com.delellis.eneverre.api.ApiClient;
import okhttp3.OkHttpClient;

/**
 * Thin ExoPlayer (Media3) wrapper for <b>recording playback</b>. Live RTSP is
 * not played through here but by {@link RtspPlayerView}.
 *
 * <p>Unlike VLC — the player this replaced, which plays a single fixed window
 * and must be restarted at
 * each boundary (the source of the playback gaps) — this feeds ExoPlayer a
 * <b>playlist</b> of consecutive segments. ExoPlayer pre-buffers the next item
 * and transitions into it without tearing down the pipeline, so consecutive
 * recordings play back <b>gapless</b>.
 *
 * <p>Playback goes through the app's OkHttp client, so the Bearer token is
 * stamped per request by {@code BearerInterceptor} — a rotated token is picked
 * up without recreating the player — and a 401 is refreshed and retried by
 * {@code TokenAuthenticator}. OkHttp also drops the {@code Authorization} header
 * when a redirect changes host, port or scheme, which the default Media3 data
 * source does not do (it replays its default request properties to the redirect
 * target). URLs stay free of inline credentials.
 */
@OptIn(markerClass = UnstableApi.class)
public class Media3Player {

    /** Position is polled (ExoPlayer has no per-frame position callback). */
    private static final long PROGRESS_INTERVAL_MS = 200L;

    public interface Listener {
        /** Buffering spinner on/off. */
        void onBuffering(boolean buffering);
        /** Current playlist item + position within it (poll-driven). */
        void onProgress(int itemIndex, long positionMs);
        /** Reached the end of the whole playlist. */
        void onEnded();
        /**
         * A load failed. {@code httpCode} is the HTTP status if it was an HTTP
         * error ({@code -1} otherwise); {@code nextAvailable} is the value of the
         * server's {@code x-next-available} header when present (the next servable
         * timestamp).
         */
        void onError(int httpCode, @Nullable String nextAvailable, @Nullable String message);

    }

    private final ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener = null;
    private float volume = 1.0f;

    /**
     * Presentation time (µs) of the last video frame handed to the renderer, or
     * -1 when the current playlist has not rendered one. Recordings whose video
     * stalled while the audio kept going have long stretches with no video at
     * all; the UI uses this to warn instead of showing a black picture.
     */
    private volatile long lastVideoFrameUs = -1;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (listener != null && player.isPlaying()) {
                listener.onProgress(player.getCurrentMediaItemIndex(), player.getCurrentPosition());
            }
            handler.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    public Media3Player(Context context) {
        // Share the app's OkHttp client: BearerInterceptor stamps the token per
        // request (so a rotation is picked up mid-session), TokenAuthenticator
        // refreshes on a 401, and OkHttp strips the header on a cross-host
        // redirect. The backend assembles each window on demand and can be slow
        // to answer a freshly-seeked position, so the timeouts are wider than
        // the client defaults.
        OkHttpClient client = ApiClient.getInstance().getHttpClient().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        OkHttpDataSource.Factory httpFactory = new OkHttpDataSource.Factory(client);

        // Keep ExoPlayer's own load retries low: a 502 here usually means a real
        // recording gap, so the controller's recovery (probe forward / skip) should
        // drive the response instead of ExoPlayer hammering the same dead request.
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(httpFactory)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(1));

        player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();

        // Fires for every frame actually handed to the video renderer. The
        // presentation time is in the same timeline as getCurrentPosition().
        player.setVideoFrameMetadataListener((presentationTimeUs, releaseTimeNs, format, mediaFormat) ->
                lastVideoFrameUs = presentationTimeUs);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (listener == null) return;
                if (state == Player.STATE_BUFFERING) {
                    listener.onBuffering(true);
                } else if (state == Player.STATE_READY) {
                    listener.onBuffering(false);
                } else if (state == Player.STATE_ENDED) {
                    listener.onBuffering(false);
                    listener.onEnded();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                // Mechanism only: surface the HTTP status and the server's
                // x-next-available hint; the owner decides how to recover (jump,
                // probe, stop). Recovery is driven via seekToItem()/prepare().
                int httpCode = -1;
                String nextAvailable = null;
                for (Throwable t = error; t != null; t = t.getCause()) {
                    if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                        HttpDataSource.InvalidResponseCodeException http =
                                (HttpDataSource.InvalidResponseCodeException) t;
                        httpCode = http.responseCode;
                        nextAvailable = firstHeader(http.headerFields, "x-next-available");
                        break;
                    }
                }
                if (listener != null) {
                    listener.onError(httpCode, nextAvailable, error.getMessage());
                }
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** First value of {@code name} in {@code headers}, matched case-insensitively, or null. */
    @Nullable
    private static String firstHeader(@Nullable Map<String, List<String>> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                List<String> values = entry.getValue();
                return (values != null && !values.isEmpty()) ? values.get(0) : null;
            }
        }
        return null;
    }

    public void attachView(PlayerView view) {
        view.setPlayer(player);
    }

    public void detachView(PlayerView view) {
        view.setPlayer(null);
    }

    /**
     * Loads a playlist and starts playing at {@code startIndex} / {@code startPositionMs}.
     * The remaining items play gapless after it.
     */
    public void setPlaylist(List<MediaItem> items, int startIndex, long startPositionMs) {
        // A new window starts without having rendered anything yet.
        lastVideoFrameUs = -1;
        player.setMediaItems(items, startIndex, startPositionMs);
        player.prepare();
        player.setPlayWhenReady(true);
        startProgress();
    }

    public void stop() {
        stopProgress();
        player.stop();
        player.clearMediaItems();
        // stop() leaves playWhenReady alone, which made isPaused() report a
        // player with no playlist as "playing".
        player.setPlayWhenReady(false);
        lastVideoFrameUs = -1;
    }

    /** Presentation time (µs) of the last rendered video frame, or -1 if none yet. */
    public long getLastVideoFrameUs() {
        return lastVideoFrameUs;
    }

    /**
     * Drops the video track so a recording whose video is missing (the camera
     * stalled it while audio kept going) can still play its audio instead of
     * sitting in buffering forever waiting for a frame that will never come.
     */
    public void disableVideo() {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build());
    }

    /** Re-enables the video track (a new window may have it). */
    public void enableVideo() {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .build());
    }

    public boolean isPaused() {
        return !player.getPlayWhenReady();
    }

    /** Whether the player is actually playing (not paused, buffering or empty). */
    public boolean isPlaying() {
        return player.isPlaying();
    }

    public void pause() {
        player.setPlayWhenReady(false);
    }

    public void resume() {
        player.setPlayWhenReady(true);
    }

    public boolean isMuted() {
        return volume == 0f;
    }

    public void mute(boolean muted) {
        setVolume(muted ? 0f : 1f);
    }

    public void setVolume(float volume) {
        this.volume = volume;
        player.setVolume(volume);
    }

    /** Sets the playback speed (1.0 = normal, 2.0 = double). */
    public void setRate(float rate) {
        player.setPlaybackSpeed(rate);
    }

    public void release() {
        stopProgress();
        player.release();
    }

    private void startProgress() {
        handler.removeCallbacks(progressTick);
        handler.post(progressTick);
    }

    private void stopProgress() {
        handler.removeCallbacks(progressTick);
    }
}
