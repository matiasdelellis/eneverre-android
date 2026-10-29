package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * Live-settings snapshot of one camera, served by {@code GET /api/camera/{id}/settings}
 * (admin only). Read-only camera state — day/night mode and brightness, the
 * illuminators, motion detection and the audio in/out toggles — plus the unix
 * fetch time, so a stale entry is visibly stale.
 */
@SuppressWarnings("unused")
public class CameraSettings implements Serializable {
    @Expose
    @SerializedName("daynight_mode")
    private String daynightMode;

    @Expose
    @SerializedName("daynight_enabled")
    private boolean daynightEnabled;

    @Expose
    @SerializedName("daynight_brightness")
    private int daynightBrightness;

    @Expose
    @SerializedName("motion_enabled")
    private boolean motionEnabled;

    @Expose
    @SerializedName("mic_enabled")
    private boolean micEnabled;

    @Expose
    @SerializedName("spk_enabled")
    private boolean spkEnabled;

    @Expose
    @SerializedName("rec_ch0")
    private boolean recCh0;

    @Expose
    @SerializedName("rec_ch1")
    private boolean recCh1;

    @Expose
    @SerializedName("ircut")
    private boolean ircut;

    @Expose
    @SerializedName("ir850")
    private boolean ir850;

    @Expose
    @SerializedName("ir940")
    private boolean ir940;

    @Expose
    @SerializedName("white")
    private boolean white;

    /** Unix seconds when the server fetched this snapshot from the camera. */
    @Expose
    @SerializedName("updated_at")
    private long updatedAt;

    /** Current day/night mode reported by the camera ("day", "night", "manual", ...). */
    public String getDaynightMode() {
        return daynightMode;
    }

    /** Whether the camera's photosensor is enabled (auto day/night). */
    public boolean isDaynightEnabled() {
        return daynightEnabled;
    }

    public int getDaynightBrightness() {
        return daynightBrightness;
    }

    public boolean isMotionEnabled() {
        return motionEnabled;
    }

    public boolean isMicEnabled() {
        return micEnabled;
    }

    public boolean isSpkEnabled() {
        return spkEnabled;
    }

    public boolean isRecCh0() {
        return recCh0;
    }

    public boolean isRecCh1() {
        return recCh1;
    }

    public boolean isIrcut() {
        return ircut;
    }

    public boolean isIr850() {
        return ir850;
    }

    public boolean isIr940() {
        return ir940;
    }

    public boolean isWhite() {
        return white;
    }

    /** Unix seconds when the server fetched this snapshot from the camera. */
    public long getUpdatedAt() {
        return updatedAt;
    }
}
