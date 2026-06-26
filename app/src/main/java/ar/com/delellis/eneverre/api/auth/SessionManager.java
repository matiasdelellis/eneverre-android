package ar.com.delellis.eneverre.api.auth;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.IOException;

import ar.com.delellis.eneverre.api.ApiService;
import ar.com.delellis.eneverre.api.model.RefreshRequest;
import ar.com.delellis.eneverre.api.model.RefreshResponse;
import ar.com.delellis.eneverre.util.SecureStore;

/**
 * Owns the user's session tokens and the logic to renew them. Sits behind
 * {@code ApiClient}: the {@link BearerInterceptor} reads the current token to
 * stamp each request, and the {@link TokenAuthenticator} delegates 401 recovery
 * here.
 *
 * <p>Both tokens are rotated by the server on each refresh and so are mutated
 * from OkHttp's authenticator thread while read from request interceptors on
 * other threads; hence {@code volatile}.
 */
public class SessionManager {
    private static final String TAG = "SessionManager";

    /**
     * On app open, proactively refresh the access token if it expires within this
     * window, instead of waiting for a request to fail with a 401 mid-session.
     */
    private static final long REFRESH_THRESHOLD_SECONDS = 60 * 60; // 1h

    private volatile String token;
    private volatile String refreshToken;
    /** Unix expiry (seconds) of {@link #token}; 0 if unknown. */
    private volatile long accessExpiresAt;

    /**
     * Used to call {@code auth/refresh}. Set once via {@link #attach(ApiService)}
     * after Retrofit is built (it can't be a constructor arg because the service
     * is created from an {@code OkHttpClient} that already references this).
     */
    private ApiService apiService;

    public SessionManager(String accessToken, String refreshToken, long accessExpiresAt) {
        this.token = accessToken;
        this.refreshToken = refreshToken;
        this.accessExpiresAt = accessExpiresAt;
    }

    /** Wires up the service used for refreshes; call once after Retrofit is built. */
    public void attach(ApiService apiService) {
        this.apiService = apiService;
    }

    /** The current access token, or {@code null} if none is held yet. */
    @Nullable
    public String getToken() {
        return token;
    }

    /**
     * The {@code Authorization} header value ({@code "Bearer ..."}), or
     * {@code null} if no token is held yet. Exposed so direct stream fetchers
     * that bypass Retrofit (e.g. ExoPlayer's {@code DataSource}) can send the
     * same credentials by header instead of embedding them inline in the URL.
     */
    @Nullable
    public String getAuthorizationHeader() {
        String current = token;
        return current != null ? "Bearer " + current : null;
    }

    /**
     * Caches and persists a freshly issued/rotated token pair (and its access
     * expiry). Used after an interactive login and by every refresh, so the
     * in-memory tokens and {@code SecureStore} never drift apart.
     */
    public void setTokens(String accessToken, String refreshToken, long accessExpiresAt) {
        this.token = accessToken;
        this.refreshToken = refreshToken;
        this.accessExpiresAt = accessExpiresAt;
        SecureStore.getInstance().setTokens(accessToken, refreshToken, accessExpiresAt);
    }

    /**
     * Callback for {@link #refreshSessionIfExpiringSoon}: invoked on the main
     * thread once the session is ready to use (refreshed or not).
     */
    public interface SessionCallback {
        void onReady();
    }

    /**
     * If the access token is missing or within {@link #REFRESH_THRESHOLD_SECONDS}
     * of expiring, renews it up front (so the next requests don't trip a 401
     * mid-session); otherwise proceeds immediately. A failed proactive refresh is
     * non-fatal — the access token may still be valid, and if not the
     * authenticator's reactive 401 path takes over. Always calls back exactly once.
     */
    public void refreshSessionIfExpiringSoon(SessionCallback callback) {
        String currentRefresh = refreshToken;
        long now = System.currentTimeMillis() / 1000L;
        boolean expiringSoon = token == null || accessExpiresAt <= now + REFRESH_THRESHOLD_SECONDS;
        if (currentRefresh == null || !expiringSoon) {
            callback.onReady();
            return;
        }
        apiService.refresh(new RefreshRequest(currentRefresh)).enqueue(new retrofit2.Callback<RefreshResponse>() {
            @Override
            public void onResponse(retrofit2.Call<RefreshResponse> call, retrofit2.Response<RefreshResponse> resp) {
                if (resp.isSuccessful() && resp.body() != null) {
                    RefreshResponse body = resp.body();
                    setTokens(body.getToken(), body.getRefreshToken(), body.getExpiresAt());
                } else {
                    Log.w(TAG, "Proactive token refresh failed: HTTP " + resp.code());
                }
                callback.onReady();
            }

            @Override
            public void onFailure(retrofit2.Call<RefreshResponse> call, Throwable t) {
                Log.w(TAG, "Proactive token refresh failed", t);
                callback.onReady();
            }
        });
    }

    /**
     * Reactive 401 recovery, called by {@link TokenAuthenticator}. Serialized so
     * concurrent 401s collapse to a single refresh: if another thread already
     * rotated the token since {@code attemptedToken} was sent, returns the new
     * one without refreshing again; otherwise performs a blocking refresh.
     * Returns {@code null} to give up (refresh token expired/revoked), letting
     * the 401 propagate so the call site routes back to login.
     */
    @Nullable
    public synchronized String reauthenticate(@Nullable String attemptedToken) {
        String current = token;
        // Another thread already refreshed while this request was in flight.
        if (current != null && !current.equals(attemptedToken)) {
            return current;
        }
        return refreshBlocking();
    }

    /**
     * Performs a blocking refresh; on success updates and persists the rotated
     * token pair and returns the new access token, otherwise returns {@code null}.
     */
    @Nullable
    private String refreshBlocking() {
        String currentRefresh = refreshToken;
        if (currentRefresh == null) {
            return null;
        }
        try {
            retrofit2.Response<RefreshResponse> resp = apiService
                    .refresh(new RefreshRequest(currentRefresh))
                    .execute();
            if (resp.isSuccessful() && resp.body() != null) {
                RefreshResponse body = resp.body();
                // Persist the rotated pair so the next cold start can refresh too.
                setTokens(body.getToken(), body.getRefreshToken(), body.getExpiresAt());
                return body.getToken();
            }
            Log.w(TAG, "Token refresh failed: HTTP " + resp.code());
        } catch (IOException e) {
            Log.w(TAG, "Token refresh failed", e);
        }
        return null;
    }
}
