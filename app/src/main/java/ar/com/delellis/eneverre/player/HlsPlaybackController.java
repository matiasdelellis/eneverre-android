package ar.com.delellis.eneverre.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Recording;
import ar.com.delellis.eneverre.util.Time;
import ar.com.delellis.eneverre.widget.TimelineView.TimeRecord;

/**
 * Recording playback over the server's HLS VOD surface.
 *
 * <p>The server builds a CMAF playlist for a {@code [start, end]} window with
 * coverage gaps collapsed and {@code EXT-X-PROGRAM-DATE-TIME} on every segment,
 * so ExoPlayer plays the whole window natively: no per-10 s chunk requests, no
 * manual gapless stitching, and a seek is simply a new playlist at the target
 * moment.
 *
 * <p>The playlist's presentation time is <b>not</b> wall-clock: gaps are
 * collapsed, so after a gap the presentation time lags the real time. The
 * controller therefore keeps the segment list the window was built from and
 * maps the player position through it, which keeps the timeline cursor and the
 * shared moment exact across gaps.
 *
 * <p>The window is six hours. When playback reaches its end and more footage is
 * loaded after it, the controller opens a new window from there; only when the
 * footage really runs out does it report the end.
 */
public class HlsPlaybackController implements Media3Player.Listener {
    private static final String TAG = "HlsPlaybackController";

    /**
     * Playlist span requested per seek. The server caps a playlist at seven
     * days; six hours keeps the playlist small (~2000 segments of 10 s) while
     * covering a typical viewing session without a reload.
     */
    private static final long WINDOW_MS = 6 * 60 * 60 * 1000L;

    /**
     * How long playback may advance without a rendered video frame before the UI
     * warns that this part of the recording has audio only. Covers the normal
     * delay until the first frame after a seek.
     */
    private static final long VIDEO_GRACE_MS = 3_000L;

    public interface Listener {
        /** Buffering spinner on/off. */
        void onBuffering(boolean buffering);

        /** Absolute wall-clock position of playback, in ms. */
        void onPositionMs(long absoluteMs);

        /** Reached the end of the available footage. */
        void onPlaybackEnded();

        /** A real failure: stop and let the UI offer a retry. */
        void onRetryableError();

        /**
         * Playback is advancing but no video frame has been rendered for a
         * while: the recording's video stalled while its audio kept going.
         * Nothing is broken; the UI should warn instead of showing black.
         */
        void onVideoMissing(boolean missing);
    }

    private final String cameraId;
    private final Listener listener;
    private final Media3Player player;

    /** Full segment list the current window was taken from, sorted by start. */
    private final List<TimeRecord> recordings = new ArrayList<>();

    /** The window's slice of {@link #recordings}, i.e. what the playlist holds. */
    private final List<TimeRecord> playlist = new ArrayList<>();

    /** Wall-clock end of the current window. */
    private long windowEndMs = 0;
    /** Last absolute moment reported (retry/extension anchor). */
    private long lastPositionMs = 0;
    private long lastErrorMs = -1;

    /** When the current window started, for the audio-only grace period. */
    private long windowStartedAt = 0;
    /** Whether the UI was last told the video is missing (edge-triggered). */
    private boolean videoMissing = false;
    /** Whether the player's video track is currently enabled. */
    private boolean videoTrackEnabled = true;
    /** The segment whose video extent was last applied, and whether it has video. */
    @Nullable
    private TimeRecord gatedRecord = null;
    private boolean gatedNoVideo = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** True while the player reports buffering. */
    private boolean buffering = false;

    /**
     * A segment whose video never arrives can also leave the player stuck
     * buffering (the audio renderer rejects the stretched timestamps). The
     * overlay is shown anyway after a grace, so the black screen is explained
     * instead of looking like a hang.
     */
    private final Runnable stallCheck;

    public HlsPlaybackController(Context context, String cameraId, Listener listener) {
        this.cameraId = cameraId;
        this.listener = listener;
        this.player = new Media3Player(context);
        this.player.setListener(this);
        this.stallCheck = () -> {
            if (!buffering || videoMissing) {
                return;
            }
            if (player.getLastVideoFrameUs() >= 0) {
                return; // a frame was rendered; this is just a slow load
            }
            // No video since the window started: drop the video track so the
            // audio can advance, and explain the black picture instead of spinning.
            Log.w(TAG, "No video frame while buffering; continuing with audio only");
            player.disableVideo();
            videoTrackEnabled = false;
            player.resume();
            videoMissing = true;
            listener.onVideoMissing(true);
        };
    }

    public void attach(PlayerView view) {
        player.attachView(view);
    }

    public void detach(PlayerView view) {
        player.detachView(view);
    }

    /**
     * Starts playback at {@code timeMs} over the given recordings. Returns
     * {@code false} if there is no footage at or after that moment.
     */
    public boolean playFrom(List<TimeRecord> records, long timeMs) {
        recordings.clear();
        recordings.addAll(records);
        Collections.sort(recordings, (a, b) -> Long.compare(a.timestampMsec, b.timestampMsec));
        if (recordings.isEmpty()) {
            return false;
        }

        // A new window may well have video (the previous one may have been an
        // audio-only stretch): re-enable it; the per-segment gate below drops
        // it again exactly on the segments the index says have no video.
        player.enableVideo();
        videoTrackEnabled = true;
        gatedRecord = null;
        gatedNoVideo = false;
        handler.removeCallbacks(stallCheck);
        buffering = false;

        TimeRecord newest = recordings.get(recordings.size() - 1);
        long loadedEnd = newest.timestampMsec + segmentDurationMs(newest);
        long end = Math.min(timeMs + WINDOW_MS, loadedEnd);
        if (end <= timeMs) {
            return false;
        }

        // Mirror the server's Index.Range(from, to): it includes the segment that
        // overlaps `timeMs` (or the next one when it falls in a gap).
        playlist.clear();
        for (TimeRecord record : recordings) {
            long recordEnd = record.timestampMsec + segmentDurationMs(record);
            if (recordEnd <= timeMs) {
                continue;
            }
            if (record.timestampMsec >= end) {
                break;
            }
            playlist.add(record);
        }
        if (playlist.isEmpty()) {
            return false;
        }

        long firstStart = playlist.get(0).timestampMsec;
        long seekMs = Math.max(0, timeMs - firstStart);
        windowEndMs = end;
        lastPositionMs = firstStart + seekMs;
        lastErrorMs = -1;
        windowStartedAt = SystemClock.elapsedRealtime();
        clearVideoMissing();
        // Gate the initial position: a seek can land in the middle of a span
        // whose video already ended (or before it starts).
        gateVideo(seekMs);

        String url = ApiClient.getInstance().getHlsPlaylistUrl(
                cameraId, Time.MStoRFC3339(timeMs), Time.MStoRFC3339(end));
        Log.i(TAG, "HLS window " + Time.MStoRFC3339(timeMs) + " .. " + Time.MStoRFC3339(end)
                + " segments=" + playlist.size() + " seek=" + seekMs);

        player.setPlaylist(Collections.singletonList(MediaItem.fromUri(url)), 0, seekMs);
        return true;
    }

    /** Re-attempts playback from where a retryable error left off. */
    public void retry() {
        if (lastErrorMs > 0) {
            playFrom(new ArrayList<>(recordings), lastErrorMs);
        }
    }

    public void stop() {
        handler.removeCallbacks(stallCheck);
        buffering = false;
        player.stop();
        clearVideoMissing();
    }

    public boolean isPaused() {
        return player.isPaused();
    }

    /** Whether a window is playing right now. */
    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isMuted() {
        return player.isMuted();
    }

    public void mute(boolean muted) {
        player.mute(muted);
    }

    public void pause() {
        player.pause();
    }

    public void resume() {
        player.resume();
    }

    public void setRate(float rate) {
        player.setRate(rate);
    }

    public void release() {
        handler.removeCallbacks(stallCheck);
        player.release();
    }

    // --- Media3Player.Listener ---

    @Override
    public void onBuffering(boolean buffering) {
        this.buffering = buffering;
        handler.removeCallbacks(stallCheck);
        if (buffering) {
            // If this is an audio-only stretch the player may never become
            // ready on its own; give it a longer grace than the progress check.
            handler.postDelayed(stallCheck, VIDEO_GRACE_MS * 2);
        }
        listener.onBuffering(buffering);
    }

    @Override
    public void onProgress(int itemIndex, long positionMs) {
        // Apply the index's video extent for the segment under the cursor: it
        // enables/disables the video track exactly at the boundaries, which is
        // what lets playback recover on its own after an audio-only span.
        gateVideo(positionMs);

        lastPositionMs = positionToWallClock(positionMs);
        listener.onPositionMs(lastPositionMs);

        // Audio can keep playing while the video track has no samples (a camera
        // that stalled its video but not its audio). Warn instead of showing a
        // black picture; the grace covers the delay until the first frame.
        boolean missing = gatedNoVideo;
        if (!missing) {
            long frameUs = player.getLastVideoFrameUs();
            if (frameUs < 0) {
                missing = SystemClock.elapsedRealtime() - windowStartedAt > VIDEO_GRACE_MS;
            } else {
                missing = positionMs * 1000L - frameUs > VIDEO_GRACE_MS * 1000L;
            }
        }
        if (missing != videoMissing) {
            videoMissing = missing;
            listener.onVideoMissing(missing);
        }
    }

    @Override
    public void onEnded() {
        clearVideoMissing();
        // The window ended. If more footage is loaded after it, open a new
        // window from there instead of reporting the end of the recordings.
        long loadedEnd = 0;
        if (!recordings.isEmpty()) {
            TimeRecord newest = recordings.get(recordings.size() - 1);
            loadedEnd = newest.timestampMsec + segmentDurationMs(newest);
        }
        if (windowEndMs > 0 && windowEndMs < loadedEnd
                && playFrom(new ArrayList<>(recordings), windowEndMs)) {
            return;
        }
        listener.onBuffering(false);
        listener.onPlaybackEnded();
    }

    @Override
    public void onError(int httpCode, @Nullable String nextAvailable, @Nullable String message) {
        clearVideoMissing();
        if (httpCode == 404) {
            // No segments in the requested window (a gap at the very end).
            listener.onBuffering(false);
            listener.onPlaybackEnded();
            return;
        }
        Log.e(TAG, "Playback error (http " + httpCode + "): " + message);
        lastErrorMs = lastPositionMs;
        listener.onBuffering(false);
        listener.onRetryableError();
    }

    /** Hides the audio-only warning if it was showing. */
    private void clearVideoMissing() {
        if (videoMissing) {
            videoMissing = false;
            listener.onVideoMissing(false);
        }
    }

    /**
     * Walks the window's segments to turn a presentation position into an
     * absolute wall-clock moment. The playlist's presentation time is the sum of
     * segment durations, with gaps collapsed.
     */
    private long positionToWallClock(long positionMs) {
        SegmentAt at = segmentAt(positionMs);
        if (at == null) {
            return lastPositionMs;
        }
        return at.record.timestampMsec + at.offsetMs;
    }

    /**
     * The segment under a presentation position and the offset inside it. The
     * segment's duration comes from the recording (the server's EXTINF), not
     * the timeline's minimum-width clamp, so the boundaries match the HLS.
     */
    @Nullable
    private SegmentAt segmentAt(long positionMs) {
        long remaining = Math.max(0, positionMs);
        for (TimeRecord record : playlist) {
            long duration = segmentDurationMs(record);
            if (remaining < duration) {
                return new SegmentAt(record, remaining);
            }
            remaining -= duration;
        }
        if (playlist.isEmpty()) {
            return null;
        }
        TimeRecord last = playlist.get(playlist.size() - 1);
        return new SegmentAt(last, segmentDurationMs(last));
    }

    private static long segmentDurationMs(TimeRecord record) {
        if (record.object instanceof Recording) {
            float duration = ((Recording) record.object).getDuration();
            if (duration > 0) {
                return Math.round(duration * 1000.0);
            }
        }
        return record.durationMsec;
    }

    /**
     * Applies the index's video extent for the segment at {@code positionMs}:
     * the video is enabled only where the segment actually has frames, and the
     * UI is warned exactly on the spans that do not. Unknown extents (an older
     * server, or a row the backfill has not measured yet) keep the video on and
     * leave the warning to the frame-based fallback.
     */
    private void gateVideo(long positionMs) {
        SegmentAt at = segmentAt(positionMs);
        if (at == null || at.record == gatedRecord) {
            return;
        }
        gatedRecord = at.record;

        Double start = null;
        Double duration = null;
        if (at.record.object instanceof Recording) {
            Recording recording = (Recording) at.record.object;
            start = recording.getVideoStart();
            duration = recording.getVideoDuration();
        }
        if (start == null || duration == null) {
            gatedNoVideo = false;
            setVideoPresent(true);
            return;
        }

        long startMs = Math.round(start * 1000.0);
        long durationMs = Math.round(duration * 1000.0);
        boolean hasVideo = durationMs > 0
                && at.offsetMs >= startMs
                && at.offsetMs < startMs + durationMs;
        gatedNoVideo = !hasVideo;
        setVideoPresent(hasVideo);
    }

    /**
     * Enables or disables the video track and keeps the audio-only warning in
     * sync. Edge-triggered: the player is only touched when the state changes.
     */
    private void setVideoPresent(boolean present) {
        if (present) {
            if (!videoTrackEnabled) {
                player.enableVideo();
                videoTrackEnabled = true;
            }
            clearVideoMissing();
        } else {
            if (videoTrackEnabled) {
                player.disableVideo();
                videoTrackEnabled = false;
            }
            if (!videoMissing) {
                videoMissing = true;
                listener.onVideoMissing(true);
            }
        }
    }

    private static final class SegmentAt {
        final TimeRecord record;
        final long offsetMs;

        SegmentAt(TimeRecord record, long offsetMs) {
            this.record = record;
            this.offsetMs = offsetMs;
        }
    }
}
