package ar.com.delellis.eneverre.player;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.util.Time;
import ar.com.delellis.eneverre.widget.TimelineView.TimeRecord;

/**
 * Drives gapless recording playback for a single camera on top of
 * {@link Media3Player}.
 *
 * <p>It walks the available recordings with a cursor, feeding the player small
 * fixed-length chunks ({@link #CHUNK_MS}) as a lazily-grown playlist so they play
 * back gapless, and reports the absolute playback time to the UI via
 * {@link Listener}.
 *
 * <p>Recovery is driven by the backend's error responses:
 * <ul>
 *   <li><b>404</b> — no recording at that instant (a gap between segments). Jump
 *       to the {@code x-next-available} moment the server reports, or to the start
 *       of the next recording if it gives no hint.</li>
 *   <li><b>transport timeout</b> — the backend is slow to serve a freshly-seeked
 *       window; retry the same chunk a few times silently.</li>
 *   <li><b>5xx / other</b> — a real failure: stop and ask the UI to offer a retry
 *       (see {@link Listener#onRetryableError()} and {@link #retry()}).</li>
 * </ul>
 */
public class GaplessPlaybackController implements Media3Player.Listener {

    private static final String TAG = "GaplessPlayback";

    /** Nominal length of each chunk request. */
    private static final long CHUNK_MS = 10_000L;
    /** Silent retries of a chunk on a transport timeout before prompting the user. */
    private static final int MAX_TRANSIENT_RETRIES = 3;
    /** Chunks queued up front when playback starts. */
    private static final int INITIAL_CHUNKS = 18; // ~3 min
    /** How many chunks to keep queued ahead of the current one. */
    private static final int AHEAD_CHUNKS = 12; // ~2 min

    /** Reports playback state back to the UI. */
    public interface Listener {
        void onBuffering(boolean buffering);
        /** The absolute moment (ms) currently being played. */
        void onPositionMs(long absoluteMs);
        /** A real server error stopped playback; the UI should offer to retry. */
        void onRetryableError();
        /** Playback reached the end of the available footage (nothing left to play). */
        void onPlaybackEnded();
    }

    private final Media3Player player;
    private final String cameraId;
    private final Listener listener;

    /** Recordings to play, ascending by start. */
    private List<TimeRecord> recordings = new ArrayList<>();
    /** Cursor: next chunk starts here, within recording {@link #cursorRec}. */
    private int cursorRec = 0;
    private long cursorMs = 0;
    /** [startMs, durationMs] of each chunk currently in the player, by item index. */
    private final List<long[]> queued = new ArrayList<>();
    /** Moment a real (non-gap) error stopped at, so {@link #retry()} can resume it. */
    private long lastErrorMs = -1;
    /** Consecutive transport-timeout retries on the current chunk; reset on progress. */
    private int transientRetries = 0;

    public GaplessPlaybackController(Context context, @Nullable String authorizationHeader,
                                     String cameraId, Listener listener) {
        this.cameraId = cameraId;
        this.listener = listener;
        this.player = new Media3Player(context, authorizationHeader);
        this.player.setListener(this);
    }

    public void attach(PlayerView view) {
        player.attachView(view);
    }

    public void detach(PlayerView view) {
        player.detachView(view);
    }

    /**
     * Starts gapless playback from {@code timeMs} over the given recordings.
     * Returns {@code false} if there is no footage to play from that moment.
     */
    public boolean playFrom(List<TimeRecord> recs, long timeMs) {
        recordings = new ArrayList<>(recs);
        Collections.sort(recordings, (a, b) -> Long.compare(a.timestampMsec, b.timestampMsec));
        return startAt(timeMs);
    }

    /** Re-attempts playback from where a retryable error left off. */
    public void retry() {
        if (lastErrorMs > 0) {
            startAt(lastErrorMs);
        }
    }

    /** (Re)builds the queue from {@code timeMs} over the current recordings. */
    private boolean startAt(long timeMs) {
        setCursor(timeMs);
        queued.clear();
        transientRetries = 0;
        lastErrorMs = -1;
        List<MediaItem> items = new ArrayList<>();
        for (int k = 0; k < INITIAL_CHUNKS; k++) {
            long[] chunk = nextChunk();
            if (chunk == null) {
                break;
            }
            queued.add(chunk);
            items.add(buildItem(chunk[0], chunk[1]));
        }
        if (items.isEmpty()) {
            return false;
        }
        // First chunk starts exactly at timeMs, so no per-item seek is needed.
        player.setPlaylist(items, 0, 0);
        return true;
    }

    public void stop() {
        player.stop();
    }

    public boolean isPaused() {
        return player.isPaused();
    }

    public void pause() {
        player.pause();
    }

    public void resume() {
        player.resume();
    }

    public boolean isMuted() {
        return player.isMuted();
    }

    public void mute(boolean muted) {
        player.mute(muted);
    }

    public void setRate(float rate) {
        player.setRate(rate);
    }

    public void release() {
        player.release();
    }

    // --- Media3Player.Listener ---

    @Override
    public void onBuffering(boolean buffering) {
        listener.onBuffering(buffering);
    }

    @Override
    public void onProgress(int itemIndex, long positionMs) {
        // Actually playing again: the last spot was servable, so reset recovery state.
        transientRetries = 0;
        if (itemIndex < 0 || itemIndex >= queued.size()) {
            return;
        }
        listener.onPositionMs(queued.get(itemIndex)[0] + positionMs);
    }

    @Override
    public void onItemTransition(int itemIndex) {
        // Keep a fixed look-ahead queued so playback continues seamlessly.
        enqueueUpTo(itemIndex + 1 + AHEAD_CHUNKS);
    }

    @Override
    public void onEnded() {
        // The queue can run dry before the recordings do; top it up and keep going
        // instead of freezing on a black frame.
        int before = queued.size();
        enqueueUpTo(before + AHEAD_CHUNKS);
        if (queued.size() > before) {
            player.continueToNextIfAvailable();
        } else {
            listener.onBuffering(false); // genuine end of available footage
            listener.onPlaybackEnded();
        }
    }

    @Override
    public void onError(int httpCode, @Nullable String nextAvailable, @Nullable String message) {
        int index = player.getCurrentIndex();
        if (index < 0 || index >= queued.size()) {
            return;
        }
        long failedStart = queued.get(index)[0];

        // 404 = "no recording at this instant" (a gap / segment edge). The server
        // tells us the next servable moment in x-next-available: jump straight to it.
        if (httpCode == 404) {
            long ms = (nextAvailable != null) ? Time.RFC3339toMS(nextAvailable) : -1;
            if (ms > failedStart) {
                Log.w(TAG, "No segment at #" + index + ", jumping to x-next-available " + nextAvailable);
                rebuildFrom(index, ms);
                return;
            }
            // No usable hint: fall back to the start of the next recording.
            long nextStart = nextRecordingStartAfter(failedStart);
            if (nextStart < 0) {
                listener.onBuffering(false); // nothing servable left
                listener.onPlaybackEnded();
                return;
            }
            Log.w(TAG, "No segment at #" + index + " and no hint, jumping to next recording @ " + Time.MStoRFC3339(nextStart));
            rebuildFrom(index, nextStart);
            return;
        }

        // Transport timeout (no HTTP status): usually transient — the backend is
        // slow to serve a freshly-seeked window. Retry the same chunk silently a
        // few times before bothering the user.
        if (httpCode < 0 && transientRetries < MAX_TRANSIENT_RETRIES) {
            transientRetries++;
            Log.w(TAG, "Transient timeout, retry " + transientRetries + "/" + MAX_TRANSIENT_RETRIES + ": " + message);
            player.seekToItem(index); // re-request the same chunk
            return;
        }

        // A 5xx, or a timeout that won't clear: a real problem. Stop and let the UI
        // offer to retry.
        Log.e(TAG, "Playback error (http " + httpCode + "): " + message);
        transientRetries = 0;
        lastErrorMs = failedStart;
        listener.onBuffering(false);
        listener.onRetryableError();
    }

    /**
     * Repoints the cursor at {@code newStart} and rebuilds the player queue from
     * {@code index} onward (everything after it assumed the old window).
     */
    private void rebuildFrom(int index, long newStart) {
        setCursor(newStart);
        long[] chunk = nextChunk();
        if (chunk == null) {
            listener.onBuffering(false);
            return;
        }
        player.removeAfter(index);
        if (queued.size() > index + 1) {
            queued.subList(index + 1, queued.size()).clear();
        }
        player.replaceItem(index, buildItem(chunk[0], chunk[1]));
        queued.set(index, chunk);
        player.seekToItem(index);
        enqueueUpTo(index + 1 + AHEAD_CHUNKS);
    }

    // --- chunk planning ---

    /** Points the cursor at {@code ms}, in the recording that contains it (or the next). */
    private void setCursor(long ms) {
        cursorRec = 0;
        while (cursorRec < recordings.size()
                && recordings.get(cursorRec).timestampMsec + recordings.get(cursorRec).durationMsec <= ms) {
            cursorRec++;
        }
        cursorMs = ms;
    }

    /**
     * Returns the next chunk {@code [startMs, durationMs]} and advances the cursor,
     * or {@code null} when there are no recordings left. Chunks never span a gap:
     * the cursor jumps to the next recording's start when it runs off the end.
     */
    private long[] nextChunk() {
        while (cursorRec < recordings.size()) {
            TimeRecord rec = recordings.get(cursorRec);
            long end = rec.timestampMsec + rec.durationMsec;
            if (cursorMs >= end) {
                cursorRec++;
                if (cursorRec < recordings.size()) {
                    cursorMs = recordings.get(cursorRec).timestampMsec;
                }
                continue;
            }
            long start = Math.max(cursorMs, rec.timestampMsec);
            long duration = Math.min(CHUNK_MS, end - start);
            cursorMs = start + duration;
            return new long[]{start, duration};
        }
        return null;
    }

    /** Start (ms) of the first recording that begins after {@code ms}, or -1 if none. */
    private long nextRecordingStartAfter(long ms) {
        for (TimeRecord rec : recordings) {
            if (rec.timestampMsec > ms) {
                return rec.timestampMsec;
            }
        }
        return -1;
    }

    /** Grows the queue until it holds {@code target} items (or the recordings run out). */
    private void enqueueUpTo(int target) {
        while (queued.size() < target) {
            long[] chunk = nextChunk();
            if (chunk == null) {
                break;
            }
            queued.add(chunk);
            player.addMediaItems(Collections.singletonList(buildItem(chunk[0], chunk[1])));
        }
    }

    private MediaItem buildItem(long startMs, long durationMs) {
        String url = ApiClient.getInstance().getPlaybackStreamUrl(
                cameraId, Time.MStoRFC3339(startMs), durationMs / 1000.0);
        return MediaItem.fromUri(url);
    }
}
