package ar.com.delellis.eneverre.util;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;

public class Snapshot {
    /**
     * Copies the current frame of {@code surfaceView} into a bitmap. The
     * {@link PixelCopy} request runs on a throwaway background thread, but the
     * listener is always invoked on the main thread so callers can safely touch
     * the UI.
     */
    public static void getSurfaceBitmap(SurfaceView surfaceView, final PixelCopyListener listener) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        int width = surfaceView.getWidth();
        int height = surfaceView.getHeight();
        if (width <= 0 || height <= 0) {
            mainHandler.post(() -> listener.onSurfaceBitmapError(PixelCopy.ERROR_SOURCE_NO_DATA));
            return;
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        HandlerThread handlerThread = new HandlerThread(Snapshot.class.getSimpleName());
        handlerThread.start();

        PixelCopy.request(surfaceView, bitmap, copyResult -> {
            handlerThread.quitSafely();
            mainHandler.post(() -> {
                if (copyResult == PixelCopy.SUCCESS) {
                    listener.onSurfaceBitmapReady(bitmap);
                } else {
                    listener.onSurfaceBitmapError(copyResult);
                }
            });
        }, new Handler(handlerThread.getLooper()));
    }

    public interface PixelCopyListener {
        void onSurfaceBitmapReady(Bitmap bitmap);

        void onSurfaceBitmapError(int errorCode);
    }
}
