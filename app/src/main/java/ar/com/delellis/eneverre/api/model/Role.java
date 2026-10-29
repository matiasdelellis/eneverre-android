package ar.com.delellis.eneverre.api.model;

/**
 * The server's fixed roles and the capabilities the phone UI derives from them.
 *
 * <p>Roles decide <em>what operations</em> an account may perform; a per-user
 * camera ACL decides <em>which cameras</em> those operations apply to. The ACL
 * needs no client logic: every camera response is already filtered, and a camera
 * outside it answers {@code 404} (not {@code 403}) on single-camera endpoints.
 *
 * <ul>
 *   <li>{@code admin} — everything, including users and camera configuration.</li>
 *   <li>{@code user} — live, playback, PTZ, privacy, talk and events.</li>
 *   <li>{@code viewer} — live and events; <b>never playback</b>, no
 *       PTZ/privacy/talk.</li>
 *   <li>{@code monitor} — {@code /api/status} and {@code /api/metrics} only; no
 *       camera list, live or playback.</li>
 * </ul>
 */
public final class Role {
    public static final String ADMIN = "admin";
    public static final String USER = "user";
    public static final String VIEWER = "viewer";
    public static final String MONITOR = "monitor";

    /** Every role, in the order the admin UI offers them. */
    public static final String[] ALL = {ADMIN, USER, VIEWER, MONITOR};

    private Role() {
    }

    public static boolean isAdmin(String role) {
        return ADMIN.equals(role);
    }

    public static boolean isMonitor(String role) {
        return MONITOR.equals(role);
    }

    /**
     * Whether the role may list and watch cameras at all (live + events).
     * {@code monitor} is the only role with no camera access.
     */
    public static boolean canViewCameras(String role) {
        return !MONITOR.equals(role);
    }

    /** Whether the role may open recordings. {@code viewer} never can. */
    public static boolean canPlayback(String role) {
        return ADMIN.equals(role) || USER.equals(role);
    }

    /** Whether the role may operate a camera: PTZ, privacy and talk. */
    public static boolean canOperate(String role) {
        return ADMIN.equals(role) || USER.equals(role);
    }
}
