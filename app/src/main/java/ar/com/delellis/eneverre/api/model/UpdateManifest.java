package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.List;

public class UpdateManifest implements Serializable {
    @Expose
    @SerializedName("versionName")
    private String versionName;

    @Expose
    @SerializedName("versionCode")
    private int versionCode;

    @Expose
    @SerializedName("mandatory")
    private boolean mandatory;

    @Expose
    @SerializedName("releaseNotes")
    private String releaseNotes;

    @Expose
    @SerializedName("builds")
    private List<UpdateBuild> builds;

    public String getVersionName() {
        return versionName;
    }

    public int getVersionCode() {
        return versionCode;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public String getReleaseNotes() {
        return releaseNotes;
    }

    public List<UpdateBuild> getBuilds() {
        return builds;
    }
}
