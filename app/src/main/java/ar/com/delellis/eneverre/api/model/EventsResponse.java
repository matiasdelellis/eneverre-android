package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;
import java.util.List;

public class EventsResponse implements Serializable {
    @Expose
    @SerializedName("events")
    private List<Event> events;

    @Expose
    @SerializedName("total")
    private int total;

    public List<Event> getEvents() {
        return events;
    }

    public int getTotal() {
        return total;
    }
}
