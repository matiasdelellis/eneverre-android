package ar.com.delellis.eneverre;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ar.com.delellis.eneverre.adapter.LocationsAdapter;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.model.Location;

public class CamerasActivity extends AppCompatActivity {
    private static final String TAG = "CamerasActivity";

    private static final int INTENT_VISUALIZE = 100;

    public static final String CAMERAS_LIST_DATA = "CAMERA_LIST";
    public static final String CURRENT_CAMERA_DATA = "CURRENT_CAMERA";

    private List<Location> locationList;
    private Map<String, List<Camera>> cameraLocationMap;

    private List<Camera> cameraList = null;

    private LocationsAdapter locationsAdapter = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cameras);

        Toolbar myToolbar = findViewById(R.id.cameras_toolbar);
        setSupportActionBar(myToolbar);

        Intent intent = getIntent();
        cameraList = (List<Camera>) intent.getSerializableExtra(CAMERAS_LIST_DATA);

        // Sort by name
        Collections.sort(cameraList, (o1, o2) ->
                o1.getName().compareToIgnoreCase(o2.getName())
        );

        // Map to cameras in locations..
        cameraLocationMap = new HashMap<>();
        for (Camera camera: cameraList) {
            String location = camera.getLocation();

            List<Camera> cameras = cameraLocationMap.get(location);
            if (cameras == null) cameras = new ArrayList<>();

            cameras.add(camera);
            cameraLocationMap.put(location, cameras);
        }

        // Fill locationList of cameras.
        locationList = new ArrayList<>();
        for (Map.Entry<String, List<Camera>> entry : cameraLocationMap.entrySet()) {
            locationList.add(
                    new Location(entry.getValue().get(0).getLocation(), entry.getValue())
            );
        }
        RecyclerView recyclerView = findViewById(R.id.camera_list_view);

        locationsAdapter = new LocationsAdapter(this, locationList);

        // Fixme:
        /*locationsAdapter.setOnClickListener(view -> {
            Camera camera = cameraList.get(recyclerView.getChildAdapterPosition(view));

            Intent videoIntent = new Intent(CamerasActivity.this, VideoActivity.class);
            videoIntent.putExtra(CURRENT_CAMERA_DATA, camera);
            startActivityForResult(videoIntent, INTENT_VISUALIZE);
        });*/

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(locationsAdapter);
    }

    @Override
    protected void onActivityResult (int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == INTENT_VISUALIZE && resultCode == RESULT_OK) {
            //FIXME:
            //Camera camera = (Camera) data.getSerializableExtra(CURRENT_CAMERA_DATA);
            //locationsAdapter.updateCamera(camera.getId(), camera);
        }
    }
}