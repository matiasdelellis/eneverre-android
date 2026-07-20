package ar.com.delellis.eneverre.adapter;

import ar.com.delellis.eneverre.model.Location;

/** Clicks on a location as a whole (e.g. opening its live camera mosaic). */
public interface OnLocationClickListener {
    void onMosaicClick(Location location);
}
