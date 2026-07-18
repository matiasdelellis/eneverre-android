package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Body of {@code GET /api/users/me/sessions}: the caller's sessions by status. */
public class SessionsResponse {
    @Expose
    @SerializedName("active")
    private List<Session> active;

    @Expose
    @SerializedName("expired")
    private List<Session> expired;

    public List<Session> getActive() {
        return active;
    }

    public List<Session> getExpired() {
        return expired;
    }
}
