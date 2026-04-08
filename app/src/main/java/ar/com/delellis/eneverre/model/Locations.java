package ar.com.delellis.eneverre.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ar.com.delellis.eneverre.api.model.Camera;

public class Locations implements Serializable {
    @Expose
    @SerializedName("locations")
    private final List<Location> locationList;

    public Locations(List<Camera> rawCameras) {
        Map<String, List<Camera>> cameraLocationMap;

        // Sort by name
        rawCameras.sort((o1, o2) ->
                o1.getName().compareToIgnoreCase(o2.getName()));

        // Map to cameras in locations..
        cameraLocationMap = new HashMap<>();
        for (Camera camera: rawCameras) {
            String location = camera.getLocation();

            List<Camera> cameras = cameraLocationMap.get(location);
            if (cameras == null) cameras = new ArrayList<>();

            cameras.add(camera);
            cameraLocationMap.put(location, cameras);
        }

        // Fill locationList of cameras.
        locationList = new ArrayList<>();
        for (Map.Entry<String, List<Camera>> entry : cameraLocationMap.entrySet()) {
            locationList.add(
                    new Location(entry.getValue().get(0).getLocation(), new Cameras(entry.getValue()))
            );
        }
    }

    public Location get(int i) {
        return locationList.get(i);
    }

    public Location get(String locationName) {
        for (Location location: locationList) {
            if (locationName.equals(location.getName())) {
                return location;
            }
        }
        return null;
    }

    public int count() {
        return locationList.size();
    }
}