package ar.com.delellis.eneverre;

import static android.widget.Toast.LENGTH_LONG;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ar.com.delellis.eneverre.adapter.LocationsAdapter;
import ar.com.delellis.eneverre.adapter.OnCameraClickListener;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.UserCode;
import ar.com.delellis.eneverre.api.model.VerifyStatus;
import ar.com.delellis.eneverre.model.Location;
import ar.com.delellis.eneverre.model.Locations;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.ApiError;
import ar.com.delellis.eneverre.util.UserCodePickerDialog;

public class CamerasActivity extends AppCompatActivity implements OnCameraClickListener {

    public static final String RAW_CAMERAS_LIST_DATA = "RAW_CAMERA_LIST";
    public static final String LOCATION_CAMERAS_DATA = "LOCATION_CAMERAS";

    public static final String SELECTED_CAMERA_DATA = "SELECTED_CAMERA";

    private Locations locations = null;

    private LocationsAdapter locationsAdapter = null;

    private final ActivityResultLauncher<Intent> liveViewLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Location location = (Location) result.getData().getSerializableExtra(LOCATION_CAMERAS_DATA);
                    // TODO: Update privacy icons.
                }
            });

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
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.cameras_top_app_bar, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.about) {
            startActivity(new Intent(this, AboutActivity.class));
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

        Intent liveIntent = new Intent(CamerasActivity.this, ViewActivity.class);
        liveIntent.putExtra(LOCATION_CAMERAS_DATA, location);
        liveIntent.putExtra(SELECTED_CAMERA_DATA, position);
        liveViewLauncher.launch(liveIntent);
    }

    private void onUserCodeRequest(String user_code) {
        UserCode userCode = new UserCode(user_code);

        ApiClient.getApiService().device_verify(userCode).enqueue(new ApiCallback<VerifyStatus>(this) {
            @Override
            public void onSuccess(VerifyStatus verifyStatus) {
                if (verifyStatus == null) {
                    onError(ApiError.NO_HTTP_CODE, null);
                    return;
                }

                String status = verifyStatus.getStatus();
                if ("approved".equals(status)) {
                    Toast.makeText(CamerasActivity.this, R.string.approved_device, LENGTH_LONG).show();
                } else if ("expired".equals(status)) {
                    Toast.makeText(CamerasActivity.this, R.string.the_code_has_expired, LENGTH_LONG).show();
                } else if ("invalid".equals(status)) {
                    Toast.makeText(CamerasActivity.this, R.string.invalid_user_code, LENGTH_LONG).show();
                }
            }
            @Override
            public void onError(int httpCode, String message) {
                Toast.makeText(CamerasActivity.this, R.string.device_linking_failed, LENGTH_LONG).show();
            }
        });
    }
}