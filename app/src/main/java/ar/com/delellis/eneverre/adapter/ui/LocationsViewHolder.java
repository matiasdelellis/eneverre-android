package ar.com.delellis.eneverre.adapter.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.adapter.CamerasAdapter;

public class LocationsViewHolder extends RecyclerView.ViewHolder {
    private final TextView locationName;
    private final RecyclerView rowCameras;

    public LocationsViewHolder(@NonNull View itemView) {
        super(itemView);

        locationName = itemView.findViewById(R.id.location_name);
        rowCameras = itemView.findViewById(R.id.row_cameras);
    }

    public void setLocationName(String locationName) {
        this.locationName.setText(locationName);
    }

    public void setupRecyclerView(Context context, CamerasAdapter adapter) {
        rowCameras.setLayoutManager(new GridLayoutManager(context, 2, GridLayoutManager.VERTICAL,false));
        rowCameras.setAdapter(adapter);
    }
}