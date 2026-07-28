package ar.com.delellis.eneverre.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import ar.com.delellis.eneverre.MosaicFragment;
import ar.com.delellis.eneverre.model.Locations;

/**
 * One {@link MosaicFragment} per location, so the mosaic can be swiped from one
 * location's camera grid to the next.
 */
public class MosaicPagerAdapter extends FragmentStateAdapter {

    private final Locations locations;

    public MosaicPagerAdapter(@NonNull FragmentActivity activity, Locations locations) {
        super(activity);
        this.locations = locations;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return MosaicFragment.newInstance(locations.get(position));
    }

    @Override
    public int getItemCount() {
        return locations == null ? 0 : locations.count();
    }
}
