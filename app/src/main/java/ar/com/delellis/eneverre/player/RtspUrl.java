package ar.com.delellis.eneverre.player;

import android.net.Uri;

/**
 * Helpers for the RTSP URLs the API advertises.
 *
 * <p>The server replaced the global rotating RTSP username/password with a
 * per-camera signed token in the URL path, so a live URL now looks like
 * {@code rtsp://<host>:<port>/<camera-id>/<exp>.<sig>}. The last path segment
 * is a bearer credential valid for six hours: it must never be logged, and it
 * only authorizes a <em>new</em> connection until {@code exp} — after that the
 * client has to ask {@code GET /api/cameras} for a fresh URL (see
 * {@code CameraRefresher}). An already playing session survives the expiry.
 *
 * <p>Otherwise the URL is opaque. It is handed to the player exactly as the
 * API returned it: no {@code user:pass@} prefix, no RTSP {@code Authorization}
 * header, no appended query.
 */
public final class RtspUrl {

    /**
     * How long before a token's expiry the client refreshes it. Tokens live six
     * hours, so half an hour is a wide margin that still leaves most sessions
     * untouched.
     */
    public static final long REFRESH_MARGIN_MS = 30 * 60 * 1000L;

    /** 2100-01-01 in unix seconds: anything larger is not a plausible expiry. */
    private static final long MAX_PLAUSIBLE_SECONDS = 4_102_444_800L;

    private RtspUrl() {
    }

    /**
     * The expiry encoded in the URL's last path segment, in <b>milliseconds</b>
     * since the epoch, or {@code 0} when the URL carries none — a malformed
     * URL, or one from a server that predates the path-token scheme.
     *
     * <p>The server writes unix <em>seconds</em>; this converts to the scale
     * the rest of the app uses ({@link System#currentTimeMillis()}).
     */
    public static long tokenExpiresAt(String url) {
        if (url == null || url.isEmpty()) {
            return 0;
        }
        String last = Uri.parse(url).getLastPathSegment();
        if (last == null) {
            return 0;
        }
        int dot = last.lastIndexOf('.');
        if (dot <= 0 || dot == last.length() - 1) {
            return 0;
        }
        long seconds;
        try {
            seconds = Long.parseLong(last.substring(0, dot));
        } catch (NumberFormatException e) {
            return 0;
        }
        if (seconds <= 0 || seconds > MAX_PLAUSIBLE_SECONDS) {
            return 0;
        }
        return seconds * 1000L;
    }

    /**
     * Whether the token in {@code url} expires within
     * {@link #REFRESH_MARGIN_MS}.
     *
     * <p>A URL whose expiry cannot be read counts as fresh: guessing would make
     * every start refresh against a server that predates the path tokens, and
     * the server still answers {@code 401} if the credential really is dead
     * (which the one-shot error refresh handles).
     */
    public static boolean isExpiring(String url) {
        long expiresAt = tokenExpiresAt(url);
        if (expiresAt <= 0) {
            return false;
        }
        return System.currentTimeMillis() + REFRESH_MARGIN_MS >= expiresAt;
    }

    /**
     * The URL with its last path segment replaced by {@code <redacted>}, safe
     * to log. That segment is a bearer credential: a full RTSP URL in logcat
     * is a leaked camera.
     */
    public static String redact(String url) {
        if (url == null || url.isEmpty()) {
            return String.valueOf(url);
        }
        int slash = url.lastIndexOf('/');
        if (slash < 0 || slash == url.length() - 1) {
            return url;
        }
        return url.substring(0, slash + 1) + "<redacted>";
    }
}
