package ar.com.delellis.eneverre.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import ar.com.delellis.eneverre.LiveViewFragment;
import ar.com.delellis.eneverre.model.Location;

public class CameraPagerAdapter extends FragmentStateAdapter {

    private final Location location;

    public CameraPagerAdapter(@NonNull FragmentActivity fragmentActivity, Location cameraList) {
        super(fragmentActivity);
        this.location = cameraList;
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return LiveViewFragment.newInstance(location.getCameras().get(position));
    }

    @Override
    public int getItemCount() {
        return location.getCameras().count();
    }
}