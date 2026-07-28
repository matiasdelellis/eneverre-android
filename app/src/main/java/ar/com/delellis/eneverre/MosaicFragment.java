package ar.com.delellis.eneverre;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.videolan.libvlc.LibVLC;

import ar.com.delellis.eneverre.adapter.MosaicAdapter;
import ar.com.delellis.eneverre.model.Location;

/**
 * Live mosaic of the cameras of a single {@link Location}: a scrolling grid where
 * each visible cell streams RTSP simultaneously (see {@link MosaicAdapter}).
 * Tapping a cell opens the full single-camera {@link ViewActivity}.
 *
 * One of these per location is paged by {@link MosaicActivity}. Streams follow
 * this fragment's <em>resumed</em> state, not just view attachment: the adapter
 * (and therefore every cell's player) is attached in {@link #onResume} and
 * detached in {@link #onPause}. Since the pager caps every non-current page to
 * {@code STARTED}, only the location on screen ever streams — and nothing
 * streams while the app is in the background.
 */
public class MosaicFragment extends Fragment implements MosaicAdapter.OnCellClickListener {

    /**
     * Host owning the single shared {@link LibVLC} every page's grid runs on (one
     * native engine for the whole activity, not one per location).
     */
    public interface SharedLibVlcProvider {
        LibVLC getSharedLibVlc();
    }

    private static final String ARG_LOCATION = "location";

    private Location location;
    private RecyclerView recyclerView;
    private GridLayoutManager layoutManager;
    private MosaicAdapter adapter;

    public static MosaicFragment newInstance(Location location) {
        MosaicFragment fragment = new MosaicFragment();
        Bundle args = new Bundle();
        args.putSerializable(ARG_LOCATION, location);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mosaic, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        location = (Location) requireArguments().getSerializable(ARG_LOCATION);
        recyclerView = (RecyclerView) view;

        layoutManager = new GridLayoutManager(requireContext(), spanCountFor(cameraCount()));
        // Don't prefetch offscreen cells: prefetching would attach (and start
        // streaming) cells before they are visible, defeating the on-screen cap.
        layoutManager.setItemPrefetchEnabled(false);
        recyclerView.setLayoutManager(layoutManager);

        // Reserve room below the last row for the gesture/navigation bar (the app
        // draws edge-to-edge on Android 15), matching CamerasActivity.
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
            return insets;
        });

        LibVLC libVlc = ((SharedLibVlcProvider) requireActivity()).getSharedLibVlc();
        if (libVlc == null) {
            // Host is finishing (e.g. bouncing through the splash after process
            // death) and never created the engine; leave the grid empty.
            return;
        }
        adapter = new MosaicAdapter(requireContext(), libVlc, location.getCameras(), this);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Attaching the adapter starts the visible cells' streams; only the page
        // the pager resumed gets here, so offscreen locations stay silent.
        if (adapter != null && recyclerView.getAdapter() == null) {
            recyclerView.setAdapter(adapter);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        // Detaching every cell (via setAdapter(null)) stops all this location's
        // streams as soon as it is swiped away or the app leaves the foreground.
        if (recyclerView != null) {
            recyclerView.setAdapter(null);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        recyclerView = null;
        layoutManager = null;
        adapter = null;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The host declares configChanges for orientation, so nothing is
        // recreated on rotation: re-lay out the grid for the new orientation.
        if (layoutManager != null) {
            layoutManager.setSpanCount(spanCountFor(cameraCount()));
        }
    }

    private int cameraCount() {
        return location == null ? 0 : location.getCameras().count();
    }

    /**
     * Grid columns for the given camera count and orientation: portrait stacks
     * every camera full-width in a single column; landscape prefers a 2x2 quad
     * (two columns) for up to four cameras and goes to three columns beyond that.
     */
    private int spanCountFor(int cameraCount) {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (!landscape) {
            return 1;
        }
        if (cameraCount <= 1) {
            return 1;
        }
        return cameraCount < 5 ? 2 : 3;
    }

    @Override
    public void onCellClick(int position) {
        Intent liveIntent = new Intent(requireContext(), ViewActivity.class);
        liveIntent.putExtra(CamerasActivity.LOCATION_CAMERAS_DATA, location);
        liveIntent.putExtra(CamerasActivity.SELECTED_CAMERA_DATA, position);
        startActivity(liveIntent);
    }
}
