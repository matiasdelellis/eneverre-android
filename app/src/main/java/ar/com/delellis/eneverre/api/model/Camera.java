package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

public class Camera implements Serializable {
    @Expose
    @SerializedName("id")
    private String id;

    @Expose
    @SerializedName("name")
    private String name;

    @Expose
    @SerializedName("comment")
    private String comment;

    @Expose
    @SerializedName("live")
    private String live;

    @Expose
    @SerializedName("playback")
    private boolean playback;

    @Expose
    @SerializedName("width")
    private int width;

    @Expose
    @SerializedName("height")
    private int height;

    @Expose
    @SerializedName("ptz")
    private boolean ptz;

    @Expose
    @SerializedName("privacy")
    private boolean privacy;

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getComment() {
        return comment;
    }

    public String getLive() {
        return live;
    }

    public boolean hasPlayback() {
        return playback;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean getPtz() {
        return ptz;
    }

    public boolean getPrivacy() {
        return privacy;
    }

    public void setPrivacy(boolean enable) {
        privacy = enable;
    }
}
