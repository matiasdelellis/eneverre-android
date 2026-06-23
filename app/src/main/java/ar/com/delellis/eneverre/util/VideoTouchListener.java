package ar.com.delellis.eneverre.util;

import static java.lang.Math.clamp;

import android.annotation.SuppressLint;
import android.graphics.PointF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import org.videolan.libvlc.util.VLCVideoLayout;

public class VideoTouchListener implements View.OnTouchListener {

    /** Notified when the user presses and holds on the video, and when they release. */
    public interface OnLongPressListener {
        void onLongPressStart();
        void onLongPressEnd();
    }

    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int touchMode = NONE;

    private final PointF lastEvent = new PointF();
    private float lastDistance = 1f;
    private float currentScale = 1f;
    private int maxScrollX = 0;
    private int maxScrollY = 0;

    private final VLCVideoLayout vlcVideoLayout;

    private final GestureDetector gestureDetector;
    private OnLongPressListener longPressListener = null;
    private boolean longPressActive = false;

    public VideoTouchListener(VLCVideoLayout view) {
        vlcVideoLayout = view;
        gestureDetector = new GestureDetector(view.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // A still single-finger hold is the fast-forward gesture; ignore
                // it while pinching. The detector already cancels it if the finger
                // moves (drag-to-pan / swipe-to-page), so those don't trigger it.
                if (touchMode == ZOOM || longPressListener == null) {
                    return;
                }
                longPressActive = true;
                longPressListener.onLongPressStart();
            }
        });
    }

    public void setOnLongPressListener(OnLongPressListener listener) {
        longPressListener = listener;
    }

    private void endLongPress() {
        if (longPressActive) {
            longPressActive = false;
            if (longPressListener != null) {
                longPressListener.onLongPressEnd();
            }
        }
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouch(View v, MotionEvent event) {
        gestureDetector.onTouchEvent(event);

        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                lastEvent.set(event.getRawX(), event.getRawY());
                touchMode = DRAG;
                // Only hijack the gesture from an enclosing pager when the video
                // is zoomed in (and there is therefore something to pan).
                // Otherwise let the ViewPager2 receive the swipe to change camera.
                v.getParent().requestDisallowInterceptTouchEvent(currentScale > 1f);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastDistance = getPinchDistance(event);
                if (lastDistance > 10f) {
                    touchMode = ZOOM;
                    // A pinch is starting: keep the gesture for ourselves, and
                    // cancel any in-progress hold so it doesn't stick at 2x.
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    endLongPress();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                touchMode = NONE;
                endLongPress();
                break;
            case MotionEvent.ACTION_MOVE:
                if (touchMode == DRAG) {
                    if (currentScale <= 1f) {
                        // Not zoomed: nothing to pan; let the pager handle paging.
                        break;
                    }
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    float dx = (event.getRawX() - lastEvent.x) / currentScale;
                    int scrollX = vlcVideoLayout.getScrollX() - (int) dx;
                    scrollX = clamp(scrollX, -maxScrollX, maxScrollX);
                    vlcVideoLayout.setScrollX(scrollX);

                    float dy = (event.getRawY() - lastEvent.y) / currentScale;
                    int scrrollY = vlcVideoLayout.getScrollY() - (int) dy;
                    scrrollY = clamp(scrrollY, -maxScrollY, maxScrollY);
                    vlcVideoLayout.setScrollY(scrrollY);

                    lastEvent.set(event.getRawX(), event.getRawY());
                } else if (touchMode == ZOOM) {
                    float newDistance = getPinchDistance(event);
                    float calcScale = (newDistance / lastDistance);

                    calcScale = vlcVideoLayout.getScaleX() * calcScale;
                    currentScale = clamp(calcScale, 1.0f, 9f);

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