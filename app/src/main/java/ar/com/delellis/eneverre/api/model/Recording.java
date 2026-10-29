package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

public class Recording implements Serializable {
    @Expose
    @SerializedName("start")
    private String start;

    @Expose
    @SerializedName("duration")
    private float duration;

    /**
     * Where the video lies inside the segment, in seconds from {@link #start}.
     * Both are absent when the server has not measured the segment yet (a row
     * from an older version), which means "unknown", not "no video". A
     * duration of 0 means the camera recorded audio only.
     */
    @Expose
    @SerializedName("video_start")
    private Double videoStart;

    @Expose
    @SerializedName("video_duration")
    private Double videoDuration;

    public String getStart() {
        return start;
    }

    public float getDuration() {
        return duration;
    }

    /** Offset of the first video frame, in seconds; null when unknown. */
    public Double getVideoStart() {
        return videoStart;
    }

    /** Seconds of the segment covered by video; null when unknown. */
    public Double getVideoDuration() {
        return videoDuration;
    }
}
