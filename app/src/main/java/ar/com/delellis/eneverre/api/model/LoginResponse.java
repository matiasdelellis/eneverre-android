package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * Response of {@code POST /api/auth/login}. The client keeps the short-lived
 * {@code token} (sent as {@code Authorization: Bearer <token>}) and the
 * long-lived {@code refresh_token} (exchanged for a fresh pair at
 * {@code /api/auth/refresh} when the access token lapses). The {@code *_at}
 * fields are the unix expiries the server assigned.
 */
public class LoginResponse {
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

    /**
     * When true the client must send the user through the change-password flow
     * ({@code PUT /api/users/me/password}) before using the app. Set for the
     * seeded initial admin and for any account an admin flagged.
     */
    @Expose
    @SerializedName("must_change_password")
    private boolean mustChangePassword;

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

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }
}
