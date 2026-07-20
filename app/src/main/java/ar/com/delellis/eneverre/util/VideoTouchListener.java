package ar.com.delellis.eneverre.util;

import static java.lang.Math.clamp;

import android.annotation.SuppressLint;
import android.graphics.PointF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Touch handler for a video surface: pinch-to-zoom, drag-to-pan while zoomed,
 * double-tap to toggle 1x/2x, and press-and-hold to fast-forward. It operates on
 * whatever {@link View} it is attached to (the one delivered to {@link #onTouch}),
 * so usage is just:
 *
 * <pre>{@code
 * VideoTouchListener l = new VideoTouchListener();
 * l.setOnLongPressListener(...);
 * videoView.setOnTouchListener(l);
 * }</pre>
 *
 * When a PTZ pan listener is set and enabled, an unzoomed (1x) single-finger
 * drag is reported as raw pixel deltas so the owner can drive the physical
 * camera. While zoomed the drag keeps panning digitally, as before. This
 * claims the unzoomed drag from any enclosing pager.
 */
public class VideoTouchListener implements View.OnTouchListener {

    /** Notified when the user presses and holds on the video, and when they release. */
    public interface OnLongPressListener {
        void onLongPressStart();
        void onLongPressEnd();
    }

    /** Receives unzoomed drag deltas (in pixels) to convert into physical PTZ moves. */
    public interface OnPtzPanListener {
        void onPtzPan(float dxPixels, float dyPixels);
        void onPtzPanEnd();
    }

    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int touchMode = NONE;

    private final PointF lastEvent = new PointF();
    /** Where the current gesture went down; PTZ panning starts only past the touch slop. */
    private final PointF downEvent = new PointF();
    /** System touch slop in pixels; resolved lazily (needs a Context from the view). */
    private int touchSlop = -1;
    private float lastDistance = 1f;
    private float currentScale = 1f;
    private int maxScrollX = 0;
    private int maxScrollY = 0;

    /** The view currently being touched; valid for the duration of an onTouch pass. */
    private View videoView;

    /** Created lazily on the first touch (it needs a Context from the view). */
    private GestureDetector gestureDetector;
    private OnLongPressListener longPressListener = null;
    private boolean longPressActive = false;

    private OnPtzPanListener ptzPanListener = null;
    private boolean ptzPanEnabled = false;
    /** Whether the current DRAG delivered at least one PTZ delta (gates the end event). */
    private boolean ptzPanning = false;

    public void setOnLongPressListener(OnLongPressListener listener) {
        longPressListener = listener;
    }

    public void setOnPtzPanListener(OnPtzPanListener listener) {
        ptzPanListener = listener;
    }

    /** Toggled by the owner as PTZ availability changes (capability, privacy mode). */
    public void setPtzPanEnabled(boolean enabled) {
        ptzPanEnabled = enabled;
    }

    private boolean ptzPanActive() {
        return ptzPanEnabled && ptzPanListener != null;
    }

    private final GestureDetector.SimpleOnGestureListener gestureListener =
            new GestureDetector.SimpleOnGestureListener() {
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

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    // Toggle between fit (1x) and 2x, centering the zoom on the tapped
                    // point. Any in-progress hold is cancelled so it doesn't stick.
                    endLongPress();
                    if (currentScale > 1f) {
                        applyScale(1f, e.getX(), e.getY());
                    } else {
                        applyScale(2f, e.getX(), e.getY());
                    }
                    return true;
                }
            };

    private void endLongPress() {
        if (longPressActive) {
            longPressActive = false;
            if (longPressListener != null) {
                longPressListener.onLongPressEnd();
            }
        }
    }

    private void endPtzPan() {
        if (ptzPanning) {
            ptzPanning = false;
            if (ptzPanListener != null) {
                ptzPanListener.onPtzPanEnd();
            }
        }
    }

    private int touchSlop(View v) {
        if (touchSlop < 0) {
            touchSlop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
        }
        return touchSlop;
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouch(View v, MotionEvent event) {
        videoView = v;
        if (gestureDetector == null) {
            gestureDetector = new GestureDetector(v.getContext(), gestureListener);
        }
        gestureDetector.onTouchEvent(event);

        switch (event.getAction() & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN:
                lastEvent.set(event.getRawX(), event.getRawY());
                downEvent.set(event.getRawX(), event.getRawY());
                touchMode = DRAG;
                // Hijack the gesture from an enclosing pager when the video is
                // zoomed in (there is something to pan digitally) or the drag
                // drives the physical PTZ. Otherwise let the ViewPager2 receive
                // the swipe to change camera.
                v.getParent().requestDisallowInterceptTouchEvent(currentScale > 1f || ptzPanActive());
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                lastDistance = getPinchDistance(event);
                if (lastDistance > 10f) {
                    touchMode = ZOOM;
                    // A pinch is starting: keep the gesture for ourselves, and
                    // cancel any in-progress hold or PTZ pan so they don't stick.
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    endLongPress();
                    endPtzPan();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                touchMode = NONE;
                endLongPress();
                endPtzPan();
                break;
            case MotionEvent.ACTION_MOVE:
                if (touchMode == DRAG) {
                    if (currentScale <= 1f) {
                        if (ptzPanActive()) {
                            // Not zoomed on a PTZ camera: the drag moves the
                            // physical camera instead of paging. Sub-slop MOVE
                            // events still arrive for plain taps and double
                            // taps, so panning starts only once the finger
                            // clears the touch slop — otherwise every sloppy
                            // tap would twitch the camera. The pre-slop offset
                            // is dropped so the gesture starts from rest.
                            if (!ptzPanning) {
                                if (Math.hypot(event.getRawX() - downEvent.x,
                                        event.getRawY() - downEvent.y) < touchSlop(v)) {
                                    break;
                                }
                                ptzPanning = true;
                                lastEvent.set(event.getRawX(), event.getRawY());
                                break;
                            }
                            ptzPanListener.onPtzPan(event.getRawX() - lastEvent.x,
                                    event.getRawY() - lastEvent.y);
                            lastEvent.set(event.getRawX(), event.getRawY());
                        }
                        // Otherwise nothing to pan; let the pager handle paging.
                        break;
                    }
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    float dx = (event.getRawX() - lastEvent.x) / currentScale;
                    float dy = (event.getRawY() - lastEvent.y) / currentScale;
                    v.setScrollX(v.getScrollX() - (int) dx);
                    v.setScrollY(v.getScrollY() - (int) dy);
                    clampScroll();

                    lastEvent.set(event.getRawX(), event.getRawY());
                } else if (touchMode == ZOOM) {
                    float newDistance = getPinchDistance(event);
                    setScale(v.getScaleX() * (newDistance / lastDistance));
                    // Advance the reference distance so the next MOVE scales by the
                    // incremental change. Without this, every MOVE multiplies the
                    // (already grown) scale by the ratio to the *initial* distance,
                    // compounding it and snapping straight to the max zoom.
                    lastDistance = newDistance;
                    clampScroll();
                }
                break;
            default:
                break;
        }
        return true;
    }

    /**
     * Applies the clamped scale to the view and recomputes the pan bounds.
     * The caller is responsible for positioning (and clamping) the scroll.
     */
    private void setScale(float scale) {
        currentScale = clamp(scale, 1.0f, 9f);

        videoView.setScaleX(currentScale);
        videoView.setScaleY(currentScale);

        // D'Oh!. Took me a week to discover this math.
        maxScrollX = (int) ((currentScale - 1f) * videoView.getWidth() / (2 * currentScale));
        maxScrollY = (int) ((currentScale - 1f) * videoView.getHeight() / (2 * currentScale));
    }

    /** Clamps the current pan to the bounds computed by {@link #setScale}. */
    private void clampScroll() {
        videoView.setScrollX(clamp(videoView.getScrollX(), -maxScrollX, maxScrollX));
        videoView.setScrollY(clamp(videoView.getScrollY(), -maxScrollY, maxScrollY));
    }

    /**
     * Sets the video scale, centering on the given view-local point, and keeps
     * the pan within bounds. The pager keeps the gesture only while zoomed in.
     */
    private void applyScale(float scale, float focusX, float focusY) {
        setScale(scale);

        float factor = (currentScale - 1f) / currentScale;
        videoView.setScrollX((int) ((focusX - videoView.getWidth() / 2f) * factor));
        videoView.setScrollY((int) ((focusY - videoView.getHeight() / 2f) * factor));
        clampScroll();

        videoView.getParent().requestDisallowInterceptTouchEvent(currentScale > 1f);
    }

    private float getPinchDistance(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }
}
