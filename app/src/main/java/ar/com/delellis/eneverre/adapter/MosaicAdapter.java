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

import ar.com.delellis.eneverre.R;
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

    public MosaicAdapter(Context context, Cameras cameras, OnCellClickListener listener) {
        this.context = context;
        this.cameras = cameras;
        this.listener = listener;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        this.recyclerView = recyclerView;
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
                    showError();
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

            privacyCover.setVisibility(GONE);
            statusIcon.setVisibility(GONE);
            progress.setVisibility(VISIBLE);
            failed = false;

            // The camera's shape is known from its metadata, so the cell fits
            // the picture from the first frame instead of from whenever the
            // decoder gets around to reporting its own geometry.
            video.setVideoAspect(camera.getWidth(), camera.getHeight());
            video.playUri(Uri.parse(camera.getRtsp()));
        }

        /** Tap on a failed cell: give this stream another go, in place. */
        void retry() {
            if (camera == null || camera.getPrivacy()) {
                return;
            }
            statusIcon.setVisibility(GONE);
            progress.setVisibility(VISIBLE);
            failed = false;

            video.setVideoAspect(camera.getWidth(), camera.getHeight());
            video.playUri(Uri.parse(camera.getRtsp()));
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

        private void showError() {
            failed = true;
            progress.setVisibility(GONE);
            // The stream failed — often just rotated RTSP credentials, in which
            // case retrying with this URL won't help and the full view (which
            // refetches a fresh one) is the way back.
            statusIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_refresh_24));
            statusIcon.setVisibility(VISIBLE);
        }
    }
}
