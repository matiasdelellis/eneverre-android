package ar.com.delellis.eneverre.api.auth;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Attaches {@code Authorization: Bearer <accessToken>} to requests bound for the
 * API host so call sites don't have to. The token-minting endpoints
 * (login/refresh) carry none themselves.
 *
 * <p>The token is <b>only</b> attached when the request targets {@link #apiHost}.
 * Update downloads use an absolute {@code @Url} that the server can point at a
 * different domain/CDN (see {@code ApiService#downloadUpdate}); without this host
 * check the user's access token would leak to that third party.
 */
public class BearerInterceptor implements Interceptor {
    private final SessionManager session;
    private final String apiHost;

    public BearerInterceptor(SessionManager session, String apiHost) {
        this.session = session;
        this.apiHost = apiHost;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String current = session.getToken();
        boolean sameHost = apiHost != null && apiHost.equalsIgnoreCase(request.url().host());
        if (current != null && sameHost && !AuthPaths.isAuthEndpoint(request)) {
            request = request.newBuilder()
                    .header("Authorization", "Bearer " + current)
                    .build();
        }
        return chain.proceed(request);
    }
}
