package ar.com.delellis.eneverre.api;

import java.util.List;

import ar.com.delellis.eneverre.api.model.Camera;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

public interface ApiService {
    @GET("cameras")
    Call<List<Camera>> cameras(@Header("Authorization") String authorization);

    @POST("camera/{device_id}/ptz/home")
    Call<Void> home(@Header("Authorization") String authorization, @Path("device_id") String device_id);

    @POST("camera/{device_id}/ptz/move")
    Call<Void> move(@Header("Authorization") String authorization, @Path("device_id") String device_id, @Query("x") int x, @Query("y") int y);

    @POST("camera/{device_id}/ptz/recalibrate")
    Call<Void> recalibrate(@Header("Authorization") String authorization, @Path("device_id") String device_id);

    @POST("camera/{device_id}/privacy")
    Call<Void> privacy(@Header("Authorization") String authorization, @Path("device_id") String device_id, @Query("enable") boolean enable);
}
