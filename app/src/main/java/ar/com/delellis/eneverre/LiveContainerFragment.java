package ar.com.delellis.eneverre;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import ar.com.delellis.eneverre.adapter.CameraPagerAdapter;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.model.Location;

/**
 * Hosts the {@link ViewPager2} that swipes between the live cameras of a
 * {@link Location}. Lives as the "Live" tab of {@link ViewActivity}; the host
 * caps this fragment's lifecycle so the underlying live players start and stop
 * automatically when the tab is shown or hidden.
 */
public class LiveContainerFragment extends Fragment {

    private static final String ARG_LOCATION = "location";
    private static final String ARG_SELECTED = "selected";

    private ViewPager2 viewPager;
    private Location location;
    private OnCameraChangeListener cameraListener;

    public static LiveContainerFragment newInstance(Location location, int selected) {
        LiveContainerFragment fragment = new LiveContainerFragment();
        Bundle args = new Bundle();
        args.putSerializable(ARG_LOCATION, location);
        args.putInt(ARG_SELECTED, selected);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnCameraChangeListener) {
            cameraListener = (OnCameraChangeListener) context;
        } else {
            throw new RuntimeException("Host must implement OnCameraChangeListener");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera_pager, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        location = (Location) requireArguments().getSerializable(ARG_LOCATION);
        int selected = requireArguments().getInt(ARG_SELECTED, 0);

        viewPager = view.findViewById(R.id.viewPager);
        viewPager.setOffscreenPageLimit(1);
        viewPager.setAdapter(new CameraPagerAdapter(this, location));

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                notifyCameraChanged(position);
            }
        });

        viewPager.setCurrentItem(selected, false);
        // Make sure the host learns the initial camera even when the selected
        // position is 0 (onPageSelected is not guaranteed to fire for it).
        viewPager.post(() -> notifyCameraChanged(viewPager.getCurrentItem()));
    }

    public int getCurrentItem() {
        return viewPager != null ? viewPager.getCurrentItem() : 0;
    }

    /** Keeps the pager in sync with the position selected in the other tab. */
    public void setCurrentItem(int position) {
        if (viewPager != null && viewPager.getCurrentItem() != position) {
            viewPager.setCurrentItem(position, false);
        }
    }

    private void notifyCameraChanged(int position) {
        if (cameraListener != null) {
            cameraListener.onCameraChanged(location.getCameras().get(position), position);
        }
    }
}
