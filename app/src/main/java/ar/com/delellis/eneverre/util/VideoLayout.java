package ar.com.delellis.eneverre.util;

import android.util.Rational;

/**
 * Aspect-ratio math for the video frame, resilient to cameras that report no
 * dimensions. When the backend omits {@code width}/{@code height} they arrive
 * as 0, which would divide-by-zero the portrait height and produce an invalid
 * {@link Rational} for Picture-in-Picture; both fall back to 16:9 here.
 */
public final class VideoLayout {
    private VideoLayout() {}

    private static final int FALLBACK_W = 16;
    private static final int FALLBACK_H = 9;

    // Android rejects PiP aspect ratios outside roughly [1:2.39, 2.39:1] and
    // throws IllegalArgumentException on entry; clamp to stay inside the range.
    private static final float PIP_MIN = 1f / 2.39f;
    private static final float PIP_MAX = 2.39f;

    /**
     * Height for a full-width portrait video frame, given the screen width and
     * the camera's native dimensions. Falls back to 16:9 when the camera reports
     * no usable dimensions.
     */
    public static int portraitHeight(int screenWidth, int videoWidth, int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0) {
            return screenWidth * FALLBACK_H / FALLBACK_W;
        }
        return screenWidth * videoHeight / videoWidth;
    }

    /**
     * A PiP aspect ratio for the given camera dimensions, clamped to the range
     * Android accepts and falling back to 16:9 for unknown or extreme dimensions.
     */
    public static Rational pipAspectRatio(int videoWidth, int videoHeight) {
        float ratio = (videoWidth <= 0 || videoHeight <= 0)
                ? (float) FALLBACK_W / FALLBACK_H
                : (float) videoWidth / videoHeight;
        ratio = Math.max(PIP_MIN, Math.min(PIP_MAX, ratio));
        return new Rational(Math.round(ratio * 1000), 1000);
    }
}
