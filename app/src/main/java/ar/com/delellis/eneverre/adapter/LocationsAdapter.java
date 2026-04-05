package ar.com.delellis.eneverre.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.adapter.ui.LocationsViewHolder;
import ar.com.delellis.eneverre.model.Location;

public class LocationsAdapter extends RecyclerView.Adapter<LocationsViewHolder> {
    private final Context context;
    private final List<Location> locationList;
    private final OnCameraClickListener listener;

    public LocationsAdapter(Context context, List<Location> locationList, OnCameraClickListener listener) {
        this.context = context;
        this.locationList = locationList;
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
        Location location = locationList.get(position);
        holder.setLocationName(location.getLocationName());
        CamerasAdapter adapter = new CamerasAdapter(context, location.getCameraList(), listener);
        holder.setupRecyclerView(context, adapter);
    }

    @Override
    public int getItemCount() {
        return locationList == null ? 0 : locationList.size();
    }
}