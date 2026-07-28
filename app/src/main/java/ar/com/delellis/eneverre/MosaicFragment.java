package ar.com.delellis.eneverre;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
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
 * Live mosaic of the cameras of a single {@link Location}: a grid where each
 * visible cell streams RTSP simultaneously (see {@link MosaicAdapter}). Tapping a
 * cell opens the full single-camera {@link ViewActivity}.
 *
 * One of these per location is paged by {@link MosaicActivity}. Streams follow
 * this fragment's <em>resumed</em> state, not just view attachment: the adapter
 * (and therefore every cell's player) is attached in {@link #onResume} and
 * detached in {@link #onPause}. Since the pager caps every non-current page to
 * {@code STARTED}, only the location on screen ever streams — and nothing
 * streams while the app is in the background.
 *
 * Two view modes, toggled from the toolbar of its {@link MosaicHost}:
 * <ul>
 *   <li><b>Scrolling grid</b> (default): 16:9 cells in the columns
 *       {@link #spanCountFor} picks for the orientation; the grid scrolls when
 *       the cameras don't fit, and only the on-screen ones stream.</li>
 *   <li><b>Fit to screen</b>: rows and columns chosen so <em>every</em> camera of
 *       the location lands on screen at once, each cell taking its share of the
 *       viewport height, over a black background. Nothing scrolls — and, by
 *       construction, every camera of the location streams at the same time.</li>
 * </ul>
 */
public class MosaicFragment extends Fragment
        implements MosaicAdapter.OnCellClickListener, MosaicHost.LayoutModeListener {

    private static final String ARG_LOCATION = "location";

    /** Cells look best near 16:9, the shape the cameras themselves stream. */
    private static final double TARGET_CELL_ASPECT = 16d / 9d;

    private Location location;
    private RecyclerView recyclerView;
    private GridLayoutManager layoutManager;
    private MosaicAdapter adapter;
    private boolean fitToScreen;

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
        // draws edge-to-edge on Android 15), matching CamerasActivity. It also
        // shrinks the viewport the fit-to-screen mode divides, so re-apply.
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
            attachWhenReady();
            return insets;
        });

        // The viewport is unknown until the grid is laid out, and changes on
        // rotation (which the host absorbs without recreating anything). This is
        // also what re-lays out the grid for the new orientation: re-applying the
        // mode on the configuration change instead would do it with stale
        // dimensions and restart every stream twice.
        recyclerView.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if ((r - l) != (oldR - oldL) || (b - t) != (oldB - oldT)) {
                attachWhenReady();
            }
        });

        LibVLC libVlc = host().getSharedLibVlc();
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
        // Pick up a mode toggled while this page was off screen, and take over as
        // the page the toolbar toggles from here on.
        fitToScreen = host().isMosaicFitToScreen();
        host().registerLayoutModeListener(this);
        attachWhenReady();
    }

    @Override
    public void onPause() {
        super.onPause();
        host().unregisterLayoutModeListener(this);
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
    public void onFitToScreenChanged(boolean fitToScreen) {
        this.fitToScreen = fitToScreen;
        applyLayoutMode();
    }

    /**
     * Lays the grid out for the current mode and, once that is possible, starts
     * streaming by attaching the adapter. Both modes size their cells from the
     * measured viewport, so a call before the first layout pass does nothing and
     * the layout listener calls back with the real dimensions.
     */
    private void attachWhenReady() {
        if (!isResumed() || adapter == null || recyclerView == null) {
            return;
        }
        if (!applyLayoutMode()) {
            return;
        }
        if (recyclerView.getAdapter() == null) {
            recyclerView.setAdapter(adapter);
        }
    }

    /**
     * Applies the columns and cell height of the current mode. Returns false if it
     * could not be applied yet — the viewport is not measured, or the grid is
     * mid-layout — in which case it retries itself.
     */
    private boolean applyLayoutMode() {
        if (recyclerView == null || layoutManager == null || adapter == null) {
            return false;
        }

        // Black behind the cells so the gutters, the slack left by the integer
        // division and any empty slot in the last row read as part of the wall
        // instead of the theme surface. The scrolling grid keeps the theme.
        if (fitToScreen) {
            recyclerView.setBackgroundColor(Color.BLACK);
        } else {
            recyclerView.setBackground(null);
        }

        if (recyclerView.isComputingLayout()) {
            // setCellHeight rebinds the cells, which a layout pass forbids.
            recyclerView.post(this::attachWhenReady);
            return false;
        }

        int count = cameraCount();
        int width = recyclerView.getWidth() - recyclerView.getPaddingLeft() - recyclerView.getPaddingRight();
        int height = recyclerView.getHeight() - recyclerView.getPaddingTop() - recyclerView.getPaddingBottom();
        if (count == 0 || width <= 0 || height <= 0) {
            return false;
        }

        int columns;
        int cellHeight;
        if (fitToScreen) {
            // Every camera on screen: each row takes an equal share of the height.
            columns = fitColumnsFor(count, width, height);
            int rows = (int) Math.ceil((double) count / columns);
            cellHeight = height / rows;
        } else {
            // Scrolling: cells keep the 16:9 shape of the stream, so the grid is as
            // tall as it needs to be.
            columns = spanCountFor(count);
            cellHeight = (int) Math.round((double) width / columns / TARGET_CELL_ASPECT);
        }

        layoutManager.setSpanCount(columns);
        adapter.setCellHeight(cellHeight);
        return true;
    }

    private int cameraCount() {
        return location == null ? 0 : location.getCameras().count();
    }

    /**
     * Grid columns for the scrolling mode: portrait stacks every camera full-width
     * in a single column; landscape prefers a 2x2 quad (two columns) for up to
     * four cameras and goes to three columns beyond that.
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

    /**
     * Columns for the fit-to-screen mode: of every layout that holds all the
     * cameras in the given viewport, the one whose cells come closest to 16:9,
     * preferring fewer empty slots between equally-shaped candidates. The
     * viewport's own shape decides, so this needs no orientation special case (a
     * landscape screen naturally lands on wider layouts than a portrait one).
     */
    private static int fitColumnsFor(int count, int width, int height) {
        int best = 1;
        double bestPenalty = Double.MAX_VALUE;
        for (int columns = 1; columns <= count; columns++) {
            int rows = (int) Math.ceil((double) count / columns);
            double aspect = ((double) width / columns) / ((double) height / rows);
            // Log-ratio: being twice too wide costs the same as twice too tall.
            double penalty = Math.abs(Math.log(aspect / TARGET_CELL_ASPECT))
                    + 0.05 * (columns * rows - count);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                best = columns;
            }
        }
        return best;
    }

    private MosaicHost host() {
        return (MosaicHost) requireActivity();
    }

    @Override
    public void onCellClick(int position) {
        Intent liveIntent = new Intent(requireContext(), ViewActivity.class);
        liveIntent.putExtra(CamerasActivity.LOCATION_CAMERAS_DATA, location);
        liveIntent.putExtra(CamerasActivity.SELECTED_CAMERA_DATA, position);
        startActivity(liveIntent);
    }
}
