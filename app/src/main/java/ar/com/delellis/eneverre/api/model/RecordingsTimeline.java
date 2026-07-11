package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * Recorded extent for a camera, from
 * {@code GET /api/camera/{id}/recordings/timeline} (embedded media engine only,
 * 404 otherwise). {@code start}/{@code end} are RFC3339 UTC and may be null when
 * the camera has no recordings.
 */
public class RecordingsTimeline {
    @Expose
    @SerializedName("start")
    private String start;

    @Expose
    @SerializedName("end")
    private String end;

    @Expose
    @SerializedName("count")
    private int count;

    /** First recorded moment (RFC3339 UTC), or null when there are no recordings. */
    public String getStart() {
        return start;
    }

    /** Last recorded moment (RFC3339 UTC), or null when there are no recordings. */
    public String getEnd() {
        return end;
    }

    public int getCount() {
        return count;
    }
}
