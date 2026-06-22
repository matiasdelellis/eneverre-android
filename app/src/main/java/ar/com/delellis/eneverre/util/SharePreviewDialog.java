package ar.com.delellis.eneverre.util;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;

import ar.com.delellis.eneverre.R;

public class SharePreviewDialog {
    public static void show(
            @NonNull Context context,
            @NonNull Uri sourceUri,
            @NonNull String mimetype,
            @NonNull String cameraName,
            Bitmap preview,
            long duration
    ) {

        BottomSheetDialog dialog = new BottomSheetDialog(context);

        View view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_share_preview, null, false);

        if (preview != null) {
            ImageView imagePreview = view.findViewById(R.id.imagePreview);
            imagePreview.setImageBitmap(preview);
        }

        MaterialButton btnShare = view.findViewById(R.id.btnShare);
        MaterialButton btnOpen = view.findViewById(R.id.btnOpen);
        btnShare.setOnClickListener(v -> {
            Download.share(context, sourceUri, cameraName, mimetype);
            dialog.dismiss();
        });

        btnOpen.setOnClickListener(v -> {
            Download.open(context, sourceUri, mimetype);
            dialog.dismiss();
        });

        dialog.setContentView(view);

        boolean isVideo = mimetype.startsWith("video/");
        view.findViewById(R.id.imageVideoOverlay).setVisibility(isVideo ? VISIBLE : GONE);
        view.findViewById(R.id.textOverlayDuration).setVisibility(isVideo ? VISIBLE : GONE);
        if (duration > 0) {
            TextView txtDuration = view.findViewById(R.id.textOverlayDuration);
            txtDuration.setText(formatDuration(duration));
        }

        dialog.show();

        View bottomSheet = dialog.findViewById(
                com.google.android.material.R.id.design_bottom_sheet
        );
        if (bottomSheet != null) {
            bottomSheet.setClipToOutline(true);
        }
    }

    private static String formatDuration(long seconds) {
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;

        return String.format(
                java.util.Locale.getDefault(),
                "%02d:%02d",
                minutes,
                remainingSeconds
        );
    }}