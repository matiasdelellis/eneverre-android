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

import ar.com.delellis.eneverre.adapter.PlaybackPagerAdapter;
import ar.com.delellis.eneverre.model.Location;

/**
 * Hosts the {@link ViewPager2} that swipes between the cameras of a
 * {@link Location} for recording playback. Lives as the "Playback" tab of
 * {@link ViewActivity}; the host caps its lifecycle so the playback players of
 * non-visible pages do not keep streaming.
 */
public class PlaybackContainerFragment extends Fragment implements PlaybackTimeHost {

    private static final String ARG_LOCATION = "location";
    private static final String ARG_SELECTED = "selected";

    private ViewPager2 viewPager;
    private Location location;
    private OnCameraChangeListener cameraListener;

    /** Shared across pages so a newly selected camera resumes at the same time. */
    private long sharedTimeMs = 0;

    @Override
    public long getSharedPlaybackTime() {
        return sharedTimeMs;
    }

    @Override
    public void setSharedPlaybackTime(long timeMs) {
        sharedTimeMs = timeMs;
    }

    public static PlaybackContainerFragment newInstance(Location location, int selected) {
        PlaybackContainerFragment fragment = new PlaybackContainerFragment();
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
        viewPager.setAdapter(new PlaybackPagerAdapter(this, location));

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                notifyCameraChanged(position);
            }
        });

        viewPager.setCurrentItem(selected, false);
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
