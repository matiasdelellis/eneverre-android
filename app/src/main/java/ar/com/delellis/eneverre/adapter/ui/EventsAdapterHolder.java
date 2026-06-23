package ar.com.delellis.eneverre.adapter.ui;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import ar.com.delellis.eneverre.R;

public class EventsAdapterHolder extends RecyclerView.ViewHolder {
    TextView typeView, timeView;

    public EventsAdapterHolder(@NonNull View itemView) {
        super(itemView);

        typeView = itemView.findViewById(R.id.event_type);
        timeView = itemView.findViewById(R.id.event_time);
    }

    public void setType(String type) {
        typeView.setText(type);
    }

    public void setTime(String time) {
        timeView.setText(time);
    }
}
