package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

public class UpdateBuild implements Serializable {
    @Expose
    @SerializedName("variant")
    private String variant;

    @Expose
    @SerializedName("filename")
    private String filename;

    @Expose
    @SerializedName("size")
    private long size;

    @Expose
    @SerializedName("sha256")
    private String sha256;

    @Expose
    @SerializedName("contentType")
    private String contentType;

    @Expose
    @SerializedName("url")
    private String url;

    public String getVariant() {
        return variant;
    }

    public String getFilename() {
        return filename;
    }

    public long getSize() {
        return size;
    }

    public String getSha256() {
        return sha256;
    }

    public String getContentType() {
        return contentType;
    }

    public String getUrl() {
        return url;
    }
}
