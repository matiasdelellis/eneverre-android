package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

public class VerifyStatus implements Serializable {
    @Expose
    @SerializedName("status")
    private String status;

    // Label of the device that was just authorized (the name it sent when it
    // requested the code). Null/absent for expired/invalid codes.
    @Expose
    @SerializedName("device_name")
    private String deviceName;

    public String getStatus() {
        return status;
    }

    public String getDeviceName() {
        return deviceName;
    }

}
