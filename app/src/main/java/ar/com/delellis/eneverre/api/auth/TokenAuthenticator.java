package ar.com.delellis.eneverre.api.auth;

import androidx.annotation.Nullable;

import okhttp3.Authenticator;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

/**
 * Renews the access token on a {@code 401} by delegating to
 * {@link SessionManager#reauthenticate(String)}, then retries the failed
 * request with the fresh token. Returning {@code null} gives up and lets the
 * {@code 401} propagate (refresh token expired or revoked), so the call site
 * routes the user back to the login screen.
 */
public class TokenAuthenticator implements Authenticator {
    private final SessionManager session;
    private final String apiHost;

    public TokenAuthenticator(SessionManager session, String apiHost) {
        this.session = session;
        this.apiHost = apiHost;
    }

    @Nullable
    @Override
    public Request authenticate(@Nullable Route route, Response response) {
        // Only ever renew/retry against the API host: a 401 from a third-party
        // update-download host must not cause a token to be attached to the retry.
        if (apiHost == null || !apiHost.equalsIgnoreCase(response.request().url().host())) {
            return null;
        }
        // Never try to renew the login/refresh calls themselves, and give up
        // after a single retry to avoid an infinite 401 loop.
        if (AuthPaths.isAuthEndpoint(response.request()) || responseCount(response) >= 2) {
            return null;
        }

        String fresh = session.reauthenticate(bearerOf(response.request()));
        if (fresh == null) {
            return null;
        }
        return response.request().newBuilder()
                .header("Authorization", "Bearer " + fresh)
                .build();
    }

    @Nullable
    private String bearerOf(Request request) {
        String header = request.header("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring("Bearer ".length());
        }
        return null;
    }

    private int responseCount(Response response) {
        int count = 1;
        while ((response = response.priorResponse()) != null) {
            count++;
        }
        return count;
    }
}
