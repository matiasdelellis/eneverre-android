package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

public class VerifyStatus implements Serializable {
    @Expose
    @SerializedName("status")
    private String status;

    public String getStatus() {
        return status;
    }

}
