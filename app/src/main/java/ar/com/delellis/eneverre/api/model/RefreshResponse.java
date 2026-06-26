package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * Response of {@code POST /api/auth/refresh}: a freshly rotated token pair. Both
 * secrets change on every refresh, so the client must persist the new
 * {@code refresh_token} to refresh again next time.
 */
public class RefreshResponse {
    @Expose
    @SerializedName("token")
    private String token;

    @Expose
    @SerializedName("expires_at")
    private long expiresAt;

    @Expose
    @SerializedName("refresh_token")
    private String refreshToken;

    @Expose
    @SerializedName("refresh_expires_at")
    private long refreshExpiresAt;

    public String getToken() {
        return token;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public long getRefreshExpiresAt() {
        return refreshExpiresAt;
    }
}
