package ar.com.delellis.eneverre.adapter;

import android.view.View;

import ar.com.delellis.eneverre.api.model.Event;

public interface OnEventClickListener {
    void onEventClick(Event event, long startMsec);

    /** Long press on an event row; {@code anchor} is the row, to anchor a menu. */
    void onEventLongClick(View anchor, Event event, long startMsec);
}
