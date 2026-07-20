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

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.model.Cameras;
import ar.com.delellis.eneverre.player.VlcPlayer;

/**
 * Backs the live camera mosaic. Every cell runs its own {@link VlcPlayer} over a
 * single shared {@link LibVLC} (one native engine for the whole grid). Streams
 * are tied to view attachment: a cell starts playing when it scrolls on screen
 * ({@link #onViewAttachedToWindow}) and is torn down when it scrolls off
 * ({@link #onViewDetachedFromWindow}), so only the visible cells ever stream and
 * the count of concurrent RTSP sessions stays bounded by what fits on screen.
 *
 * Every cell is muted: several live audio tracks at once would be unusable, and
 * audio belongs to the full single-camera view opened on tap.
 */
public class MosaicAdapter extends RecyclerView.Adapter<MosaicAdapter.CellHolder> {

    /** Tap on a cell: open the full single-camera view at this position. */
    public interface OnCellClickListener {
        void onCellClick(int position);
    }

    private final Context context;
    private final LibVLC libVlc;
    private final Cameras cameras;
    private final OnCellClickListener listener;

    public MosaicAdapter(Context context, LibVLC libVlc, Cameras cameras, OnCellClickListener listener) {
        this.context = context;
        this.libVlc = libVlc;
        this.cameras = cameras;
        this.listener = listener;
    }

    @NonNull
    @Override
    public CellHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_mosaic_cell, parent, false);
        return new CellHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CellHolder holder, int position) {
        holder.bind(cameras.get(position));
    }

    @Override
    public void onViewAttachedToWindow(@NonNull CellHolder holder) {
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
        private final VLCVideoLayout video;
        private final View privacyCover;
        private final ProgressBar progress;
        private final ImageView statusIcon;
        private final TextView name;

        private Camera camera;
        private VlcPlayer player;

        CellHolder(@NonNull View itemView) {
            super(itemView);
            video = itemView.findViewById(R.id.cell_video);
            privacyCover = itemView.findViewById(R.id.cell_privacy_cover);
            progress = itemView.findViewById(R.id.cell_progress);
            statusIcon = itemView.findViewById(R.id.cell_status_icon);
            name = itemView.findViewById(R.id.cell_name);

            itemView.findViewById(R.id.cell_video_frame).setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onCellClick(pos);
                }
            });
        }

        void bind(Camera camera) {
            this.camera = camera;
            name.setText(camera.getName());
        }

        /** Starts (or restarts) this cell's stream, unless the camera is in privacy mode. */
        void start() {
            if (camera == null || player != null) {
                return;
            }
            if (camera.getPrivacy()) {
                showPrivacy();
                return;
            }

            privacyCover.setVisibility(GONE);
            statusIcon.setVisibility(GONE);
            progress.setVisibility(VISIBLE);

            player = new VlcPlayer(libVlc);
            player.setEventListener(event -> {
                if (event.type == MediaPlayer.Event.Buffering && event.getBuffering() == 100f) {
                    progress.setVisibility(GONE);
                } else if (event.type == MediaPlayer.Event.EncounteredError) {
                    showError();
                }
            });
            player.attachView(video);
            // Muted: the full single-camera view owns audio (see class javadoc).
            player.mute(true);
            player.playUri(Uri.parse(camera.getRtsp()));
        }

        void stop() {
            if (player != null) {
                player.stop();
                player.detachViews();
                player.release();
                player = null;
            }
            progress.setVisibility(GONE);
        }

        private void showPrivacy() {
            progress.setVisibility(GONE);
            privacyCover.setVisibility(VISIBLE);
            statusIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_privacy_24));
            statusIcon.setVisibility(VISIBLE);
        }

        private void showError() {
            progress.setVisibility(GONE);
            // The stream failed (often just rotated RTSP credentials); a tap opens
            // the full view, which refetches a fresh URL and recovers on its own.
            statusIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_refresh_24));
            statusIcon.setVisibility(VISIBLE);
        }
    }
}
