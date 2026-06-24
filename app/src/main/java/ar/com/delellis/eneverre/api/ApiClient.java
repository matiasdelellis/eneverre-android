package ar.com.delellis.eneverre.api;

import android.util.Base64;

import java.net.MalformedURLException;
import java.net.URL;

import ar.com.delellis.eneverre.BuildConfig;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ApiClient {
    private final String protocol;
    private final String baseUrl;
    private final int port;
    private final String username;
    private final String password;
    private final ApiService apiService;

    private static ApiClient instance = null;

    private ApiClient(String url, String username, String password) {
        URL host;
        try {
            host = new URL(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid API host URL: " + url, e);
        }

        this.protocol = host.getProtocol() + "://";
        this.baseUrl = host.getHost();
        this.port = host.getPort();
        this.username = username;
        this.password = password;

        // Attach the Basic auth header to every request so call sites don't have to.
        OkHttpClient.Builder httpClient = new OkHttpClient.Builder();
        httpClient.addInterceptor(chain -> chain.proceed(
                chain.request().newBuilder()
                        .header("Authorization", getAuthorization())
                        .build()));

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
    }

    /**
     * (Re)initializes the client with the given credentials. Throws
     * {@link IllegalArgumentException} if the URL is malformed.
     */
    public static synchronized ApiClient getInstance(String url, String username, String password) {
        instance = new ApiClient(url, username, password);
        return instance;
    }

    /** @throws IllegalStateException if {@link #getInstance(String, String, String)} was never called. */
    public static synchronized ApiClient getInstance() {
        if (instance == null) {
            throw new IllegalStateException("ApiClient is not initialized; call getInstance(url, username, password) first.");
        }
        return instance;
    }

    public static ApiService getApiService() {
        return getInstance().apiService;
    }

    private String getAuthorization() {
        String credentials = getCredentials();
        return "Basic " + Base64.encodeToString(credentials.getBytes(), Base64.URL_SAFE|Base64.NO_WRAP);
    }

    /**
     * The {@code Authorization} header value ({@code "Basic ..."}) attached to
     * every API request. Exposed so direct stream fetchers that bypass Retrofit
     * (e.g. ExoPlayer's {@code DataSource}) can send the same credentials by
     * header instead of embedding them inline in the URL.
     */
    public String getAuthorizationHeader() {
        return getAuthorization();
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

    private String getCredentials() {
        return this.username + ":" + this.password;
    }
}
