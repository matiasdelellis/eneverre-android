package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/** One login session of the current user (see {@code GET /api/users/me/sessions}). */
public class Session implements Serializable {
    @Expose
    @SerializedName("id")
    private long id;

    @Expose
    @SerializedName("fingerprint")
    private String fingerprint;

    @Expose
    @SerializedName("created_at")
    private long createdAt;

    @Expose
    @SerializedName("expires_at")
    private long expiresAt;

    @Expose
    @SerializedName("renewable")
    private boolean renewable;

    @Expose
    @SerializedName("is_current")
    private boolean isCurrent;

    @Expose
    @SerializedName("device_name")
    private String deviceName;

    public long getId() {
        return id;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    /** Unix seconds when the session was created. */
    public long getCreatedAt() {
        return createdAt;
    }

    /** Unix seconds when the session (or its refresh token) expires. */
    public long getExpiresAt() {
        return expiresAt;
    }

    public boolean isRenewable() {
        return renewable;
    }

    /** Whether this is the session the app is currently using. */
    public boolean isCurrent() {
        return isCurrent;
    }

    public String getDeviceName() {
        return deviceName;
    }
}
