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

public class CamerasAdapter extends RecyclerView.Adapter<CamerasAdapterHolder> implements View.OnClickListener {
    Context context;
    View.OnClickListener onClickListener = null;
    List<Camera> cameraList;

    public CamerasAdapter(Context context, List<Camera> cameraList) {
        this.context = context;
        this.cameraList = cameraList;
    }

    @Override
    public CamerasAdapterHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.item_camera, parent, false);

        view.setOnClickListener(this);
        return new CamerasAdapterHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CamerasAdapterHolder holder, int position) {
        Camera camera = cameraList.get(position);

        int imageId = camera.getPrivacy() ? R.drawable.ic_privacy_24 : R.drawable.ic_image_24;

        holder.setImage(ContextCompat.getDrawable(context, imageId));
        holder.setName(camera.getName());
        holder.setLocation(camera.getLocation());
        holder.setComment(camera.getComment());
    }

    @Override
    public int getItemCount() {
        return cameraList.size();
    }

    public void setOnClickListener(View.OnClickListener onClickListener) {
        this.onClickListener = onClickListener;
    }

    @Override
    public void onClick(View view) {
        if (onClickListener != null) {
            onClickListener.onClick(view);
        }
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
