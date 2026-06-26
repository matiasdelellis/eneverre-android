package ar.com.delellis.eneverre.api.auth;

import okhttp3.Request;

/**
 * The token-minting endpoints (under {@code /api/}). They must never carry a
 * {@code Bearer} header nor be retried by the {@link TokenAuthenticator}, since
 * they are how tokens are obtained in the first place.
 */
public final class AuthPaths {
    public static final String LOGIN_PATH = "auth/login";
    public static final String REFRESH_PATH = "auth/refresh";

    private AuthPaths() {}

    public static boolean isAuthEndpoint(Request request) {
        String path = request.url().encodedPath();
        return path.endsWith("/" + LOGIN_PATH) || path.endsWith("/" + REFRESH_PATH);
    }
}
