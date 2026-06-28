package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

public class UpdateBuild implements Serializable {
    @Expose
    @SerializedName("abi")
    private String abi;

    @Expose
    @SerializedName("apkFilename")
    private String apkFilename;

    @Expose
    @SerializedName("size")
    private long size;

    @Expose
    @SerializedName("sha256")
    private String sha256;

    @Expose
    @SerializedName("url")
    private String url;

    public String getAbi() {
        return abi;
    }

    public String getApkFilename() {
        return apkFilename;
    }

    public long getSize() {
        return size;
    }

    public String getSha256() {
        return sha256;
    }

    public String getUrl() {
        return url;
    }
}
