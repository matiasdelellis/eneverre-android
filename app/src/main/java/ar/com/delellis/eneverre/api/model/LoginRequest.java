package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/** Body of {@code POST /api/auth/login}: the credentials exchanged for a Bearer token. */
public class LoginRequest {
    @Expose
    @SerializedName("username")
    private final String username;

    @Expose
    @SerializedName("password")
    private final String password;

    /**
     * Human label stored on the token so the server's session manager can
     * distinguish logins (e.g. "Samsung Galaxy S21"). Optional on the backend
     * (empty -> NULL), but the app always sends the device model.
     */
    @Expose
    @SerializedName("device_name")
    private final String deviceName;

    public LoginRequest(String username, String password, String deviceName) {
        this.username = username;
        this.password = password;
        this.deviceName = deviceName;
    }
}
