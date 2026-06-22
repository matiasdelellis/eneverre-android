package ar.com.delellis.eneverre.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import ar.com.delellis.eneverre.PlaybackFragment;
import ar.com.delellis.eneverre.model.Location;

public class PlaybackPagerAdapter extends FragmentStateAdapter {

    private final Location location;

    public PlaybackPagerAdapter(@NonNull Fragment fragment, Location location) {
        super(fragment);
        this.location = location;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return PlaybackFragment.newInstance(location.getCameras().get(position));
    }

    @Override
    public int getItemCount() {
        return location.getCameras().count();
    }
}
