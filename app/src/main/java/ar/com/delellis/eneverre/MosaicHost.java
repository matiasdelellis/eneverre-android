package ar.com.delellis.eneverre;

import org.videolan.libvlc.LibVLC;

/**
 * What a {@link MosaicFragment} needs from whoever pages it ({@link MosaicActivity}):
 * the shared playback engine and the view mode its toolbar switches.
 */
public interface MosaicHost {

    /**
     * The single shared {@link LibVLC} every page's grid runs on (one native
     * engine for the whole activity, not one per location).
     */
    LibVLC getSharedLibVlc();

    /** Current view mode: fit the whole grid on screen, or let it scroll. */
    boolean isMosaicFitToScreen();

    /** Only the resumed page registers, and it alone gets toolbar toggles. */
    void registerLayoutModeListener(LayoutModeListener listener);

    void unregisterLayoutModeListener(LayoutModeListener listener);

    /** Notified when the host's toolbar switches the view mode. */
    interface LayoutModeListener {
        void onFitToScreenChanged(boolean fitToScreen);
    }
}
