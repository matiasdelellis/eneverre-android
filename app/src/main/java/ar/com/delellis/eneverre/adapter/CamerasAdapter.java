package ar.com.delellis.eneverre.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.adapter.ui.CamerasAdapterHolder;
import ar.com.delellis.eneverre.api.model.Camera;

public class CamerasAdapter extends RecyclerView.Adapter<CamerasAdapterHolder> {
    Context context;
    List<Camera> cameraList;
    private OnCameraClickListener listener;

    public CamerasAdapter(Context context, List<Camera> cameraList, OnCameraClickListener listener) {
        this.context = context;
        this.cameraList = cameraList;
        this.listener = listener;
    }

    @Override
    public CamerasAdapterHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.item_camera, parent, false);
        return new CamerasAdapterHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CamerasAdapterHolder holder, int position) {
        Camera camera = cameraList.get(position);

        int imageId = camera.getPrivacy() ? R.drawable.ic_privacy_24 : R.drawable.ic_image_24;

        holder.setImage(ContextCompat.getDrawable(context, imageId));
        holder.setName(camera.getName());
        holder.setComment(camera.getComment());

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCameraClick(camera);
            }
        });
    }

    @Override
    public int getItemCount() {
        return cameraList.size();
    }

    public void updateCamera(String cameraId, Camera camera) {
        // Until now only can change the privacy of the camera... we sync it.
        for (Camera item: cameraList) {
            if (item.getId().equals(cameraId)) {
                item.setPrivacy(camera.getPrivacy());
            }
        }
        notifyDataSetChanged();
    }
}
