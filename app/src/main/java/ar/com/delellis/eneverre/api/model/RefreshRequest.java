package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/** Body of {@code POST /api/auth/refresh}: the refresh token to exchange. */
public class RefreshRequest {
    @Expose
    @SerializedName("refresh_token")
    private final String refreshToken;

    public RefreshRequest(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
