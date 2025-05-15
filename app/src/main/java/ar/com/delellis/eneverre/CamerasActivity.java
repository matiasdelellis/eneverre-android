package ar.com.delellis.eneverre;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ar.com.delellis.eneverre.api.model.Camera;

public class CamerasActivity extends AppCompatActivity {
    private static final String TAG = "CamerasActivity";

    private static final int INTENT_VISUALIZE = 100;

    public static final String CAMERAS_LIST_DATA = "CAMERA_LIST";
    public static final String CURRENT_CAMERA_DATA = "CURRENT_CAMERA";

    private List<Camera> cameraList = null;

    private CamerasAdapter camerasAdapter = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cameras);

        Toolbar myToolbar = findViewById(R.id.cameras_toolbar);
        setSupportActionBar(myToolbar);

        Intent intent = getIntent();
        cameraList = (List<Camera>) intent.getSerializableExtra(CAMERAS_LIST_DATA);

        RecyclerView recyclerView = findViewById(R.id.camera_list_view);

        camerasAdapter = new CamerasAdapter(this, cameraList);
        camerasAdapter.setOnClickListener(view -> {
            Camera camera = cameraList.get(recyclerView.getChildAdapterPosition(view));

            Intent videoIntent = new Intent(CamerasActivity.this, VideoActivity.class);
            videoIntent.putExtra(CURRENT_CAMERA_DATA, camera);
            startActivityForResult(videoIntent, INTENT_VISUALIZE);
        });

        recyclerView.setAdapter(camerasAdapter);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2, GridLayoutManager.VERTICAL,false));
    }

    @Override
    protected void onActivityResult (int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == INTENT_VISUALIZE && resultCode == RESULT_OK) {
            Camera camera = (Camera) data.getSerializableExtra(CURRENT_CAMERA_DATA);
            camerasAdapter.updateCamera(camera.getId(), camera);
        }
    }
}