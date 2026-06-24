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
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
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
        void onError(@Nullable String message);
    }

    /** Give up after this many bad items in a row (otherwise a dead stream could spin). */
    private static final int MAX_CONSECUTIVE_ERRORS = 5;

    private final ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener = null;
    private float volume = 1.0f;
    private int consecutiveErrors = 0;
    /** Playlist index already retried once after an error (so it's only retried once). */
    private int lastRetryIndex = -1;

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
                .setDefaultRequestProperties(headers);

        player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(httpFactory))
                .build();

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (listener == null) return;
                if (state == Player.STATE_BUFFERING) {
                    listener.onBuffering(true);
                } else if (state == Player.STATE_READY) {
                    consecutiveErrors = 0; // a healthy item clears the skip counter
                    lastRetryIndex = -1;   // ...and lets the next failure retry afresh
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
                if (listener != null) {
                    listener.onError(error.getMessage());
                }

                int index = player.getCurrentMediaItemIndex();

                // First failure on this chunk: retry it once in place (a transient
                // 502 often succeeds on a second try, and this avoids skipping the
                // ~10s of footage the chunk holds).
                if (index != lastRetryIndex) {
                    lastRetryIndex = index;
                    player.prepare(); // recover the player out of the error/idle state
                    return;
                }

                // Retried and it failed again: a single bad chunk shouldn't kill
                // the whole session, so skip past it and resume on the next one.
                // Bail only if too many fail back-to-back.
                consecutiveErrors++;
                if (consecutiveErrors <= MAX_CONSECUTIVE_ERRORS && player.hasNextMediaItem()) {
                    player.seekToNextMediaItem();
                    player.prepare(); // recover the player out of the error/idle state
                }
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
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
        consecutiveErrors = 0;
        lastRetryIndex = -1;
        player.setMediaItems(items, startIndex, startPositionMs);
        player.prepare();
        player.setPlayWhenReady(true);
        startProgress();
    }

    /** Appends more items to the end of the current playlist (gapless continuation). */
    public void addMediaItems(List<MediaItem> items) {
        player.addMediaItems(items);
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
