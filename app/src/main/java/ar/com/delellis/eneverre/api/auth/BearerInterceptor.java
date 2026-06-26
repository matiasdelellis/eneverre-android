package ar.com.delellis.eneverre.api.auth;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Attaches {@code Authorization: Bearer <accessToken>} to every request so call
 * sites don't have to. The token-minting endpoints (login/refresh) carry none
 * themselves.
 */
public class BearerInterceptor implements Interceptor {
    private final SessionManager session;

    public BearerInterceptor(SessionManager session) {
        this.session = session;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String current = session.getToken();
        if (current != null && !AuthPaths.isAuthEndpoint(request)) {
            request = request.newBuilder()
                    .header("Authorization", "Bearer " + current)
                    .build();
        }
        return chain.proceed(request);
    }
}
