package ar.com.delellis.eneverre;

import ar.com.delellis.eneverre.api.model.Camera;

/**
 * Implemented by {@link ViewActivity}; the camera pagers
 * ({@link LiveContainerFragment} / {@link PlaybackContainerFragment}) report
 * their current page through it so the host can keep both tabs in sync.
 */
public interface OnCameraChangeListener {
    void onCameraChanged(Camera camera, int position);
}
