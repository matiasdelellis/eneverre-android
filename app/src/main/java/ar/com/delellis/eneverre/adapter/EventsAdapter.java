package ar.com.delellis.eneverre.adapter;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.adapter.ui.EventsAdapterHolder;
import ar.com.delellis.eneverre.api.model.Event;
import ar.com.delellis.eneverre.util.Time;

public class EventsAdapter extends RecyclerView.Adapter<EventsAdapterHolder> {
    private final Context context;
    private final List<Event> events = new ArrayList<>();
    private final OnEventClickListener listener;

    private long highlightedEventId = -1L;

    private final SimpleDateFormat timeFormatter;

    public EventsAdapter(Context context, OnEventClickListener listener) {
        this.context = context;
        this.listener = listener;

        boolean is24h = DateFormat.is24HourFormat(context);
        String timePattern = is24h ? "HH:mm:ss" : "h:mm:ss a";
        timeFormatter = new SimpleDateFormat(timePattern, Locale.getDefault());
    }

    @NonNull
    @Override
    public EventsAdapterHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View view = inflater.inflate(R.layout.item_event, parent, false);
        return new EventsAdapterHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull EventsAdapterHolder holder, int position) {
        Event event = events.get(position);
        long startMsec = Time.RFC3339toMS(event.getStartTs());
        long endMsec = Time.RFC3339toMS(event.getEndTs());

        holder.setType(typeLabel(event.getType()));
        holder.setTime(friendlyTime(startMsec));
        holder.setDuration(friendlyDuration(startMsec, endMsec));
        holder.setHighlighted(event.getId() == highlightedEventId);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null && startMsec > 0) {
                listener.onEventClick(event, startMsec);
            }
        });
    }

    @Override
    public int getItemCount() {
        return events.size();
    }

    public void updateEvents(List<Event> newEvents) {
        events.clear();
        events.addAll(newEvents);
        notifyDataSetChanged();
    }

    /** Highlights the given event (or clears the highlight with -1). */
    public void setHighlightedEventId(long eventId) {
        if (highlightedEventId == eventId) {
            return;
        }
        highlightedEventId = eventId;
        notifyDataSetChanged();
    }

    private String typeLabel(String type) {
        if (type == null) {
            return context.getString(R.string.event_unknown);
        }
        if ("motion".equalsIgnoreCase(type)) {
            return context.getString(R.string.event_motion);
        }
        // Capitalize any other type reported by the backend.
        return type.substring(0, 1).toUpperCase(Locale.getDefault()) + type.substring(1);
    }

    private String friendlyTime(long startMsec) {
        if (startMsec <= 0) {
            return "";
        }

        Date date = new Date(startMsec);
        Date today = new Date(System.currentTimeMillis());
        Date yesterday = Time.dateAddDays(today, -1);

        String time = timeFormatter.format(date);
        if (Time.isSameDay(date, today)) {
            return context.getString(R.string.today) + ", " + time;
        } else if (Time.isSameDay(date, yesterday)) {
            return context.getString(R.string.yesterday) + ", " + time;
        }

        String day = DateFormat.getMediumDateFormat(context).format(date);
        return day + ", " + time;
    }

    /** Clock-style event length ("0:05", "1:23", "1:02:03"); empty if unknown. */
    private String friendlyDuration(long startMsec, long endMsec) {
        if (startMsec <= 0 || endMsec <= startMsec) {
            return "";
        }

        long totalSec = (endMsec - startMsec) / 1000;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }
}
