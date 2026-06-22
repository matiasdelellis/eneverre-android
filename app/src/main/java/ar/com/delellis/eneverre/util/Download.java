package ar.com.delellis.eneverre.util;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ar.com.delellis.eneverre.BuildConfig;
import ar.com.delellis.eneverre.R;
import okhttp3.ResponseBody;

/**
 * Saves snapshots / clips to the public {@code Downloads/Eneverre} folder and
 * shares them.
 *
 * <p>All saving runs off the main thread and returns a {@code content://} URI
 * that is safe to hand to other apps:
 * <ul>
 *   <li>API 29+: written through {@link MediaStore} (no storage permission
 *       needed; the inserted item URI is directly shareable).</li>
 *   <li>API 24-28: written with the {@link File} API and exposed through
 *       {@link FileProvider} (cleartext {@code file://} URIs would crash on
 *       Android 7+).</li>
 * </ul>
 */
public class Download {

    private static final String SUBDIR = "Eneverre";
    private static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider";

    /** Snapshot/clip saving is serialized on this single background thread. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /** Writes file content to the given stream. The stream is closed by the caller. */
    private interface ContentWriter {
        void writeTo(OutputStream out) throws IOException;
    }

    public static String buildFileName(String prefix, String dateTime, String extension) {
        return prefix + "_" + dateTime + "." + extension;
    }

    /**
     * Saves a snapshot bitmap off the main thread and, on success, shows the
     * share preview. Failures surface as a toast. Safe to call from any thread.
     */
    public static void saveSnapshotAndShare(@NonNull Activity activity, @NonNull Bitmap bitmap,
                                            @NonNull String fileName, @NonNull String cameraName) {
        IO.execute(() -> {
            try {
                Uri uri = save(activity, fileName, "image/png",
                        out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
                postPreview(activity, uri, "image/png", cameraName, bitmap, -1);
            } catch (IOException e) {
                postError(activity, R.string.error_snapshot);
            }
        });
    }

    /**
     * Streams a clip response body to storage off the main thread (so large
     * files neither block the UI nor load fully into memory) and, on success,
     * shows the share preview.
     */
    public static void saveClipAndShare(@NonNull Activity activity, @NonNull ResponseBody body,
                                        @NonNull String fileName, @NonNull String cameraName,
                                        Bitmap preview, long durationSeconds) {
        IO.execute(() -> {
            try (ResponseBody closeable = body; InputStream in = closeable.byteStream()) {
                Uri uri = save(activity, fileName, "video/mp4", out -> copy(in, out));
                postPreview(activity, uri, "video/mp4", cameraName, preview, durationSeconds);
            } catch (IOException e) {
                postError(activity, R.string.error_download);
            }
        });
    }

    public static void share(Context context, Uri uri, String title, String mimetype) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(mimetype);
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.putExtra(Intent.EXTRA_TITLE, title);
        shareIntent.setClipData(ClipData.newUri(context.getContentResolver(), title, uri));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent chooser = Intent.createChooser(shareIntent, title);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(chooser);
    }

    public static void open(Context context, Uri uri, String mimetype) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimetype);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    // --- saving -------------------------------------------------------------

    private static Uri save(Context context, String fileName, String mimeType, ContentWriter writer)
            throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(context, fileName, mimeType, writer);
        }
        return saveViaFile(context, fileName, writer);
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private static Uri saveViaMediaStore(Context context, String fileName, String mimeType,
                                         ContentWriter writer) throws IOException {
        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (item == null) {
            throw new IOException("MediaStore returned no URI for " + fileName);
        }

        try {
            try (OutputStream out = resolver.openOutputStream(item)) {
                if (out == null) {
                    throw new IOException("Could not open output stream for " + item);
                }
                writer.writeTo(out);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(item, values, null, null);
            return item;
        } catch (IOException e) {
            // Don't leave a half-written, still-pending entry behind.
            resolver.delete(item, null, null);
            throw e;
        }
    }

    @SuppressWarnings("deprecation") // getExternalStoragePublicDirectory: only used pre-scoped-storage (API < 29)
    private static Uri saveViaFile(Context context, String fileName, ContentWriter writer)
            throws IOException {
        File folder = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR);
        folder.mkdirs();

        File file = new File(folder, fileName);
        try (OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(file))) {
            writer.writeTo(out);
        }
        MediaScannerConnection.scanFile(context, new String[]{file.getAbsolutePath()}, null, null);
        return FileProvider.getUriForFile(context, AUTHORITY, file);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    // --- UI callbacks (posted to the main thread) ---------------------------

    private static void postPreview(Activity activity, Uri uri, String mimetype,
                                    String cameraName, Bitmap preview, long duration) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            SharePreviewDialog.show(activity, uri, mimetype, cameraName, preview, duration);
        });
    }

    private static void postError(Activity activity, @StringRes int messageRes) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            Toast.makeText(activity, messageRes, Toast.LENGTH_LONG).show();
        });
    }
}
