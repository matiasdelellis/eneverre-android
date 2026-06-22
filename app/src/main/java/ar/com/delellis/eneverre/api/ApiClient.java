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

    private String getApiBase() {
        if (this.port > 0) {
            return this.protocol + this.baseUrl + ":" + this.port + "/api/";
        }
        return this.protocol + this.baseUrl + "/api/";
    }

    /**
     * Builds a playback URL with the credentials embedded inline
     * ({@code user:pass@host}) because VLC fetches the stream directly.
     *
     * <p><b>Security:</b> the returned string contains plaintext credentials.
     * Never log it, put it in a crash report, or expose it to other apps. It is
     * handed straight to {@code VlcPlayer.playUri()} (libVLC runs with
     * {@code --quiet}, so it is not written to the VLC log either).
     */
    public String getPlaybackUrl(String device_id, String start, double duration) {
        if (this.port > 0) {
            return this.protocol + getCredentials() + "@" + this.baseUrl + ":" + this.port + "/api/camera/" + device_id + "/playback/get?start=" + start + "&duration=" + duration;
        }
        return this.protocol + getCredentials() + "@" + this.baseUrl + "/api/camera/" + device_id + "/playback/get?start=" + start + "&duration=" + duration;
    }

    private String getCredentials() {
        return this.username + ":" + this.password;
    }
}
