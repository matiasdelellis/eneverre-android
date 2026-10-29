package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * Full stored configuration of one camera, served by {@code GET /api/camera/{id}/config}
 * (admin only — it includes the source/backchannel/thingino credentials) and
 * sent back verbatim (possibly patched) to {@code PUT /api/camera/{id}}.
 * The PUT expects the complete body; {@code name} and {@code source} are
 * required, every other key falls back to a default when omitted.
 */
@SuppressWarnings("unused")
public class CameraConfig implements Serializable {
    @Expose
    @SerializedName("id")
    private String id;

    @Expose
    @SerializedName("name")
    private String name;

    @Expose
    @SerializedName("comment")
    private String comment;

    @Expose
    @SerializedName("location")
    private String location;

    @Expose
    @SerializedName("source")
    private String source;

    @Expose
    @SerializedName("backchannel")
    private String backchannel;

    @Expose
    @SerializedName("snapshot_url")
    private String snapshotUrl;

    @Expose
    @SerializedName("transport")
    private String transport;

    @Expose
    @SerializedName("record")
    private boolean record;

    @Expose
    @SerializedName("mse")
    private boolean mse;

    @Expose
    @SerializedName("relay")
    private boolean relay;

    @Expose
    @SerializedName("privacy")
    private boolean privacy;

    @Expose
    @SerializedName("playback")
    private boolean playback;

    @Expose
    @SerializedName("width")
    private int width;

    @Expose
    @SerializedName("height")
    private int height;

    @Expose
    @SerializedName("schedule_id")
    private String scheduleId;

    @Expose
    @SerializedName("thingino_url")
    private String thinginoUrl;

    @Expose
    @SerializedName("thingino_api_key")
    private String thinginoApiKey;

    @Expose
    @SerializedName("ptz")
    private boolean ptz;

    @Expose
    @SerializedName("home_x")
    private Double homeX;

    @Expose
    @SerializedName("home_y")
    private Double homeY;

    @Expose
    @SerializedName("privacy_x")
    private Double privacyX;

    @Expose
    @SerializedName("privacy_y")
    private Double privacyY;

    @Expose
    @SerializedName("pan_steps")
    private int panSteps;

    @Expose
    @SerializedName("pan_degrees")
    private int panDegrees;

    @Expose
    @SerializedName("tilt_steps")
    private int tiltSteps;

    @Expose
    @SerializedName("tilt_degrees")
    private int tiltDegrees;

    @Expose
    @SerializedName("fov_h")
    private Double fovH;

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getComment() {
        return comment;
    }

    public String getLocation() {
        return location;
    }

    public String getSource() {
        return source;
    }

    public String getBackchannel() {
        return backchannel;
    }

    public String getSnapshotUrl() {
        return snapshotUrl;
    }

    public String getTransport() {
        return transport;
    }

    public boolean isRecord() {
        return record;
    }

    public boolean isMse() {
        return mse;
    }

    public boolean isRelay() {
        return relay;
    }

    public boolean isPrivacy() {
        return privacy;
    }

    public boolean isPlayback() {
        return playback;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getScheduleId() {
        return scheduleId;
    }

    public String getThinginoUrl() {
        return thinginoUrl;
    }

    public String getThinginoApiKey() {
        return thinginoApiKey;
    }

    public boolean isPtz() {
        return ptz;
    }

    public Double getHomeX() {
        return homeX;
    }

    public Double getHomeY() {
        return homeY;
    }

    public Double getPrivacyX() {
        return privacyX;
    }

    public Double getPrivacyY() {
        return privacyY;
    }

    public int getPanSteps() {
        return panSteps;
    }

    public int getPanDegrees() {
        return panDegrees;
    }

    public int getTiltSteps() {
        return tiltSteps;
    }

    public int getTiltDegrees() {
        return tiltDegrees;
    }

    public Double getFovH() {
        return fovH;
    }

    public void setHomeX(Double homeX) {
        this.homeX = homeX;
    }

    public void setHomeY(Double homeY) {
        this.homeY = homeY;
    }

    public void setPrivacyX(Double privacyX) {
        this.privacyX = privacyX;
    }

    public void setPrivacyY(Double privacyY) {
        this.privacyY = privacyY;
    }
}
