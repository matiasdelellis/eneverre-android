package ar.com.delellis.eneverre.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.adapter.ui.LocationsViewHolder;
import ar.com.delellis.eneverre.model.Location;
import ar.com.delellis.eneverre.model.Locations;

public class LocationsAdapter extends RecyclerView.Adapter<LocationsViewHolder> {
    private final Context context;
    private final Locations locations;
    private final OnCameraClickListener listener;

    public LocationsAdapter(Context context, Locations locationList, OnCameraClickListener listener) {
        this.context = context;
        this.locations = locationList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public LocationsViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.row_location_cameras, parent, false);
        return new LocationsViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LocationsViewHolder holder, int position) {
        Location location = locations.get(position);
        holder.setLocationName(location.getName());
        CamerasAdapter adapter = new CamerasAdapter(context, location.getCameras(), listener);
        holder.setupRecyclerView(context, adapter);
    }

    @Override
    public int getItemCount() {
        return locations == null ? 0 : locations.count();
    }
}