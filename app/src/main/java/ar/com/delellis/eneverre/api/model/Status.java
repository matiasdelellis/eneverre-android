package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * Response of {@code GET /api/status} — the only thing a {@code monitor}
 * account can read besides {@code /api/metrics}.
 *
 * <p>Gson ignores the per-camera array (which this screen does not need); the
 * aggregate {@code totals} and the storage headroom are enough to show that the
 * server and its media engine are alive.
 */
public class Status {
    @Expose
    @SerializedName("service")
    private String service;

    @Expose
    @SerializedName("version")
    private String version;

    @Expose
    @SerializedName("uptime_seconds")
    private long uptimeSeconds;

    @Expose
    @SerializedName("recording_enabled")
    private boolean recordingEnabled;

    @Expose
    @SerializedName("totals")
    private Totals totals;

    @Expose
    @SerializedName("storage")
    private Storage storage;

    public String getService() {
        return service;
    }

    public String getVersion() {
        return version;
    }

    public long getUptimeSeconds() {
        return uptimeSeconds;
    }

    public boolean isRecordingEnabled() {
        return recordingEnabled;
    }

    public Totals getTotals() {
        return totals;
    }

    /** Null when the server has no recording volume to report. */
    public Storage getStorage() {
        return storage;
    }

    public static class Totals {
        @Expose
        @SerializedName("cameras")
        private int cameras;

        @Expose
        @SerializedName("connected")
        private int connected;

        @Expose
        @SerializedName("recording")
        private int recording;

        @Expose
        @SerializedName("privacy")
        private int privacy;

        @Expose
        @SerializedName("disabled")
        private int disabled;

        public int getCameras() {
            return cameras;
        }

        public int getConnected() {
            return connected;
        }

        public int getRecording() {
            return recording;
        }

        public int getPrivacy() {
            return privacy;
        }

        public int getDisabled() {
            return disabled;
        }
    }

    public static class Storage {
        @Expose
        @SerializedName("total_bytes")
        private long totalBytes;

        @Expose
        @SerializedName("free_bytes")
        private long freeBytes;

        @Expose
        @SerializedName("used_bytes")
        private long usedBytes;

        @Expose
        @SerializedName("low")
        private boolean low;

        public long getTotalBytes() {
            return totalBytes;
        }

        public long getFreeBytes() {
            return freeBytes;
        }

        public long getUsedBytes() {
            return usedBytes;
        }

        public boolean isLow() {
            return low;
        }
    }
}
