package ar.com.delellis.eneverre.player;

import android.content.Context;
import android.net.Uri;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;

public class VlcPlayer {

    private LibVLC libVlc = null;
    private MediaPlayer mediaPlayer = null;
    protected Media currentMedia = null;

    // Whether this player created its own LibVLC (and must release it) or was
    // handed a shared one (owned by the caller, e.g. the mosaic). A shared
    // instance is released by whoever created it, not by each player.
    private final boolean ownsLibVlc;

    private int volume = 100;

    /** Standalone player: creates and owns its own {@link LibVLC} instance. */
    public VlcPlayer(Context context) {
        this(newLibVlc(context), true);
    }

    /**
     * Player over a shared {@link LibVLC}, for running several streams at once
     * (the camera mosaic) without one native engine per cell. The caller owns
     * the passed instance and is responsible for releasing it.
     */
    public VlcPlayer(LibVLC sharedLibVlc) {
        this(sharedLibVlc, false);
    }

    private VlcPlayer(LibVLC libVlc, boolean ownsLibVlc) {
        this.libVlc = libVlc;
        this.ownsLibVlc = ownsLibVlc;
        this.mediaPlayer = new MediaPlayer(libVlc);
    }

    /** Builds a configured {@link LibVLC}; reused for both standalone and shared use. */
    public static LibVLC newLibVlc(Context context) {
        ArrayList<String> options = new ArrayList<String>();
        options.add("--quiet");
        options.add("--network-caching=50");
        options.add("--no-drop-late-frames");
        options.add("--no-skip-frames");
        options.add("--rtsp-tcp");
        return new LibVLC(context, options);
    }

    public void release() {
        if (currentMedia != null) {
            currentMedia.release();
            currentMedia = null;
        }

        if (mediaPlayer != null) {
            mediaPlayer.detachViews();
            mediaPlayer.release();
            mediaPlayer = null;
        }

        // The LibVLC instance owns native resources too; without releasing it the
        // finalizer trips "LibVLC finalized but not natively released (1 refs)".
        // Only release it when this player created it — a shared instance is
        // freed by its owner once every cell has been torn down.
        if (libVlc != null && ownsLibVlc) {
            libVlc.release();
            libVlc = null;
        }
    }

    public void attachView(VLCVideoLayout videoLayout) {
        if (mediaPlayer != null) {
            mediaPlayer.attachViews(videoLayout, null, false, false);
        }
    }

    public void detachViews() {
        if (mediaPlayer != null) {
            mediaPlayer.detachViews();
        }
    }

    public void setEventListener(MediaPlayer.EventListener eventListener) {
        if (mediaPlayer != null) {
            mediaPlayer.setEventListener(eventListener);
        }
    }

    public void playUri(Uri uri) {
        if (mediaPlayer == null) {
            return;
        }
        currentMedia = new Media(libVlc, uri);
        currentMedia.setHWDecoderEnabled(true, false);
        mediaPlayer.setMedia(currentMedia);
        // setMedia takes its own reference, so drop ours right away and clear the
        // field: keeping it non-null would let a later stop()/release() free the
        // same native Media a second time (over-release → native crash).
        currentMedia.release();
        currentMedia = null;

        mediaPlayer.play();
    }

    public void stop() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
        }
        if (currentMedia != null) {
            currentMedia.release();
            currentMedia = null;
        }
    }

    public boolean isPaused () {
        return mediaPlayer == null || !mediaPlayer.isPlaying();
    }

    public void pause () {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
        }
    }

    public void resume () {
        if (mediaPlayer != null) {
            mediaPlayer.play();
        }
    }

    public boolean isMuted () {
        return 0 == getVolume();
    }

    public void mute(boolean muted) {
        setVolume(muted ? 0 : 100);
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(volume);
        }
    }

    /** Sets the playback speed (1.0 = normal, 2.0 = double). */
    public void setRate(float rate) {
        if (mediaPlayer != null) {
            mediaPlayer.setRate(rate);
        }
    }
}
