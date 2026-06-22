package ar.com.delellis.eneverre;

/**
 * Shares the currently-watched playback time across the {@link PlaybackFragment}
 * pages of {@link PlaybackContainerFragment}, so swiping to another camera
 * resumes at the same moment. Implemented by the container; pages reach it via
 * {@code getParentFragment()}.
 */
public interface PlaybackTimeHost {
    /** Last watched time in epoch millis, or 0 if none yet. */
    long getSharedPlaybackTime();

    void setSharedPlaybackTime(long timeMs);
}
