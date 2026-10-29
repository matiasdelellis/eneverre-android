package ar.com.delellis.eneverre.api;

import androidx.annotation.Nullable;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import ar.com.delellis.eneverre.BuildConfig;
import ar.com.delellis.eneverre.api.auth.BearerInterceptor;
import ar.com.delellis.eneverre.api.auth.SessionManager;
import ar.com.delellis.eneverre.api.auth.TokenAuthenticator;
import okhttp3.HttpUrl;
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
    /** The app's HTTP client (auth interceptor + authenticator), shared with Media3. */
    private final OkHttpClient httpClient;

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

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                // The backend is usually a LAN appliance, so "server down" is a
                // common, local condition: fail fast instead of waiting the 10 s
                // default before the UI can report it. Read/write keep their
                // defaults (a slow link is not the same as an absent server).
                .connectTimeout(5, TimeUnit.SECONDS);
        // Stamp the Bearer token on every request, and transparently renew it on
        // a 401 and retry. No username/password is involved or stored.
        builder.addInterceptor(new BearerInterceptor(session, this.baseUrl));
        builder.authenticator(new TokenAuthenticator(session, this.baseUrl));

        if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            // BASIC: request line + response status only — avoids logging credentials.
            logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
            builder.addInterceptor(logging);
        }

        this.httpClient = builder.build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(getApiBase())
                .client(this.httpClient)
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

    /**
     * The app's HTTP client: the Bearer interceptor and the 401 authenticator
     * are registered on it. Shared with Media3's playback data source so stream
     * requests use the same credentials, the same refresh path and OkHttp's
     * redirect handling (which drops {@code Authorization} when a redirect
     * changes host, port or scheme).
     */
    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    /** @see SessionManager#setTokens(String, String, long) */
    public void setTokens(String accessToken, String refreshToken, long accessExpiresAt) {
        session.setTokens(accessToken, refreshToken, accessExpiresAt);
    }

    /** @see SessionManager#refreshSessionIfExpiringSoon(SessionManager.SessionCallback) */
    public void refreshSessionIfExpiringSoon(SessionManager.SessionCallback callback) {
        session.refreshSessionIfExpiringSoon(callback);
    }

    /** @see SessionManager#refreshRole(SessionManager.SessionCallback) */
    public void refreshRole(SessionManager.SessionCallback callback) {
        session.refreshRole(callback);
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
        // Build via HttpUrl so query values are percent-encoded: the RFC3339
        // `start` carries a timezone offset like "+02:00", and a raw "+" in the
        // query decodes server-side as a space, breaking playback in UTC+ zones.
        return HttpUrl.get(getApiBase()).newBuilder()
                .addPathSegment("camera")
                .addPathSegment(device_id)
                .addPathSegment("recordings")
                .addPathSegment("get")
                .addQueryParameter("start", start)
                .addQueryParameter("duration", String.valueOf(duration))
                .build()
                .toString();
    }

    /**
     * Builds the HLS VOD playlist URL for a camera over {@code [start, end]}
     * (RFC3339). The server collapses coverage gaps into a continuous
     * presentation and emits {@code EXT-X-PROGRAM-DATE-TIME} per segment; the
     * playlist's init and segment URIs are relative and resolve under the same
     * {@code /recordings/hls/} prefix, so the Bearer header is stamped on every
     * one of them by {@code BearerInterceptor}.
     *
     * <p>Both bounds are required by the server, and the span is capped at seven
     * days (the client asks for six hours).
     */
    public String getHlsPlaylistUrl(String device_id, String start, String end) {
        return HttpUrl.get(getApiBase()).newBuilder()
                .addPathSegment("camera")
                .addPathSegment(device_id)
                .addPathSegment("recordings")
                .addPathSegment("hls")
                .addPathSegment("playlist.m3u8")
                .addQueryParameter("start", start)
                .addQueryParameter("end", end)
                .build()
                .toString();
    }

    /**
     * Builds the WebSocket URL for the two-way-audio (push-to-talk) endpoint of the
     * given camera: {@code ws(s)://<host>[:port]/api/camera/<id>/talk}. Carries no
     * credentials — the caller authenticates with the same Bearer header exposed by
     * {@link #getAuthorizationHeader()} (Android sets it on the upgrade request).
     */
    public String getTalkUrl(String device_id) {
        // http→ws, https→wss; keep the port when the host declares one.
        String wsProtocol = this.protocol.startsWith("https") ? "wss://" : "ws://";
        String host = this.port > 0 ? this.baseUrl + ":" + this.port : this.baseUrl;
        return wsProtocol + host + "/api/camera/" + device_id + "/talk";
    }
}
