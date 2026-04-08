package ar.com.delellis.eneverre.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.List;

import ar.com.delellis.eneverre.api.model.Camera;

public class Cameras implements Serializable {
    @Expose
    @SerializedName("cameras")
    private final List<Camera> cameraList;

    public Cameras(List<Camera> cameraList) {
        this.cameraList = cameraList;
    }

    public Camera get(int id) {
        return cameraList.get(id);
    }

    public int count() {
        return cameraList.size();
    }

    public boolean update(Camera camera) {
        for (Camera cam : cameraList) {
            if (cam.getId().equals(camera.getId())) {
                boolean changed = cam.getPrivacy() != camera.getPrivacy();
                cam.setPrivacy(camera.getPrivacy());
                return changed;
            }
        }
        return false;
    }
}