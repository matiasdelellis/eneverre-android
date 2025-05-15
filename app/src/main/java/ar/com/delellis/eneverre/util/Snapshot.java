package ar.com.delellis.eneverre.util;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.PixelCopy;
import android.view.SurfaceView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Snapshot {
    public static File getSnapshotFile(String cameraName) {
        File snapshotFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES + "/Screenshots");
        snapshotFolder.mkdirs();

        String currentDate = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "eneverre_" + cameraName + "_" + currentDate + ".png";

        return new File(snapshotFolder.getPath() + "/" + fileName);
    }

    public static void getSurfaceBitmap(SurfaceView surfaceView, final PixelCopyListener listener) {
        Bitmap bitmap = Bitmap.createBitmap(surfaceView.getWidth(), surfaceView.getHeight(), Bitmap.Config.ARGB_8888);

        HandlerThread handlerThread = new HandlerThread(Snapshot.class.getSimpleName());
        handlerThread.start();

        PixelCopy.request(surfaceView, bitmap, new PixelCopy.OnPixelCopyFinishedListener() {
            @Override
            public void onPixelCopyFinished(int copyResult) {
                if (copyResult == PixelCopy.SUCCESS) {
                    listener.onSurfaceBitmapReady(bitmap);
                } else {
                    listener.onSurfaceBitmapError("Couldn't create bitmap of the SurfaceView: " + String.valueOf(copyResult));
                }
                handlerThread.quitSafely();
            }
        }, new Handler(handlerThread.getLooper()));
    }

    public static void shareImage(Context context, Uri uri, String title) {
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.setType("image/png");
        context.startActivity(Intent.createChooser(shareIntent, title));
    }

    public interface PixelCopyListener {
        void onSurfaceBitmapReady(Bitmap bitmap);

        void onSurfaceBitmapError(String errorMsg);
    }
}