package ar.com.delellis.eneverre.api;

import android.util.Base64;

import java.net.MalformedURLException;
import java.net.URL;

import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class ApiClient {
    private String protocol = null;
    private String baseUrl = null;
    private int port = -1;
    private String username = null;
    private String password = null;

    private static ApiService apiService = null;
    private static ApiClient apiClient = null;

    private ApiClient(String url, String username, String password) {
        URL host = null;
        try {
            host = new URL(url);
        } catch (MalformedURLException e) {
            // Nothing...
            return;
        }

        this.protocol = host.getProtocol() + "://";
        this.baseUrl = host.getHost();
        this.port = host.getPort();
        this.username = username;
        this.password = password;

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(getApiBase())
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        apiService = retrofit.create(ApiService.class);
    }

    public static ApiClient getInstance (String url, String username, String password) {
        if (apiClient != null) {
            // TODO: Thrown if initialized or maybe clean it.
        }
        apiClient = new ApiClient(url, username, password);
        return apiClient;
    }

    public static ApiClient getInstance() {
        // TODO: Thrown if not initialized..
        return apiClient;
    }

    public static ApiService getApiService() {
        return apiService;
    }

    public String getAuthorization() {
        String credentials = getCredentials();
        return "Basic " + Base64.encodeToString(credentials.getBytes(), Base64.URL_SAFE|Base64.NO_WRAP);
    }

    private String getApiBase() {
        if (this.port > 0) {
            return this.protocol + this.baseUrl + ":" + this.port + "/api/";
        }
        return this.protocol + this.baseUrl + "/api/";
    }

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
