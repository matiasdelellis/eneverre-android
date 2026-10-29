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

    /**
     * The effective role and admin flag, sent so a client that caches them can
     * notice a server-side role change without a fresh login. Best-effort: the
     * server omits them if it could not resolve the account, so they may be
     * absent — in which case the client keeps what it had.
     */
    @Expose
    @SerializedName("role")
    private String role;

    @Expose
    @SerializedName("is_admin")
    private Boolean isAdmin;

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

    /** The effective role, or {@code null} when the server omitted it. */
    public String getRole() {
        return role;
    }

    /** The effective admin flag, or {@code null} when the server omitted it. */
    public Boolean isAdmin() {
        return isAdmin;
    }
}
