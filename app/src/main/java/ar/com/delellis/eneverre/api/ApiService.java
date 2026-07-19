package ar.com.delellis.eneverre.api;

import java.util.List;

import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.ChangePasswordRequest;
import ar.com.delellis.eneverre.api.model.EventsResponse;
import ar.com.delellis.eneverre.api.model.LoginRequest;
import ar.com.delellis.eneverre.api.model.LoginResponse;
import ar.com.delellis.eneverre.api.model.RefreshRequest;
import ar.com.delellis.eneverre.api.model.RefreshResponse;
import ar.com.delellis.eneverre.api.model.SessionsResponse;
import ar.com.delellis.eneverre.api.model.Recording;
import ar.com.delellis.eneverre.api.model.RecordingsTimeline;
import ar.com.delellis.eneverre.api.model.UpdateManifest;
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

    /**
     * JPEG snapshot proxied from the camera (only when {@code capabilities.thumbnail}
     * is set). Returns 404/502 on cameras without a reachable snapshot source, so
     * callers must degrade to a placeholder.
     */
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

    @POST("camera/{device_id}/privacy")
    Call<Void> privacy(@Path("device_id") String device_id, @Query("enable") boolean enable);

    @GET("camera/{device_id}/recordings/list")
    Call<List<Recording>> recordings(@Path("device_id") String device_id, @Query("start") String start, @Query("end") String end);

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
    @GET
    Call<ResponseBody> downloadUpdate(@Url String url);
}