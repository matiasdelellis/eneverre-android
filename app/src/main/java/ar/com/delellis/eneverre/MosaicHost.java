package ar.com.delellis.eneverre;

/**
 * What a {@link MosaicFragment} needs from whoever pages it ({@link MosaicActivity}):
 * the view mode its toolbar switches.
 */
public interface MosaicHost {

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
