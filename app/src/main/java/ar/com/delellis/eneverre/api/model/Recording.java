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

    public String getStart() {
        return start;
    }

    public float getDuration() {
        return duration;
    }
}
