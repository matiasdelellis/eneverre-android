package ar.com.delellis.eneverre.adapter;

import ar.com.delellis.eneverre.api.model.Event;

public interface OnEventClickListener {
    void onEventClick(Event event, long startMsec);
}
