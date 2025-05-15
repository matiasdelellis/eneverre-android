package ar.com.delellis.eneverre.util;

import static java.lang.Math.clamp;

import android.annotation.SuppressLint;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;

import org.videolan.libvlc.util.VLCVideoLayout;

public class VideoTouchListener implements View.OnTouchListener {

    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int touchMode = NONE;

    private final PointF lastEvent = new PointF();
    private float lastDistance = 1f;
    private float currentScale = 1f;
    private int maxScrollX = 0;
    private int maxScrollY = 0;

    private VLCVideoLayout vlcVideoLayout = null;

    public VideoTouchListener(VLCVideoLayout view) {
        vlcVideoLayout = view;
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                lastEvent.set(event.getRawX(), event.getRawY());
                touchMode = DRAG;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastDistance = getPinchDistance(event);
                if (lastDistance > 10f) {
                    touchMode = ZOOM;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                touchMode = NONE;
                break;
            case MotionEvent.ACTION_MOVE:
                if (touchMode == DRAG) {
                    float dx = (event.getRawX() - lastEvent.x) / currentScale;
                    int scrollX = vlcVideoLayout.getScrollX() - (int) dx;
                    scrollX = clamp(scrollX, -maxScrollX, maxScrollX);
                    vlcVideoLayout.setScrollX((int) scrollX);

                    float dy = (event.getRawY() - lastEvent.y) / currentScale;
                    int scrrollY = vlcVideoLayout.getScrollY() - (int) dy;
                    scrrollY = clamp(scrrollY, -maxScrollY, maxScrollY);
                    vlcVideoLayout.setScrollY(scrrollY);

                    lastEvent.set(event.getRawX(), event.getRawY());
                } else if (touchMode == ZOOM) {
                    float newDistance = getPinchDistance(event);
                    float calcScale = (newDistance / lastDistance);

                    calcScale = vlcVideoLayout.getScaleX() * calcScale;
                    currentScale = clamp(calcScale, 1.0f, 5f);

                    vlcVideoLayout.setScaleX(currentScale);
                    vlcVideoLayout.setScaleY(currentScale);

                    // D'Oh!. Took me a week to discover this math.
                    maxScrollX = (int) ((currentScale - 1f) * vlcVideoLayout.getWidth() / (2 * currentScale));
                    maxScrollY = (int) ((currentScale - 1f) * vlcVideoLayout.getHeight() / (2 * currentScale));

                    int scrollX = clamp(vlcVideoLayout.getScrollX(), -maxScrollX, maxScrollX);
                    vlcVideoLayout.setScrollX(scrollX);

                    int scrollY = clamp(vlcVideoLayout.getScrollY(), -maxScrollY, maxScrollY);
                    vlcVideoLayout.setScrollY(scrollY);
                }
                break;
            default:
                break;
        }
        return true;
    }

    private float getPinchDistance(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }
}