package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

@SuppressWarnings("unused")
public class Event implements Serializable {
    @Expose
    @SerializedName("id")
    private long id;

    @Expose
    @SerializedName("camera_id")
    private String cameraId;

    @Expose
    @SerializedName("start_ts")
    private String startTs;

    @Expose
    @SerializedName("end_ts")
    private String endTs;

    @Expose
    @SerializedName("type")
    private String type;

    @Expose
    @SerializedName("source")
    private String source;

    @Expose
    @SerializedName("created_at")
    private String createdAt;

    public long getId() {
        return id;
    }

    public String getCameraId() {
        return cameraId;
    }

    public String getStartTs() {
        return startTs;
    }

    public String getEndTs() {
        return endTs;
    }

    public String getType() {
        return type;
    }

    public String getSource() {
        return source;
    }

    public String getCreatedAt() {
        return createdAt;
    }
}
