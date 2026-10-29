package ar.com.delellis.eneverre.api;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.player.RtspUrl;
import ar.com.delellis.eneverre.util.SessionExpiry;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Fetches {@code GET /api/cameras} on behalf of the live views, which need a
 * fresh signed RTSP path token before they (re)connect.
 *
 * <p>The live URL's last path segment is a per-camera six-hour token: a new
 * connection with an expired one answers {@code 401}, while a session already
 * playing is unaffected. So every start is preceded by a check of the token
 * ({@link #shouldRefreshBeforeStart}) and a failure means "ask for new URLs",
 * not "the camera is down".
 *
 * <p>Two properties matter here:
 * <ul>
 *   <li><b>Coalescing</b> — a mosaic has one cell per camera and they all share
 *       one token lifetime. Concurrent requests collapse into a single
 *       {@code GET /api/cameras}, and every caller is answered with the same
 *       list (or {@code null} when it failed).</li>
 *   <li><b>Freshness clock</b> — {@link #markFresh()} records when a camera
 *       list was last obtained, whether by this class or by any other
 *       successful fetch, so a URL is not reused after the app sat in the
 *       background for hours even if its token still looks valid on a skewed
 *       clock.</li>
 * </ul>
 *
 * <p><b>Main thread only.</b> Callers are UI components and Retrofit delivers
 * its callbacks there; listeners are invoked on the same thread.
 */
public final class CameraRefresher {

    /** Result of a refresh: the new camera list, or {@code null} on failure. */
    public interface Listener {
        void onCameras(@Nullable List<Camera> cameras);
    }

    /**
     * A camera list older than this is not trusted for a live start, even if
     * the token in it looks valid: six-hour tokens must not survive a long
     * pause in the background.
     */
    private static final long MAX_CAMERA_LIST_AGE_MS = 5 * 60 * 60 * 1000L;

    /** When a camera list was last obtained; 0 while unknown (process start). */
    private static long lastFetchAt = 0;

    /** The request in flight, if any: everyone else joins it instead of asking again. */
    private static Call<List<Camera>> inFlight = null;

    /** Callers waiting for {@link #inFlight} to finish. */
    private static final List<Listener> pending = new ArrayList<>();

    private CameraRefresher() {
    }

    /**
     * Whether a live start should fetch a new camera list first: the URL is
     * missing, its signed token is (nearly) expired, or the list it came from
     * is too old to trust.
     */
    public static boolean shouldRefreshBeforeStart(@Nullable String rtspUrl) {
        if (rtspUrl == null || rtspUrl.isEmpty()) {
            return true;
        }
        if (RtspUrl.isExpiring(rtspUrl)) {
            return true;
        }
        if (lastFetchAt == 0) {
            // Unknown: no reason to force a request on the first start.
            return false;
        }
        return SystemClock.elapsedRealtime() - lastFetchAt > MAX_CAMERA_LIST_AGE_MS;
    }

    /**
     * Records that the caller has just obtained a fresh camera list, so its
     * URLs may be reused for the next few hours.
     */
    public static void markFresh() {
        lastFetchAt = SystemClock.elapsedRealtime();
    }

    /**
     * Fetches the camera list, or joins the request already in flight. Every
     * listener is answered exactly once, with the same result.
     */
    public static void refresh(Listener listener) {
        pending.add(listener);
        if (inFlight != null) {
            return;
        }
        inFlight = ApiClient.getApiService().cameras();
        inFlight.enqueue(new Callback<List<Camera>>() {
            @Override
            public void onResponse(Call<List<Camera>> call, Response<List<Camera>> response) {
                // This refresh shares the app's session: a 401 means the session
                // itself is dead, so recover centrally (clear + login) instead of
                // leaving the live views retrying with a token that cannot work.
                if (response.code() == 401) {
                    SessionExpiry.expire();
                }
                deliver(response.isSuccessful() ? response.body() : null);
            }

            @Override
            public void onFailure(Call<List<Camera>> call, Throwable throwable) {
                deliver(null);
            }
        });
    }

    /**
     * Copies the fresh RTSP URL for {@code target}'s camera out of {@code fresh},
     * matched by id, <b>in place</b>. Updating in place keeps every holder's
     * reference valid, so the mosaic can restart just the cells that need it
     * instead of rebuilding the whole wall.
     *
     * @return true when {@code fresh} contained that camera and its URL was applied
     */
    public static boolean applyRtsp(Camera target, List<Camera> fresh) {
        if (target == null || target.getId() == null || fresh == null) {
            return false;
        }
        for (Camera camera : fresh) {
            if (target.getId().equals(camera.getId())) {
                target.setRtsp(camera.getRtsp());
                return true;
            }
        }
        return false;
    }

    /** Answers everyone waiting, on the main thread. */
    private static void deliver(@Nullable List<Camera> cameras) {
        inFlight = null;
        if (cameras != null) {
            markFresh();
        }
        // Copy first: a listener may trigger another refresh from its callback.
        List<Listener> listeners = new ArrayList<>(pending);
        pending.clear();
        for (Listener listener : listeners) {
            listener.onCameras(cameras);
        }
    }
}
