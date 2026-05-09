package ar.com.delellis.eneverre;

import static android.widget.Toast.LENGTH_LONG;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ar.com.delellis.eneverre.adapter.LocationsAdapter;
import ar.com.delellis.eneverre.adapter.OnCameraClickListener;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.ApiService;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.UserCode;
import ar.com.delellis.eneverre.api.model.VerifyStatus;
import ar.com.delellis.eneverre.model.Location;
import ar.com.delellis.eneverre.model.Locations;
import ar.com.delellis.eneverre.util.UserCodePickerDialog;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class CamerasActivity extends AppCompatActivity implements OnCameraClickListener {
    private static final String TAG = "CamerasActivity";

    private static final int INTENT_LIVE_VIEW = 100;

    public static final String RAW_CAMERAS_LIST_DATA = "RAW_CAMERA_LIST";
    public static final String LOCATION_CAMERAS_DATA = "LOCATION_CAMERAS";

    public static final String SELECTED_CAMERA_DATA = "SELECTED_CAMERA";

    private Locations locations = null;

    private LocationsAdapter locationsAdapter = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cameras);

        Toolbar myToolbar = findViewById(R.id.cameras_toolbar);
        setSupportActionBar(myToolbar);

        Intent intent = getIntent();
        List<Camera> cameraList = (List<Camera>) intent.getSerializableExtra(RAW_CAMERAS_LIST_DATA);

        RecyclerView recyclerView = findViewById(R.id.camera_list_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        locations = new Locations(cameraList);
        locationsAdapter = new LocationsAdapter(this, locations, this);
        recyclerView.setAdapter(locationsAdapter);
    }

    @Override
    protected void onActivityResult (int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == INTENT_LIVE_VIEW && resultCode == RESULT_OK) {
            Location location = (Location) data.getSerializableExtra(LOCATION_CAMERAS_DATA);
            // TODO: Update privacy icons.
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.cameras_top_app_bar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.about) {
            Toast.makeText(getApplicationContext(), R.string.about_eneverre, LENGTH_LONG).show();
            return true;
        }
        else if (itemId == R.id.linkDevice) {
            UserCodePickerDialog.show(this, code -> {
                onUserCodeRequest(code);
            });
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCameraClick(Camera camera, int position) {
        Location location = locations.get(camera.getLocation());

        Intent liveIntent = new Intent(CamerasActivity.this, LiveViewActivity.class);
        liveIntent.putExtra(LOCATION_CAMERAS_DATA, location);
        liveIntent.putExtra(SELECTED_CAMERA_DATA, position);
        startActivityForResult(liveIntent, INTENT_LIVE_VIEW);
    }

    private void onUserCodeRequest(String user_code) {
        ApiClient apiClient = ApiClient.getInstance();
        ApiService apiService = ApiClient.getApiService();

        UserCode userCode = new UserCode(user_code);

        Call<VerifyStatus> verifyStatusCall = apiService.device_verify(apiClient.getAuthorization(), userCode);
        verifyStatusCall.enqueue(new Callback<VerifyStatus>() {
            @Override
            public void onResponse(Call<VerifyStatus> call, Response<VerifyStatus> response) {
                // TODO: Check error code
                String status = response.body().getStatus();
                if ("approved".equals(status)) {
                    Toast.makeText(CamerasActivity.this, R.string.approved_device, LENGTH_LONG).show();
                } else if ("expired".equals(status)) {
                    Toast.makeText(CamerasActivity.this, R.string.the_code_has_expired, LENGTH_LONG).show();
                } else if ("invalid".equals(status)) {
                    Toast.makeText(CamerasActivity.this, R.string.invalid_user_code, LENGTH_LONG).show();
                }
            }
            @Override
            public void onFailure(Call<VerifyStatus> call, Throwable throwable) {
                Log.e(TAG, "onUserCodeRequest onFailure: " + throwable.getMessage());
                Toast.makeText(CamerasActivity.this, getString(R.string.device_linking_failed), LENGTH_LONG).show();
            }
        });
    }
}