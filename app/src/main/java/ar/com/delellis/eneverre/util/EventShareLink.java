package ar.com.delellis.eneverre.util;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import java.util.List;

import ar.com.delellis.eneverre.CamerasActivity;
import ar.com.delellis.eneverre.ViewActivity;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.model.Cameras;
import ar.com.delellis.eneverre.model.Location;
import ar.com.delellis.eneverre.model.Locations;

/**
 * Builds and resolves the shareable "event" links sent over WhatsApp and the
 * like. A link is just a <i>pointer</i> to a camera and a moment on the same
 * backend; it grants no access. The recipient's app authenticates with its own
 * credentials and only opens the event if its {@code cameras()} list contains
 * the camera — so "share" really means "share with users who already have
 * access to the same cameras".
 *
 * <p>The link uses the backend host itself ({@code SecureStore.getConfigHost()})
 * as the domain, so no extra domain has to be registered. Format:
 * <pre>{@code <host>/share/event?c=<cameraId>&t=<startEpochMs>}</pre>
 * Only the start moment is carried: seeking there lands on the right event, so
 * the duration is redundant.
 */
public class EventShareLink {

    private static final String PATH = "/share/event";
    private static final String PARAM_CAMERA = "c";
    private static final String PARAM_TIME = "t";

    /** Parsed contents of a share link. */
    public static class Parsed {
        public final String cameraId;
        public final long startMs;

        Parsed(String cameraId, long startMs) {
            this.cameraId = cameraId;
            this.startMs = startMs;
        }
    }

    /** Builds a shareable link pointing at the event's start moment. */
    public static String build(String host, String cameraId, long startMs) {
        // getConfigHost() already carries the scheme (the user types it at login).
        String base = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        return Uri.parse(base).buildUpon()
                .path(PATH)
                .appendQueryParameter(PARAM_CAMERA, cameraId)
                .appendQueryParameter(PARAM_TIME, Long.toString(startMs))
                .build()
                .toString();
    }

    /** Returns the parsed link, or {@code null} if {@code uri} is not one of ours. */
    public static Parsed parse(Uri uri) {
        if (uri == null || uri.getPath() == null || !uri.getPath().startsWith(PATH)) {
            return null;
        }
        try {
            String cameraId = uri.getQueryParameter(PARAM_CAMERA);
            String time = uri.getQueryParameter(PARAM_TIME);
            if (cameraId == null || time == null) {
                return null;
            }
            return new Parsed(cameraId, Long.parseLong(time));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Resolves the link against the recipient's camera list and opens the
     * Playback tab at the event. Returns {@code false} if the camera is not in
     * the list (i.e. the recipient has no access, or it is another backend).
     */
    public static boolean launch(Activity activity, List<Camera> cameras, Parsed link) {
        if (cameras == null || link == null) {
            return false;
        }

        // Find the camera (hence its location) the recipient actually has.
        Camera target = null;
        for (Camera camera : cameras) {
            if (link.cameraId.equals(camera.getId())) {
                target = camera;
                break;
            }
        }
        if (target == null) {
            return false;
        }

        Locations locations = new Locations(cameras);
        Location location = locations.get(target.getLocation());
        if (location == null) {
            return false;
        }

        // Position of the camera within its location (the pager's index).
        int position = 0;
        Cameras locationCameras = location.getCameras();
        for (int i = 0; i < locationCameras.count(); i++) {
            if (link.cameraId.equals(locationCameras.get(i).getId())) {
                position = i;
                break;
            }
        }

        Intent intent = new Intent(activity, ViewActivity.class);
        intent.putExtra(CamerasActivity.LOCATION_CAMERAS_DATA, location);
        intent.putExtra(CamerasActivity.SELECTED_CAMERA_DATA, position);
        intent.putExtra(ViewActivity.EXTRA_START_ON_PLAYBACK, true);
        intent.putExtra(ViewActivity.EXTRA_INITIAL_TIME_MS, link.startMs);
        activity.startActivity(intent);
        return true;
    }
}
