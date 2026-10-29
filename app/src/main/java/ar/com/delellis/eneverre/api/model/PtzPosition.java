package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * Cached PTZ position served by {@code GET /api/camera/{id}/ptz/position}:
 * the last known pan/tilt in degrees. 503 while the cache is cold (no move
 * recorded yet and the startup probe did not reach the camera).
 */
@SuppressWarnings("unused")
public class PtzPosition implements Serializable {
    @Expose
    @SerializedName("pan")
    private Double pan;

    @Expose
    @SerializedName("tilt")
    private Double tilt;

    public Double getPan() {
        return pan;
    }

    public Double getTilt() {
        return tilt;
    }
}
