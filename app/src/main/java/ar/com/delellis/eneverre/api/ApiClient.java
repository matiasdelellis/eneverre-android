package ar.com.delellis.eneverre.api;

import androidx.annotation.Nullable;

import java.net.MalformedURLException;
import java.net.URL;

import ar.com.delellis.eneverre.BuildConfig;
import ar.com.delellis.eneverre.api.auth.BearerInterceptor;
import ar.com.delellis.eneverre.api.auth.SessionManager;
import ar.com.delellis.eneverre.api.auth.TokenAuthenticator;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Singleton entry point to the REST API: owns the Retrofit instance and the
 * base URL ({@code <host>/api/}). All token/session handling lives in
 * {@link SessionManager} (package {@code api.auth}); this class wires it into
 * the OkHttp pipeline and exposes thin delegations so call sites keep going
 * through {@code ApiClient}.
 */
public class ApiClient {
    private final String protocol;
    private final String baseUrl;
    private final int port;
    private final ApiService apiService;
    private final SessionManager session;

    private static ApiClient instance = null;

    private ApiClient(String url, String accessToken, String refreshToken, long accessExpiresAt) {
        URL host;
        try {
            host = new URL(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid API host URL: " + url, e);
        }

        this.protocol = host.getProtocol() + "://";
        this.baseUrl = host.getHost();
        this.port = host.getPort();
        this.session = new SessionManager(accessToken, refreshToken, accessExpiresAt);

        OkHttpClient.Builder httpClient = new OkHttpClient.Builder();
        // Stamp the Bearer token on every request, and transparently renew it on
        // a 401 and retry. No username/password is involved or stored.
        httpClient.addInterceptor(new BearerInterceptor(session));
        httpClient.authenticator(new TokenAuthenticator(session));

        if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            // BASIC: request line + response status only — avoids logging credentials.
            logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
            httpClient.addInterceptor(logging);
        }

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(getApiBase())
                .client(httpClient.build())
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        this.apiService = retrofit.create(ApiService.class);
        // The session refreshes via the service, which only exists now.
        this.session.attach(apiService);
    }

    /**
     * (Re)initializes the client for the given host with a stored token pair and
     * the access token's unix expiry (any may be {@code null}/0 — a fresh login
     * has no tokens yet and obtains them via {@link ApiService#login}). Throws
     * {@link IllegalArgumentException} if the URL is malformed.
     */
    public static synchronized ApiClient getInstance(String url, String accessToken, String refreshToken, long accessExpiresAt) {
        instance = new ApiClient(url, accessToken, refreshToken, accessExpiresAt);
        return instance;
    }

    /** @throws IllegalStateException if {@link #getInstance(String, String, String, long)} was never called. */
    public static synchronized ApiClient getInstance() {
        if (instance == null) {
            throw new IllegalStateException("ApiClient is not initialized; call getInstance(url, accessToken, refreshToken, accessExpiresAt) first.");
        }
        return instance;
    }

    public static ApiService getApiService() {
        return getInstance().apiService;
    }

    /** @see SessionManager#setTokens(String, String, long) */
    public void setTokens(String accessToken, String refreshToken, long accessExpiresAt) {
        session.setTokens(accessToken, refreshToken, accessExpiresAt);
    }

    /** @see SessionManager#refreshSessionIfExpiringSoon(SessionManager.SessionCallback) */
    public void refreshSessionIfExpiringSoon(SessionManager.SessionCallback callback) {
        session.refreshSessionIfExpiringSoon(callback);
    }

    /** @see SessionManager#getAuthorizationHeader() */
    @Nullable
    public String getAuthorizationHeader() {
        return session.getAuthorizationHeader();
    }

    private String getApiBase() {
        if (this.port > 0) {
            return this.protocol + this.baseUrl + ":" + this.port + "/api/";
        }
        return this.protocol + this.baseUrl + "/api/";
    }

    /**
     * Builds a playback URL for the given camera and time window. Carries no
     * credentials: clients authenticate with the {@code Authorization} header
     * from {@link #getAuthorizationHeader()} (e.g. ExoPlayer's data source), so
     * the URL is safe to build and log.
     */
    public String getPlaybackStreamUrl(String device_id, String start, double duration) {
        return getApiBase() + "camera/" + device_id + "/playback/get?start=" + start + "&duration=" + duration;
    }
}
