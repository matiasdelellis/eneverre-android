package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * Partial update body for {@code PUT /api/camera/{id}/settings}. Every field is
 * nullable and only the fields actually set are serialized (Gson omits nulls),
 * so the server applies exactly what the caller intends to change — each field
 * present in the body is applied in order, and the first failure is reported
 * with the failing field.
 */
@SuppressWarnings("unused")
public class SetCameraSettingsRequest {
    @Expose
    @SerializedName("motion_enabled")
    private Boolean motionEnabled;

    @Expose
    @SerializedName("mic_enabled")
    private Boolean micEnabled;

    @Expose
    @SerializedName("speaker_enabled")
    private Boolean speakerEnabled;

    @Expose
    @SerializedName("daynight_auto")
    private Boolean daynightAuto;

    /** "day" | "night" | "manual". */
    @Expose
    @SerializedName("daynight_mode")
    private String daynightMode;

    @Expose
    @SerializedName("ircut")
    private Boolean ircut;

    @Expose
    @SerializedName("ir850")
    private Boolean ir850;

    @Expose
    @SerializedName("ir940")
    private Boolean ir940;

    @Expose
    @SerializedName("white")
    private Boolean white;

    public SetCameraSettingsRequest() {
    }

    public static SetCameraSettingsRequest motion(boolean enabled) {
        SetCameraSettingsRequest request = new SetCameraSettingsRequest();
        request.motionEnabled = enabled;
        return request;
    }

    public static SetCameraSettingsRequest mic(boolean enabled) {
        SetCameraSettingsRequest request = new SetCameraSettingsRequest();
        request.micEnabled = enabled;
        return request;
    }

    public static SetCameraSettingsRequest speaker(boolean enabled) {
        SetCameraSettingsRequest request = new SetCameraSettingsRequest();
        request.speakerEnabled = enabled;
        return request;
    }

    public static SetCameraSettingsRequest daynightAuto() {
        SetCameraSettingsRequest request = new SetCameraSettingsRequest();
        request.daynightAuto = true;
        return request;
    }

    public static SetCameraSettingsRequest daynightMode(String mode) {
        SetCameraSettingsRequest request = new SetCameraSettingsRequest();
        request.daynightMode = mode;
        return request;
    }

    public static SetCameraSettingsRequest ircut(boolean enabled) {
        SetCameraSettingsRequest request = new SetCameraSettingsRequest();
        request.ircut = enabled;
        return request;
    }

    public static SetCameraSettingsRequest ir850(boolean enabled) {
        SetCameraSettingsRequest request = new SetCameraSettingsRequest();
        request.ir850 = enabled;
        return request;
    }

    public static SetCameraSettingsRequest ir940(boolean enabled) {
        SetCameraSettingsRequest request = new SetCameraSettingsRequest();
        request.ir940 = enabled;
        return request;
    }

    public static SetCameraSettingsRequest white(boolean enabled) {
        SetCameraSettingsRequest request = new SetCameraSettingsRequest();
        request.white = enabled;
        return request;
    }
}
