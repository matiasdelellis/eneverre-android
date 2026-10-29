package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-user camera ACL, as returned by and sent to
 * {@code GET/PUT /api/users/{username}/cameras}.
 *
 * <p>{@code restricted=false} means "every camera" and {@code camera_ids} is
 * ignored; {@code restricted=true} with an empty list means "no cameras". The
 * server validates every id and answers {@code 422} for an unknown one.
 */
public class UserCameras {
    @Expose
    @SerializedName("username")
    private String username;

    @Expose
    @SerializedName("restricted")
    private boolean restricted;

    @Expose
    @SerializedName("camera_ids")
    private List<String> cameraIds;

    /** Request body: a restriction (or its removal) for one account. */
    public UserCameras(boolean restricted, List<String> cameraIds) {
        this.restricted = restricted;
        this.cameraIds = cameraIds != null ? cameraIds : new ArrayList<>();
    }

    public String getUsername() {
        return username;
    }

    public boolean isRestricted() {
        return restricted;
    }

    public List<String> getCameraIds() {
        return cameraIds != null ? cameraIds : new ArrayList<>();
    }
}
