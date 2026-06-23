package ar.com.delellis.eneverre.adapter.ui;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import ar.com.delellis.eneverre.R;

public class EventsAdapterHolder extends RecyclerView.ViewHolder {
    TextView typeView, timeView, durationView;

    public EventsAdapterHolder(@NonNull View itemView) {
        super(itemView);

        typeView = itemView.findViewById(R.id.event_type);
        timeView = itemView.findViewById(R.id.event_time);
        durationView = itemView.findViewById(R.id.event_duration);
    }

    public void setType(String type) {
        typeView.setText(type);
    }

    public void setTime(String time) {
        timeView.setText(time);
    }

    public void setDuration(String duration) {
        durationView.setText(duration);
    }

    public void setHighlighted(boolean highlighted) {
        itemView.setActivated(highlighted);
    }
}
