package ar.com.delellis.eneverre.model;

import java.util.List;

import ar.com.delellis.eneverre.api.model.Camera;

public class Location {
    private final String locationName;
    private final List<Camera> cameraList;

    public Location(String locationName, List<Camera> cameraList) {
        this.locationName = locationName;
        this.cameraList = cameraList;
    }

    public String getLocationName() {
        return locationName;
    }

    public List<Camera> getCameraList() {
        return cameraList;
    }
}