package ar.com.delellis.eneverre.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.PlayerView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin ExoPlayer (Media3) wrapper for <b>recording playback</b>, mirroring the
 * surface of {@link VlcPlayer} so {@code PlaybackFragment} can swap one for the
 * other. Live RTSP keeps using {@link VlcPlayer} (better codec handling).
 *
 * <p>Unlike VLC — which plays a single fixed window and must be restarted at
 * each boundary (the source of the playback gaps) — this feeds ExoPlayer a
 * <b>playlist</b> of consecutive segments. ExoPlayer pre-buffers the next item
 * and transitions into it without tearing down the pipeline, so consecutive
 * recordings play back <b>gapless</b>.
 *
 * <p>Credentials are sent via the {@code Authorization} header on the HTTP
 * data source (not inline in the URL), so URLs are safe to build/log.
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
        /** Playback advanced to a new playlist item (gapless boundary). */
        void onItemTransition(int itemIndex);
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

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (listener != null && player.isPlaying()) {
                listener.onProgress(player.getCurrentMediaItemIndex(), player.getCurrentPosition());
            }
            handler.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    public Media3Player(Context context, @Nullable String authorizationHeader) {
        Map<String, String> headers = new HashMap<>();
        if (authorizationHeader != null) {
            headers.put("Authorization", authorizationHeader);
        }
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                // The backend assembles each window on demand and can be slow to
                // respond to a freshly-seeked position; the 8s default times out too
                // eagerly while scrubbing.
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(headers);

        // Keep ExoPlayer's own load retries low: a 502 here usually means a real
        // recording gap, so the controller's recovery (probe forward / skip) should
        // drive the response instead of ExoPlayer hammering the same dead request.
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(httpFactory)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(1));

        player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();

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
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (listener != null) {
                    listener.onItemTransition(player.getCurrentMediaItemIndex());
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
        player.setMediaItems(items, startIndex, startPositionMs);
        player.prepare();
        player.setPlayWhenReady(true);
        startProgress();
    }

    /** Appends more items to the end of the current playlist (gapless continuation). */
    public void addMediaItems(List<MediaItem> items) {
        player.addMediaItems(items);
    }

    /**
     * Advances into the next item and resumes, if there is one. Used to recover
     * from {@code STATE_ENDED} after more items were appended, since ExoPlayer
     * stays ended (frozen on the last frame) until told to move on.
     */
    public void continueToNextIfAvailable() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem();
            player.play();
        }
    }

    public int getCurrentIndex() {
        return player.getCurrentMediaItemIndex();
    }

    /** Replaces the item at {@code index} (e.g. to rebuild a chunk from a new start). */
    public void replaceItem(int index, MediaItem item) {
        player.replaceMediaItem(index, item);
    }

    /** Drops every item after {@code index} (used when the plan past it is rebuilt). */
    public void removeAfter(int index) {
        int count = player.getMediaItemCount();
        if (index + 1 < count) {
            player.removeMediaItems(index + 1, count);
        }
    }

    /**
     * Jumps to the given playlist item (start of it) and resumes playing,
     * recovering the player if it was left in an error/idle state.
     */
    public void seekToItem(int index) {
        player.seekTo(index, 0);
        player.prepare(); // required to leave STATE_IDLE after an error
        player.play();
    }

    public void stop() {
        stopProgress();
        player.stop();
        player.clearMediaItems();
    }

    public boolean isPaused() {
        return !player.getPlayWhenReady();
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
