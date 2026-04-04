package ar.com.delellis.eneverre.adapter.ui;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import ar.com.delellis.eneverre.R;

public class CamerasAdapterHolder extends RecyclerView.ViewHolder {
    ImageView cameraImageView;
    TextView cameraNameView, cameraCommentView;

    public CamerasAdapterHolder(@NonNull View itemView) {
        super(itemView);

        cameraImageView = itemView.findViewById(R.id.camera_image);
        cameraNameView = itemView.findViewById(R.id.camera_name);
        cameraCommentView = itemView.findViewById(R.id.camera_comment);
    }

    public void setImage(Drawable drawable)  {
        cameraImageView.setImageDrawable(drawable);
    }
    public void setName(String name)  {
        cameraNameView.setText(name);
    }
    public void setComment(String comment)  {
        cameraCommentView.setText(comment);
    }
}