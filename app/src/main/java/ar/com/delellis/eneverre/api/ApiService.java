package ar.com.delellis.eneverre.api;

import java.util.List;

import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.EventsResponse;
import ar.com.delellis.eneverre.api.model.LoginRequest;
import ar.com.delellis.eneverre.api.model.LoginResponse;
import ar.com.delellis.eneverre.api.model.RefreshRequest;
import ar.com.delellis.eneverre.api.model.RefreshResponse;
import ar.com.delellis.eneverre.api.model.Recording;
import ar.com.delellis.eneverre.api.model.UserCode;
import ar.com.delellis.eneverre.api.model.VerifyStatus;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

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

    @POST("camera/{device_id}/ptz/home")
    Call<Void> home(@Path("device_id") String device_id);

    @POST("camera/{device_id}/ptz/move")
    Call<Void> move(@Path("device_id") String device_id, @Query("x") int x, @Query("y") int y);

    @POST("camera/{device_id}/ptz/recalibrate")
    Call<Void> recalibrate(@Path("device_id") String device_id);

    @POST("camera/{device_id}/privacy")
    Call<Void> privacy(@Path("device_id") String device_id, @Query("enable") boolean enable);

    @GET("camera/{device_id}/playback/list")
    Call<List<Recording>> recordings(@Path("device_id") String device_id, @Query("start") String start, @Query("end") String end);

    @GET("camera/{device_id}/playback/get")
    Call<ResponseBody> recording(@Path("device_id") String device_id, @Query("start") String start, @Query("duration") double duration);

    @GET("cameras/{camera_id}/events")
    Call<EventsResponse> events(@Path("camera_id") String camera_id, @Query("since") String since, @Query("until") String until);

    @POST("auth/device/verify")
    Call<VerifyStatus> device_verify(@Body UserCode userCode);
}