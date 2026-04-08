package ar.com.delellis.eneverre.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

public class Location implements Serializable {
    @Expose
    @SerializedName("name")
    private final String locationName;

    @Expose
    @SerializedName("cameras")
    private final Cameras cameras;

    public Location(String locationName, Cameras cameras) {
        this.locationName = locationName;
        this.cameras = cameras;
    }

    public String getName() {
        return locationName;
    }

    public Cameras getCameras() {
        return cameras;
    }
}