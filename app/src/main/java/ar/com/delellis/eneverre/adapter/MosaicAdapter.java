package ar.com.delellis.eneverre.adapter;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.api.CameraRefresher;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.model.Cameras;
import ar.com.delellis.eneverre.player.RtspPlayerView;
import ar.com.delellis.eneverre.util.VideoTouchListener;

/**
 * Backs the live camera mosaic. Every cell is its own {@link RtspPlayerView},
 * i.e. an independent RTSP session and decoder. Streams are tied to view
 * attachment: a cell starts playing when it scrolls on screen
 * ({@link #onViewAttachedToWindow}) and is torn down when it scrolls off
 * ({@link #onViewDetachedFromWindow}), so only the visible cells ever stream and
 * the count of concurrent RTSP sessions stays bounded by what fits on screen.
 *
 * No cell pulls audio at all: several live audio tracks at once would be
 * unusable, and audio belongs to the full single-camera view opened on tap.
 *
 * Each cell zooms on its own: a pinch (and a double tap) magnifies that
 * camera's picture in place, and a drag pans it while zoomed. The zoom is a
 * property of the cell on screen, not of the camera — it is dropped when the
 * cell is rebound or stops streaming.
 *
 * <p>Live URLs carry a per-camera signed path token valid for six hours, so the
 * whole wall shares one expiry. The adapter therefore owns a single refresh
 * path for it: at most one automatic {@code GET /api/cameras} per attached
 * session, coalesced by {@link CameraRefresher}, after which every cell gets the
 * fresh URL in place. Without that, four failing cells would each ask for the
 * same list, and a cell retrying its own stale URL could never recover.
 */
public class MosaicAdapter extends RecyclerView.Adapter<MosaicAdapter.CellHolder> {

    /** Tap on a cell: open the full single-camera view at this position. */
    public interface OnCellClickListener {
        void onCellClick(int position);
    }

    private final Context context;
    private final Cameras cameras;
    private final OnCellClickListener listener;

    /** Height every cell gets, in px; the owner computes it per view mode. */
    private int cellHeight = 0;

    /** Set while attached, to resize the cells already on screen in place. */
    private RecyclerView recyclerView = null;

    /** A camera-list refresh is in flight: cells wait for it instead of connecting. */
    private boolean refreshing = false;

    /**
     * Whether this attached session already spent its one automatic refresh.
     * Reset when the adapter is attached (each resume), so a token that expired
     * while the app was in the background gets one shot — and a wall of failing
     * cells never exceeds one request.
     */
    private boolean autoRefreshDone = false;

    /** Delivers the refreshed list back to this adapter (see {@link CameraRefresher}). */
    private final CameraRefresher.Listener refreshListener = this::onCamerasRefreshed;

    public MosaicAdapter(Context context, Cameras cameras, OnCellClickListener listener) {
        this.context = context;
        this.cameras = cameras;
        this.listener = listener;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        this.recyclerView = recyclerView;
        // A new session: the URL may have aged out while the app was paused.
        autoRefreshDone = false;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        this.recyclerView = null;
    }

    /**
     * Pins every cell to {@code heightPx}: cells have no intrinsic height, so the
     * owner sizes them for the current view mode (16:9 of the column width for the
     * scrolling grid, a share of the viewport for the grid that fits the screen)
     * before attaching this adapter, and again whenever the mode or the viewport
     * changes.
     *
     * The cells on screen are resized <b>in place</b> — deliberately not through
     * {@code notifyDataSetChanged()}, which recycles every holder and would tear
     * every RTSP session down only to reconnect it at the new size. Switching
     * view mode (and rotating) is a resize, not new content: the streams keep
     * running and only cells bound later need the height from the field.
     */
    public void setCellHeight(int heightPx) {
        if (cellHeight == heightPx) {
            return;
        }
        cellHeight = heightPx;

        // Cells not attached right now pick this up when they attach (see
        // onViewAttachedToWindow); these are the ones already on screen.
        if (recyclerView == null) {
            return;
        }
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            RecyclerView.ViewHolder holder =
                    recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
            if (holder instanceof CellHolder) {
                ((CellHolder) holder).applyCellHeight(heightPx);
            }
        }
    }

    @NonNull
    @Override
    public CellHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_mosaic_cell, parent, false);
        return new CellHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CellHolder holder, int position) {
        holder.applyCellHeight(cellHeight);
        holder.bind(cameras.get(position));
    }

    @Override
    public void onViewAttachedToWindow(@NonNull CellHolder holder) {
        // A cell can come back from the recycler's scrap without being re-bound,
        // so this — not onBindViewHolder alone — is what guarantees it carries
        // the height of the current view mode. A cell that was detached while
        // the mode changed would otherwise keep the previous one for good.
        // The layout manager measures the child right after attaching it, so
        // setting it here still lands in this pass.
        holder.applyCellHeight(cellHeight);
        holder.start();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull CellHolder holder) {
        holder.stop();
    }

    @Override
    public void onViewRecycled(@NonNull CellHolder holder) {
        // Detach usually stops the stream first, but recycling without a prior
        // detach (e.g. adapter reset) must not leak a running player either.
        holder.stop();
    }

    @Override
    public int getItemCount() {
        return cameras == null ? 0 : cameras.count();
    }

    /**
     * Asks for fresh camera URLs, collapsing into the request already in flight
     * instead of issuing another. Used both proactively — a cell is about to
     * connect with a token that is missing or about to expire — and when a cell
     * reports that its token was rejected.
     */
    private void requestRefresh() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        CameraRefresher.refresh(refreshListener);
    }

    /**
     * Applies the refreshed list in place and restarts whatever is not playing:
     * the cells that failed (or were waiting for the refresh) reconnect with new
     * URLs, while the ones already streaming are left untouched.
     */
    private void onCamerasRefreshed(List<Camera> fresh) {
        refreshing = false;
        // Apply the URLs even when detached, so the next attach reads them; but
        // never touch the holders then.
        if (fresh != null && cameras != null) {
            applyFreshUrls(fresh);
        }
        if (recyclerView != null) {
            restartVisibleCells();
        }
    }

    /** Copies every camera's fresh RTSP URL (new signed path token) in place. */
    private void applyFreshUrls(List<Camera> fresh) {
        for (int i = 0; i < cameras.count(); i++) {
            CameraRefresher.applyRtsp(cameras.get(i), fresh);
        }
    }

    /** Restarts every cell on screen that is not already streaming. */
    private void restartVisibleCells() {
        if (recyclerView == null) {
            return;
        }
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            RecyclerView.ViewHolder holder =
                    recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
            if (holder instanceof CellHolder) {
                ((CellHolder) holder).start();
            }
        }
    }

    class CellHolder extends RecyclerView.ViewHolder {
        private final View videoFrame;
        private final RtspPlayerView video;
        private final VideoTouchListener touchListener = new VideoTouchListener();
        private final View privacyCover;
        private final ProgressBar progress;
        private final ImageView statusIcon;
        private final TextView name;

        private Camera camera;
        /** Whether this cell is showing the "stream is gone" state. */
        private boolean failed = false;
        /** Whether the last failure was the camera rejecting the URL's token. */
        private boolean rejected = false;

        CellHolder(@NonNull View itemView) {
            super(itemView);
            videoFrame = itemView.findViewById(R.id.cell_video_frame);
            video = itemView.findViewById(R.id.cell_video);
            privacyCover = itemView.findViewById(R.id.cell_privacy_cover);
            progress = itemView.findViewById(R.id.cell_progress);
            statusIcon = itemView.findViewById(R.id.cell_status_icon);
            name = itemView.findViewById(R.id.cell_name);

            // Video only: the full single-camera view owns audio (see class javadoc).
            video.setAudioEnabled(false);
            video.setListener(new RtspPlayerView.Listener() {
                @Override
                public void onConnecting() {
                    progress.setVisibility(VISIBLE);
                }

                @Override
                public void onPlaying() {
                    // A frame is on screen, so whatever this cell was showing
                    // before — spinner or failure — is over.
                    progress.setVisibility(GONE);
                    statusIcon.setVisibility(GONE);
                    failed = false;
                }

                @Override
                public void onError(boolean unauthorized) {
                    showError(unauthorized);
                }
            });

            videoFrame.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onCellClick(pos);
                }
            });

            // Pinch/double-tap zooms this cell's picture — the player's
            // content, not the cell, so the magnified picture stays inside the
            // cell and off its neighbours — and a drag pans it while zoomed.
            // The listener swallows the gesture, so the tap that opens the full
            // view is routed back through the click listener above. Unzoomed it
            // leaves the drag to the grid, so scrolling still works.
            touchListener.setTransformTarget(video);
            // A cell that failed offers a retry, so that is what a tap does
            // there; anywhere else the tap opens the full single-camera view.
            touchListener.setOnTapListener(() -> {
                if (failed) {
                    retry();
                } else {
                    videoFrame.performClick();
                }
            });
            videoFrame.setOnTouchListener(touchListener);
        }

        /** Gives the cell the height the current view mode asked for. */
        void applyCellHeight(int heightPx) {
            ViewGroup.LayoutParams cell = itemView.getLayoutParams();
            int height = heightPx > 0 ? heightPx : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (cell.height != height) {
                cell.height = height;
                itemView.setLayoutParams(cell);
            }
        }

        void bind(Camera camera) {
            this.camera = camera;
            name.setText(camera.getName());
            // A recycled cell must not show the previous camera's zoom, nor
            // its failure.
            failed = false;
            rejected = false;
            touchListener.reset();
        }

        /** Starts (or restarts) this cell's stream, unless the camera is in privacy mode. */
        void start() {
            if (camera == null || video.isPlaying()) {
                return;
            }
            if (camera.getPrivacy()) {
                showPrivacy();
                return;
            }
            if (refreshing) {
                // A refresh is on its way: connecting now would use the URL it
                // is about to replace (and fail with a 401 if it is expired).
                progress.setVisibility(VISIBLE);
                return;
            }

            String url = camera.getRtsp();
            if (!autoRefreshDone && CameraRefresher.shouldRefreshBeforeStart(url)) {
                // The whole wall shares one token lifetime, so a single refresh
                // is spent here; every cell is restarted when it lands.
                autoRefreshDone = true;
                progress.setVisibility(VISIBLE);
                statusIcon.setVisibility(GONE);
                failed = false;
                requestRefresh();
                return;
            }
            if (url == null || url.isEmpty()) {
                // No live feed advertised for this camera (relay disabled,
                // privacy on, or outside its recording schedule).
                showError(false);
                return;
            }

            privacyCover.setVisibility(GONE);
            statusIcon.setVisibility(GONE);
            progress.setVisibility(VISIBLE);
            failed = false;
            rejected = false;

            // The camera's shape is known from its metadata, so the cell fits
            // the picture from the first frame instead of from whenever the
            // decoder gets around to reporting its own geometry.
            video.setVideoAspect(camera.getWidth(), camera.getHeight());
            video.playUri(Uri.parse(url));
        }

        /** Tap on a failed cell: give this stream another go, in place. */
        void retry() {
            if (camera == null || camera.getPrivacy()) {
                return;
            }
            statusIcon.setVisibility(GONE);
            progress.setVisibility(VISIBLE);
            failed = false;

            // Retrying the same URL cannot fix an expired or rejected token: ask
            // for fresh ones first. This is a user action, so it is not limited
            // by the session's automatic-refresh cap.
            if (rejected || CameraRefresher.shouldRefreshBeforeStart(camera.getRtsp())) {
                autoRefreshDone = true;
                requestRefresh();
                return;
            }
            start();
        }

        void stop() {
            video.stopPlayback();
            touchListener.reset();
            progress.setVisibility(GONE);
        }

        private void showPrivacy() {
            progress.setVisibility(GONE);
            privacyCover.setVisibility(VISIBLE);
            statusIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_privacy_24));
            statusIcon.setVisibility(VISIBLE);
        }

        private void showError(boolean unauthorized) {
            failed = true;
            rejected = unauthorized;
            progress.setVisibility(GONE);
            statusIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_refresh_24));
            statusIcon.setVisibility(VISIBLE);

            // The signed path token is short-lived, so a failure is often just
            // an expired one. Refresh the list once per session so the whole
            // wall recovers with a single request instead of one per cell; a
            // cell that fails again after that is a real outage and keeps the
            // retry icon. Tapping it is what retries with a fresh URL.
            if (!autoRefreshDone) {
                autoRefreshDone = true;
                requestRefresh();
            }
        }
    }
}
