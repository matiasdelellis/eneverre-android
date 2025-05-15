package ar.com.delellis.eneverre.player;

import android.content.Context;
import android.net.Uri;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

public class VlcPlayer {
    private static VlcPlayer vlcPlayer;

    private LibVLC libVlc = null;
    private MediaPlayer mediaPlayer = null;
    protected Media currentMedia = null;

    private int volume = 100;

    private VlcPlayer(Context context) {
        libVlc = new LibVLC(context);
        mediaPlayer = new MediaPlayer(libVlc);
    }

    public static VlcPlayer getInstance(Context context) {
        if (vlcPlayer == null) {
            vlcPlayer = new VlcPlayer(context);
        }
        return vlcPlayer;
    }

    public void release() {
        if(vlcPlayer == null)
            return;

        if (currentMedia != null) {
            currentMedia.release();
            currentMedia = null;
        }

        mediaPlayer.detachViews();
        mediaPlayer.release();

        vlcPlayer = null;
    }

    public void attachView(VLCVideoLayout videoLayout) {
        mediaPlayer.attachViews(videoLayout, null, false, false);
    }
    public void detachViews() {
        mediaPlayer.detachViews();
    }

    public void setEventListener(MediaPlayer.EventListener eventListener) {
        mediaPlayer.setEventListener(eventListener);
    }

    public void playUri(Uri uri) {
        currentMedia = new Media(vlcPlayer.libVlc, uri);

        currentMedia.setHWDecoderEnabled(false, false);
        mediaPlayer.setMedia(currentMedia);
        currentMedia.release();

        mediaPlayer.play();
    }

    public void stop() {
        mediaPlayer.stop();
        if (currentMedia != null) {
            currentMedia.release();
            currentMedia = null;
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
        mediaPlayer.setVolume(volume);
    }
}
