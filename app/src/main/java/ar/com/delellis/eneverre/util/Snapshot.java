package ar.com.delellis.eneverre.util;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.PixelCopy;
import android.view.SurfaceView;

public class Snapshot {
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
                    listener.onSurfaceBitmapError(copyResult);
                }
                handlerThread.quitSafely();
            }
        }, new Handler(handlerThread.getLooper()));
    }

    public interface PixelCopyListener {
        void onSurfaceBitmapReady(Bitmap bitmap);

        void onSurfaceBitmapError(int errorCode);
    }
}