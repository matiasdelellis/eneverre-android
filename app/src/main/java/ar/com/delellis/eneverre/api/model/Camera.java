package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.List;

@SuppressWarnings("unused")
public class Camera implements Serializable {
    @Expose
    @SerializedName("id")
    private String id;

    @Expose
    @SerializedName("name")
    private String name;

    @Expose
    @SerializedName("location")
    private String location;

    @Expose
    @SerializedName("comment")
    private String comment;

    @Expose
    @SerializedName("rtsp")
    private String rtsp;

    @Expose
    @SerializedName("width")
    private int width;

    @Expose
    @SerializedName("height")
    private int height;

    // Current privacy state (mutable at runtime via the privacy toggle), not a
    // capability: whether the camera *supports* privacy is capabilities.privacy.
    @Expose
    @SerializedName("privacy")
    private boolean privacy;

    @Expose
    @SerializedName("capabilities")
    private Capabilities capabilities;

    // Public PTZ metadata block, present only when capabilities.ptz is true
    // AND the server is new enough to expose it. Null on older servers, which
    // is the signal to fall back to the legacy step-based move parameters.
    @Expose
    @SerializedName("ptz")
    private PtzMetadata ptz;

    /**
     * Angular PTZ metadata: the lens field of view and the gimbal's travel
     * range, all in degrees. The steps↔degrees calibration is server-side;
     * clients talk to the move endpoint in degrees only.
     */
    public static class PtzMetadata implements Serializable {
        @Expose
        @SerializedName("fov_h")
        private float fovH;

        @Expose
        @SerializedName("fov_v")
        private float fovV;

        @Expose
        @SerializedName("pan_range")
        private float panRange;

        @Expose
        @SerializedName("tilt_range")
        private float tiltRange;

        public float getFovH() {
            return fovH;
        }

        public float getFovV() {
            return fovV;
        }

        public float getPanRange() {
            return panRange;
        }

        public float getTiltRange() {
            return tiltRange;
        }
    }

    /**
     * Nested capability flags: whether the camera <em>supports</em> each feature.
     * These are the source of truth for what UI to offer. (Not to be confused with
     * the flat {@code privacy} field above, which is the current on/off state.)
     */
    public static class Capabilities implements Serializable {
        @Expose
        @SerializedName("privacy")
        private boolean privacy;

        @Expose
        @SerializedName("thumbnail")
        private boolean thumbnail;

        @Expose
        @SerializedName("playback")
        private boolean playback;

        @Expose
        @SerializedName("ptz")
        private boolean ptz;

        @Expose
        @SerializedName("talk")
        private boolean talk;

        // Codecs the camera's backchannel actually accepts, probed from its SDP at
        // startup (e.g. ["aac", "g711"]). Used to negotiate the talk codec instead
        // of guessing. May be absent/empty if the probe hasn't finished or the
        // camera was unreachable — fall back to G.711 then (every one supports it).
        @Expose
        @SerializedName("talk_codecs")
        private List<String> talkCodecs;

        public boolean hasPrivacy() {
            return privacy;
        }

        public boolean hasThumbnail() {
            return thumbnail;
        }

        public boolean hasPlayback() {
            return playback;
        }

        public boolean hasPtz() {
            return ptz;
        }

        public boolean hasTalk() {
            return talk;
        }

        public List<String> getTalkCodecs() {
            return talkCodecs;
        }
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLocation() {
        return location;
    }

    public String getComment() {
        return comment;
    }

    public String getRtsp() {
        return rtsp;
    }

    public boolean hasPlayback() {
        return capabilities != null && capabilities.hasPlayback();
    }

    /** Whether the server can serve a JPEG snapshot for this camera (thumbnail endpoint). */
    public boolean hasThumbnail() {
        return capabilities != null && capabilities.hasThumbnail();
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean getPtz() {
        return capabilities != null && capabilities.hasPtz();
    }

    /** Angular PTZ metadata; null on servers that predate the degree-based PTZ API. */
    public PtzMetadata getPtzMetadata() {
        return ptz;
    }

    /** Whether the camera supports privacy mode (drives whether the toggle is offered). */
    public boolean hasPrivacy() {
        return capabilities != null && capabilities.hasPrivacy();
    }

    /** Current privacy state (toggled at runtime), not the privacy capability. */
    public boolean getPrivacy() {
        return privacy;
    }

    /** Whether the camera supports the two-way-audio (push-to-talk) backchannel. */
    public boolean hasTalk() {
        return capabilities != null && capabilities.hasTalk();
    }

    /**
     * Whether the camera's backchannel accepts AAC (wideband 16 kHz). When true,
     * the talk client should negotiate {@code codec=aac}; otherwise it falls back
     * to PCM/G.711, which every backchannel camera supports.
     */
    public boolean supportsTalkAac() {
        return capabilities != null
                && capabilities.getTalkCodecs() != null
                && capabilities.getTalkCodecs().contains("aac");
    }

    public void setPrivacy(boolean enable) {
        privacy = enable;
    }
}
