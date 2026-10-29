package ar.com.delellis.eneverre.api;

import java.util.List;

import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.CameraConfig;
import ar.com.delellis.eneverre.api.model.CameraSettings;
import ar.com.delellis.eneverre.api.model.ChangePasswordRequest;
import ar.com.delellis.eneverre.api.model.CreateUserRequest;
import ar.com.delellis.eneverre.api.model.EventsResponse;
import ar.com.delellis.eneverre.api.model.LoginRequest;
import ar.com.delellis.eneverre.api.model.LoginResponse;
import ar.com.delellis.eneverre.api.model.RefreshRequest;
import ar.com.delellis.eneverre.api.model.RefreshResponse;
import ar.com.delellis.eneverre.api.model.SessionsResponse;
import ar.com.delellis.eneverre.api.model.PtzPosition;
import ar.com.delellis.eneverre.api.model.Recording;
import ar.com.delellis.eneverre.api.model.RecordingsTimeline;
import ar.com.delellis.eneverre.api.model.SetCameraSettingsRequest;
import ar.com.delellis.eneverre.api.model.Status;
import ar.com.delellis.eneverre.api.model.UpdateManifest;
import ar.com.delellis.eneverre.api.model.UpdateNameRequest;
import ar.com.delellis.eneverre.api.model.UpdatePasswordRequest;
import ar.com.delellis.eneverre.api.model.UpdateRoleRequest;
import ar.com.delellis.eneverre.api.model.User;
import ar.com.delellis.eneverre.api.model.UserCameras;
import ar.com.delellis.eneverre.api.model.UserCode;
import ar.com.delellis.eneverre.api.model.VerifyStatus;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.Streaming;
import retrofit2.http.Url;

public interface ApiService {
    /** Exchanges username/password for a token pair; no auth header required. */
    @POST("auth/login")
    Call<LoginResponse> login(@Body LoginRequest credentials);

    /** Exchanges a refresh token for a fresh (rotated) token pair; no auth header required. */
    @POST("auth/refresh")
    Call<RefreshResponse> refresh(@Body RefreshRequest refreshToken);

    /** Revokes the current Bearer token (server-side session) on logout. */
    @POST("auth/logout")
    Call<Void> logout();

    @GET("cameras")
    Call<List<Camera>> cameras();

    /**
     * Server and media-engine status ({@code status:read}). The only camera-ish
     * data a {@code monitor} account can see: the server filters the per-camera
     * block by the account's ACL.
     */
    @GET("status")
    Call<Status> status();

    /**
     * Change the current user's password. Sent through the change-password flow
     * the server mandates via {@code must_change_password} on login. The response
     * body ({@code Message}) is ignored; a 2xx means it succeeded.
     */
    @PUT("users/me/password")
    Call<Void> changePassword(@Body ChangePasswordRequest request);

    /** Lists the current user's login sessions (active and expired). */
    @GET("users/me/sessions")
    Call<SessionsResponse> sessions();

    /** Revokes one of the current user's sessions by id. */
    @DELETE("users/me/sessions/{session_id}")
    Call<Void> revokeSession(@Path("session_id") long session_id);

    // --- User administration (admin only; gated server-side by role == "admin") ---

    /** Lists every account. */
    @GET("users")
    Call<List<User>> users();

    /**
     * Creates a user. Server returns a message body (ignored — a 2xx means it
     * succeeded); a duplicate username comes back as {@code 400 "User exists"}.
     */
    @POST("users")
    Call<Void> createUser(@Body CreateUserRequest request);

    /** Updates a user's first/last name. */
    @PUT("users/{username}/name")
    Call<Void> updateUserName(@Path("username") String username, @Body UpdateNameRequest request);

    /** Changes a user's role. The server rejects demoting the last admin with a 400. */
    @PUT("users/{username}/role")
    Call<Void> updateUserRole(@Path("username") String username, @Body UpdateRoleRequest request);

    /** Resets a user's password (revokes their sessions server-side). */
    @PUT("users/{username}/password")
    Call<Void> updateUserPassword(@Path("username") String username, @Body UpdatePasswordRequest request);

    /** Deletes a user. The server rejects deleting the last admin with a 400. */
    @DELETE("users/{username}")
    Call<Void> deleteUser(@Path("username") String username);

    /**
     * Per-user camera ACL (admin only): whether the account is restricted and,
     * when it is, which cameras it may see. An unrestricted account sees every
     * camera, and an admin bypasses the ACL entirely.
     */
    @GET("users/{username}/cameras")
    Call<UserCameras> userCameras(@Path("username") String username);

    /**
     * Replaces a user's camera ACL. {@code {"restricted":false}} restores all
     * cameras; {@code {"restricted":true,"camera_ids":[...]}} limits the account
     * to the listed ones (an empty list means none). Unknown ids answer 422.
     */
    @PUT("users/{username}/cameras")
    Call<UserCameras> setUserCameras(@Path("username") String username, @Body UserCameras acl);

    /**
     * JPEG snapshot proxied from the camera (only when {@code capabilities.thumbnail}
     * is set). Returns 404/502 on cameras without a reachable snapshot source, so
     * callers must degrade to a placeholder.
     */
    @Streaming
    @GET("camera/{device_id}/thumbnail")
    Call<ResponseBody> thumbnail(@Path("device_id") String device_id);

    @POST("camera/{device_id}/ptz/home")
    Call<Void> home(@Path("device_id") String device_id);

    /**
     * Relative PTZ move in degrees (pan > 0 = right, tilt > 0 = down). The
     * server converts to firmware steps using the camera's calibration and
     * clamps to the mechanical range.
     */
    @POST("camera/{device_id}/ptz/move")
    Call<Void> move(@Path("device_id") String device_id, @Query("pan") float pan, @Query("tilt") float tilt);

    @POST("camera/{device_id}/ptz/recalibrate")
    Call<Void> recalibrate(@Path("device_id") String device_id);

    /**
     * Last known motor position in degrees ({@code pan}, {@code tilt}). 503
     * while the server's cache is cold (no move recorded and the startup probe
     * did not reach the camera).
     */
    @GET("camera/{device_id}/ptz/position")
    Call<PtzPosition> ptzPosition(@Path("device_id") String device_id);

    /**
     * Full stored camera configuration (admin only), including the
     * source/backchannel/thingino credentials — the prefill for an edit form.
     */
    @GET("camera/{device_id}/config")
    Call<CameraConfig> cameraConfig(@Path("device_id") String device_id);

    /**
     * Replaces a camera's configuration (admin only). Expects the complete
     * body ({@code name}/{@code source} required); the server tears down and
     * rebuilds the media pipeline live, so changes take effect without a
     * restart.
     */
    @PUT("camera/{device_id}")
    Call<Void> updateCamera(@Path("device_id") String device_id, @Body CameraConfig config);

    @POST("camera/{device_id}/privacy")
    Call<Void> privacy(@Path("device_id") String device_id, @Query("enable") boolean enable);

    /**
     * Live-settings snapshot of one camera (day/night, illuminators, motion,
     * audio) — admin only. 404 while the server has not fetched a snapshot yet
     * or when the camera lacks the settings capability, so callers must
     * degrade gracefully.
     */
    @GET("camera/{device_id}/settings")
    Call<CameraSettings> cameraSettings(@Path("device_id") String device_id);

    /**
     * Applies a partial settings update to one camera (admin only). Each
     * non-null field of the body is applied in order; on the first failure the
     * request answers 502 naming the failing field.
     */
    @PUT("camera/{device_id}/settings")
    Call<Void> setCameraSettings(@Path("device_id") String device_id, @Body SetCameraSettingsRequest request);

    @GET("camera/{device_id}/recordings/list")
    Call<List<Recording>> recordings(@Path("device_id") String device_id, @Query("start") String start, @Query("end") String end);

    @Streaming
    @GET("camera/{device_id}/recordings/get")
    Call<ResponseBody> recording(@Path("device_id") String device_id, @Query("start") String start, @Query("duration") double duration);

    /**
     * Recorded extent (first start / last end / segment count) for a camera.
     * Embedded media engine only — returns 404 on servers without it, so callers
     * must degrade gracefully.
     */
    @GET("camera/{device_id}/recordings/timeline")
    Call<RecordingsTimeline> recordingsTimeline(@Path("device_id") String device_id);

    @GET("camera/{camera_id}/events")
    Call<EventsResponse> events(@Path("camera_id") String camera_id, @Query("since") String since, @Query("until") String until, @Query("limit") int limit, @Query("offset") int offset);

    @POST("auth/device/verify")
    Call<VerifyStatus> device_verify(@Body UserCode userCode);

    /**
     * Auto-update manifest for the phone track. Anonymous on the server side
     * (no auth header is sent because there are no tokens yet at this point
     * of the flow); the existing Bearer interceptor simply has nothing to
     * stamp.
     */
    @GET("app/phone/update")
    Call<UpdateManifest> checkUpdate();

    /**
     * Downloads the APK at an absolute URL returned by the update manifest.
     * Uses {@code @Url} so the call bypasses Retrofit's base-URL composition.
     */
    @Streaming
    @GET
    Call<ResponseBody> downloadUpdate(@Url String url);
}