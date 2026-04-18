package ar.com.delellis.eneverre.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.util.Time;

public class TimelineView extends View {

    public static final long INTERVAL_MIN_1   =            60 * 1000L; //  1 min
    public static final long INTERVAL_HOUR_6  =   6 * 60 * 60 * 1000L; //  6 hours
    public static final long INTERVAL_DAY_5   = 120 * 60 * 60 * 1000L; //  7 days

    public static final long ANIMATION_DURATION_MSEC = 150;

    @SuppressWarnings("FieldCanBeLocal")
    private final float STROKE_SELECTED_WIDTH = 2f;
    @SuppressWarnings("FieldCanBeLocal")
    private final float OFFSET_TOP_BOTTOM = 7f;
    @SuppressWarnings("FieldCanBeLocal")
    private final long MIN_INTERVAL = INTERVAL_MIN_1; // 1 min
    @SuppressWarnings("FieldCanBeLocal")
    private final long MAX_INTERVAL = INTERVAL_DAY_5; // 5 days

    // Minimum pixels between ruler labels
    private static final float MIN_PIXELS_BETWEEN_LABELS = 160f;

    public interface TimeDateFormatter {
        @NonNull String getStringTime(@NonNull Date date);
        @NonNull String getStringDate(@NonNull Date date);
    }

    public interface OnTimelineListener {
        void onTimeSelecting();
        void onTimeSelected(long timestampMsec, @Nullable TimeRecord record);
        void onRequestMoreBackgroundData();
        void onRequestMoreMajor1Data();
        void onRequestMoreMajor2Data();
    }

    public static class TimeRecord {
        public final long timestampMsec; // absolute
        public final long durationMsec;  // relative. Can be 0 if unknown.
        public final Object object;
        @ColorInt public final int color;
        public TimeRecord(long startMs, long durationMs, @NonNull Object obj) {
            this(startMs, durationMs, obj, -1);
        }
        public TimeRecord(long startMs, long durationMs, @NonNull Object obj, int color) {
            timestampMsec = startMs;
            durationMsec = Math.max(1500, durationMs); // min 1.5 sec
            object = obj;
            this.color = color;
        }
        @Override
        @NonNull
        public String toString() {
            return String.format(
                    Locale.ENGLISH,
                    "TimeRecord: {timestamp: %d (%s), duration: %d, object: \"%s\"}",
                    timestampMsec,
                    new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ENGLISH).format(new Date(timestampMsec)),
                    durationMsec,
                    object.toString());
        }
    }

    private SimpleDateFormat _formatHourMin;

    private static class DefaultDateFormatter implements TimeDateFormatter {
        @NonNull
        private final SimpleDateFormat _formatHours;
        @NonNull
        private final SimpleDateFormat _formatDateYear;

        DefaultDateFormatter(Context ctx) {
            boolean is24h = android.text.format.DateFormat.is24HourFormat(ctx);
            String timePattern = is24h ? "HH:mm:ss" : "h:mm:ss a";
            _formatHours = new SimpleDateFormat(timePattern, Locale.getDefault());
            _formatDateYear = (SimpleDateFormat) android.text.format.DateFormat.getLongDateFormat(ctx);
        }

        @Override
        @NonNull
        public String getStringTime(@NonNull Date date) {
            return _formatHours.format(date);
        }

        @Override
        @NonNull
        public String getStringDate(@NonNull Date date) {
            return _formatDateYear.format(date);
        }
    }

    private static class DrawRect {
        public int left;
        public int top;
        public int right;
        public int bottom;
        @ColorInt int color = -1;

        public DrawRect() {}
        public DrawRect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public void set(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private ArrayList<TimeRecord> _recordsMajor1 = new ArrayList<>();
    private ArrayList<TimeRecord> _recordsMajor2 = new ArrayList<>();
    private ArrayList<TimeRecord> _recordsBackground = new ArrayList<>();
    private DrawRect _rectMajor1Selected = null;
    private DrawRect _rectMajor2Selected = null;
    private final ArrayList<DrawRect> _rectsMajor1 = new ArrayList<>();
    private final ArrayList<DrawRect> _rectsMajor2 = new ArrayList<>();
    private final ArrayList<DrawRect> _rectsBackground = new ArrayList<>();
    private final DrawRect _rectNoData = new DrawRect();

    private final Paint _paintMajor1 = new Paint();
    private final Paint _paintMajor2 = new Paint();
    private final Paint _paintSelected1 = new Paint();
    private final Paint _paintSelected2 = new Paint();
    private final Paint _paintBackground = new Paint();
    private final Paint _paintPointer = new Paint();
    private final Paint _paintPointerTimeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint _paintPointerDateText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint _paintTextRuler = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint _paintTextRulerMain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint _paintNoData = new Paint();

    private ValueAnimator _animator;
    private TimeDateFormatter _timedateFormatter = null; // Initialized in init()

    private long _selectedMsec = 0;
    private final Date _selectedMsecDate = new Date(0);
    private long _intervalMsec = INTERVAL_HOUR_6;
    private int _gmtOffsetInMillis = 0;
    private final Rect _rect000000 = new Rect();

    private float _density = 0.0f;
    private boolean _needUpdate = true;
    private GestureDetector _gestureDetector;
    private ScaleGestureDetector _scaleDetector;
    private OnTimelineListener _listener = null;

    public TimelineView(Context context) {
        super(context);
        init(context, null);
    }

    public TimelineView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        init(context, attributeSet);
    }

    public TimelineView(Context context, AttributeSet attributeSet, int defStyleAttr) {
        super(context, attributeSet, defStyleAttr);
        init(context, attributeSet);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(_selectedRunnable);
        super.onDetachedFromWindow();
    }

    private boolean _isScaling = false;
    private boolean _isSelecting = false;
    private boolean _isFlinging = false;

    private boolean _requestedMoreBackground = false;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (event.getPointerCount() > 1) {
            _isScaling = true;
            _scaleDetector.onTouchEvent(event);
            return true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                _isScaling = false;
                removeCallbacks(_selectedRunnable);
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (!_isScaling) {
                    removeCallbacks(_selectingRunnable);
                    removeCallbacks(_selectedRunnable);

                    post(() -> {
                        if (!_isFlinging) {
                            _selectedRunnable.run();
                        }
                    });
                }
                break;
        }

        if (_gestureListener.isAnimating()) {
            _gestureListener.cancelAnimation();
        } else {
            _gestureDetector.onTouchEvent(event);
            _scaleDetector.onTouchEvent(event);
        }
        return true;
    }

    // https://blog.stylingandroid.com/gesture-navigation-edge-cases/
    private final Rect _boundingBox = new Rect();

    @Override
    public void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && changed) {
            _boundingBox.set(left, top, right, bottom);
            setSystemGestureExclusionRects(Collections.singletonList(_boundingBox));
        }
    }

    public void setTimeDateFormatter(@NonNull TimeDateFormatter formatter) {
        _timedateFormatter = formatter;
    }

    public void setOnTimelineListener(@Nullable OnTimelineListener listener) {
        _listener = listener;
    }

    public void setMajor1Records(@NonNull ArrayList<TimeRecord> records) {
        if (records == null)
            throw new NullPointerException("List of major1 records is null");
        _recordsMajor1 = records;
        _needUpdate = true;
    }

    public void setMajor2Records(@NonNull ArrayList<TimeRecord> records) {
        if (records == null)
            throw new NullPointerException("List of major2 records is null");
        _recordsMajor2 = records;
        _needUpdate = true;
    }

    public void setBackgroundRecords(@NonNull ArrayList<TimeRecord> records) {
        if (records == null)
            throw new NullPointerException("List of background records is null");
        _recordsBackground = records;
        _needUpdate = true;
    }

    public void addBackgroundRecordsAtStart(@NonNull ArrayList<TimeRecord> newRecords) {
        if (newRecords.isEmpty()) return;

       _recordsBackground.addAll(0, newRecords);
        _needUpdate = true;
        invalidate();
    }

    @NonNull
    public ArrayList<TimeRecord> getMajor1Records() {
        return _recordsMajor1;
    }

    @NonNull
    public ArrayList<TimeRecord> getBackgroundRecords() {
        return _recordsBackground;
    }

    public void setCurrent(long currentMsec) {
        if (!_recordsBackground.isEmpty()) {
            long first = _recordsBackground.get(0).timestampMsec;

            long overscroll = _intervalMsec / 4;

            if (currentMsec < first - overscroll) {
                currentMsec = first - overscroll;
            }
        }

        _selectedMsec = Math.min(currentMsec, System.currentTimeMillis());
        _selectedMsecDate.setTime(_selectedMsec);
        _needUpdate = true;
    }

    public void setCurrentWithAnimation(long currentMsec) {
        cancelAnimation();
        long offset = (currentMsec - _selectedMsec) / 1000L;
        _animator = ValueAnimator.ofInt(0, (int)offset);
        _animator.setDuration(ANIMATION_DURATION_MSEC);
        _animator.setInterpolator(new AccelerateDecelerateInterpolator());
        final long targetMsec = _selectedMsec;
        _animator.addUpdateListener(animation -> {
            Integer value = (Integer) animation.getAnimatedValue();
            setCurrent(targetMsec + (long)value * 1000L);
            invalidate();
        });
        _animator.start();
    }

    public long getCurrent() {
        return _selectedMsec;
    }

    /**
     * Sets the interval (visible time range) with continuous values.
     * @param intervalMsec The time interval in milliseconds (between MIN_INTERVAL and MAX_INTERVAL)
     */
    public void setInterval(long intervalMsec) {
        intervalMsec = Math.min(MAX_INTERVAL, Math.max(intervalMsec, MIN_INTERVAL));
        if (intervalMsec != _intervalMsec) {
            _intervalMsec = intervalMsec;
            _needUpdate = true;
        }
    }

    private void cancelAnimation() {
        if (_animator != null) {
            _animator.cancel();
            _animator = null;
        }
    }

    private void update() {
        _rectMajor1Selected = null;
        _rectMajor2Selected = null;
        _rectsMajor1.clear();
        _rectsMajor2.clear();
        _rectsBackground.clear();

        int width  = getWidth();
        int height = getHeight();

        long minValue = _selectedMsec - _intervalMsec / 2;
        long maxValue = _selectedMsec + _intervalMsec / 2;
        float msecInPixels = width / (float)_intervalMsec;

        boolean isLandscape = isLandscape();
        int offsetBackground = (int)((isLandscape ? 2.6 : 3.4) * OFFSET_TOP_BOTTOM * _density);
        int offsetMajor1     = (int)((isLandscape ? 2.6 : 3.4) * OFFSET_TOP_BOTTOM * _density);
        int offsetMajor2     = (int)((isLandscape ? 3.2 : 4.2) * OFFSET_TOP_BOTTOM * _density);

        _rectNoData.set(
                0,
                offsetMajor1,
                Math.min((int)((System.currentTimeMillis() - minValue) * msecInPixels), width),
                height - offsetMajor1);


        for (TimeRecord record : _recordsMajor1) {
            if ((record.timestampMsec + record.durationMsec) >= minValue &&
                    (record.timestampMsec) <= maxValue) {

                DrawRect rect = new DrawRect(
                        Math.max((int) ((record.timestampMsec - minValue) * msecInPixels), 0),
                        offsetMajor1,
                        Math.min((int) ((record.timestampMsec - minValue + record.durationMsec) * msecInPixels), width),
                        height - offsetMajor1);

                if (_rectMajor1Selected == null &&
                        _selectedMsec >= record.timestampMsec &&
                        _selectedMsec < (record.timestampMsec + record.durationMsec)) {
                    _rectMajor1Selected = rect;
                } else {
                    rect.color = record.color;
                    _rectsMajor1.add(rect);
                }
            }
        }

        if (_recordsMajor1.size() > 0) {
            TimeRecord record = _recordsMajor1.get(_recordsMajor1.size() - 1);
            if (minValue < record.timestampMsec) {
                _listener.onRequestMoreMajor1Data();
            }
        }

        for (TimeRecord record : _recordsMajor2) {
            if ((record.timestampMsec + record.durationMsec) >= minValue &&
                    (record.timestampMsec) <= maxValue) {

                DrawRect rect = new DrawRect(
                        Math.max((int) ((record.timestampMsec - minValue) * msecInPixels), 0),
                        offsetMajor2,
                        Math.min((int) ((record.timestampMsec - minValue + record.durationMsec) * msecInPixels), width),
                        height - offsetMajor2);

                if (_rectMajor2Selected == null &&
                        _selectedMsec >= record.timestampMsec &&
                        _selectedMsec <= (record.timestampMsec + record.durationMsec)) {

                    _rectMajor2Selected = rect;
                } else {
                    rect.color = record.color;
                    _rectsMajor2.add(rect);
                }
            }
        }

        if (_recordsMajor2.size() > 0) {
            TimeRecord record = _recordsMajor2.get(_recordsMajor2.size() - 1);
            if (minValue < record.timestampMsec) {
                _listener.onRequestMoreMajor2Data();
            }
        }

        for (TimeRecord record : _recordsBackground) {
            if ((record.timestampMsec + record.durationMsec) >= minValue &&
                    (record.timestampMsec) <= maxValue) {

                DrawRect rect = new DrawRect(
                        Math.max((int)((record.timestampMsec - minValue) * msecInPixels), 0),
                        offsetBackground,
                        Math.min((int)((record.timestampMsec - minValue + record.durationMsec) * msecInPixels), width),
                        height - offsetBackground);

                rect.color = record.color;
                _rectsBackground.add(rect);
            }
        }

        if (_recordsBackground.size() > 0) {
            TimeRecord first = _recordsBackground.get(0);
            long threshold = _intervalMsec / 4; // margen de prefetch
            if (minValue <= first.timestampMsec + threshold) {
                if (!_requestedMoreBackground) {
                    _requestedMoreBackground = true;
                    _listener.onRequestMoreBackgroundData();
                }
            } else {
                _requestedMoreBackground = false;
            }
        }
    }

    @Nullable
    private static TimeRecord getRecord(long timestampMsec, @NonNull ArrayList<TimeRecord> records) {
        for (TimeRecord record : records) {
            if (timestampMsec >= record.timestampMsec &&
                    timestampMsec < (record.timestampMsec + record.durationMsec)) {
                return record;
            }
        }
        return null;
    }

    @Nullable
    public TimeRecord getNextMajorRecord() {
        return getNextRecord(_selectedMsec + 1000, _recordsMajor1);
    }

    @Nullable
    public TimeRecord getPrevMajorRecord() {
        return getPrevRecord(_selectedMsec - 30000, _recordsMajor1);
    }

    @Nullable
    public TimeRecord getNextBackgroundRecord() {
        return getNextRecord(_selectedMsec, _recordsBackground);
    }

    @Nullable
    public TimeRecord getCurrentBackgroundRecord() {
        return getRecord(_selectedMsec, _recordsBackground);
    }

    @Nullable
    private static TimeRecord getNextRecord(long currentMsec, @NonNull ArrayList<TimeRecord> records) {
        TimeRecord prevRecord = null;
        for (TimeRecord record : records) {
            if (prevRecord != null &&
                    currentMsec < prevRecord.timestampMsec &&
                    currentMsec >= record.timestampMsec) {
                return prevRecord;
            }
            prevRecord = record;
        }
        return null;
    }

    @Nullable
    private static TimeRecord getPrevRecord(long currentMsec, @NonNull ArrayList<TimeRecord> records) {
        for (TimeRecord record : records) {
            if (record.timestampMsec < currentMsec)
                return record;
        }
        return null;
    }

    private final Runnable _selectingRunnable = () -> {
        if (_listener != null) {
            _listener.onTimeSelecting();
        }
    };

    private final Runnable _selectedRunnable = () -> {
        if (_listener != null) {
            TimeRecord record = getRecord(_selectedMsec, _recordsBackground);
            _listener.onTimeSelected(_selectedMsec, record);
        }
        _isSelecting = false;
    };

    private final SimpleOnScaleGestureListener _scaleListener = new SimpleOnScaleGestureListener() {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            // Real-time continuous zoom during pinch gesture
            float scaleFactor = detector.getScaleFactor();
            long newInterval = (long)(_intervalMsec / scaleFactor);
            newInterval = Math.min(MAX_INTERVAL, Math.max(newInterval, MIN_INTERVAL));

            if (newInterval != _intervalMsec) {
                _intervalMsec = newInterval;
                _needUpdate = true;
                invalidate();
            }
            return true;
        }

        @Override
        public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
        }
    };

    private final TimelineGestureListener _gestureListener = new TimelineGestureListener();
    private class TimelineGestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                float distanceX, float distanceY) {
            if (_waitForNextActionUp || _isScaling)
                return false;

            removeCallbacks(_selectedRunnable);
            if (!_isSelecting) {
                _isSelecting = true;
                post(_selectingRunnable);
            }

            float msecInPixels = _intervalMsec / (float)getWidth();
            long offsetInMsec = (long) (msecInPixels * distanceX);
            setCurrent(getCurrent() + offsetInMsec);
            invalidate();

            return true;
        }

        public boolean onDown(MotionEvent e) {
            _waitForNextActionUp = false;
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            return false;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            _isFlinging = true;
            runFlingAnimation(velocityX);
            return true;
        }

        private boolean _waitForNextActionUp = false;

        boolean isAnimating() {
            return scrollAnimator != null && scrollAnimator.isRunning();
        }

        void cancelAnimation() {
            if (scrollAnimator != null)
                scrollAnimator.cancel();
        }

        private static final float FLING_X_MULTIPLIER = 0.00018f;
        private static final float FLING_DURATION_MULTIPLIER = 0.15f;
        private final Interpolator INTERPOLATOR = new DecelerateInterpolator(1.4f);
        private ValueAnimator scrollAnimator;
        private long savedValue;

        private void runFlingAnimation(float velocity) {
            _isFlinging = true;

            removeCallbacks(_selectingRunnable);
            removeCallbacks(_selectedRunnable);

            int duration = (int) Math.abs(velocity * FLING_DURATION_MULTIPLIER);
            savedValue = getCurrent();
            int endValue = -(int)(velocity * FLING_X_MULTIPLIER * _intervalMsec);
            scrollAnimator = ValueAnimator
                    .ofInt(0, endValue)
                    .setDuration(duration);
            scrollAnimator.setInterpolator(INTERPOLATOR);
            scrollAnimator.addUpdateListener(flingAnimatorListener);
            scrollAnimator.addListener(animatorListener);
            scrollAnimator.start();
        }

        private final ValueAnimator.AnimatorUpdateListener flingAnimatorListener = new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setCurrent(savedValue + (int)animation.getAnimatedValue());
                invalidate();
            }
        };

        private final Animator.AnimatorListener animatorListener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                _waitForNextActionUp = true;
                _selectedRunnable.run();
            }
            @Override
            public void onAnimationEnd(Animator animation) {
                _isFlinging = false;
                _selectedRunnable.run();
            }
        };
    }

    private void init(@NonNull Context context, @Nullable AttributeSet attrs) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        ((WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getMetrics(displayMetrics);
        _density = displayMetrics.density;

        // Initialize SimpleDateFormats lazily (required for DefaultDateFormatter)
        _formatHourMin = new SimpleDateFormat("HH:mm", Locale.getDefault());

        // Initialize default formatter with context
        _timedateFormatter = new DefaultDateFormatter(context);

        _paintSelected1.setColor(Color.CYAN);
        _paintSelected1.setStyle(Paint.Style.FILL);
        _paintSelected1.setStrokeWidth(2f * _density);

        _paintSelected2.setColor(Color.GREEN);
        _paintSelected2.setStyle(Paint.Style.FILL);
        _paintSelected2.setStrokeWidth(2f * _density);

        _paintMajor1.setColor(Color.YELLOW);
        _paintMajor1.setStyle(Paint.Style.FILL);
        _paintMajor1.setStrokeWidth(2f * _density);

        _paintMajor2.setColor(Color.BLUE);
        _paintMajor2.setStyle(Paint.Style.FILL);
        _paintMajor2.setStrokeWidth(2f * _density);

        _paintBackground.setColor(Color.DKGRAY);
        _paintBackground.setStyle(Paint.Style.FILL);

        _paintPointer.setColor(Color.RED);
        _paintPointer.setStyle(Paint.Style.FILL);
        _paintPointer.setStrokeWidth((int)(STROKE_SELECTED_WIDTH * _density));

        _paintPointerTimeText.setColor(Color.WHITE);
        _paintPointerTimeText.setTextSize(12f * _density);
        _paintPointerTimeText.getTextBounds("00:00:00", 0, "00:00:00".length(), _rect000000);

        _paintPointerDateText.setColor(Color.DKGRAY);
        _paintPointerDateText.setTextSize(12f * _density);

        _paintTextRuler.setColor(Color.LTGRAY);
        _paintTextRuler.setTextSize(11f * _density);

        _paintTextRulerMain.setColor(Color.WHITE);
        _paintTextRulerMain.setTextSize(11f * _density);
        _paintTextRulerMain.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        _paintNoData.setColor(Color.DKGRAY);
        _paintNoData.setStyle(Paint.Style.FILL);

        if (attrs != null) {
            TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.TimelineView);
            try {
                _paintSelected1.setColor(array.getColor(R.styleable.TimelineView_timelineColorSelected1, Color.CYAN));
                _paintSelected2.setColor(array.getColor(R.styleable.TimelineView_timelineColorSelected2, Color.GREEN));
                _paintMajor1.setColor(array.getColor(R.styleable.TimelineView_timelineColorMajor1, Color.YELLOW));
                _paintMajor2.setColor(array.getColor(R.styleable.TimelineView_timelineColorMajor2, Color.BLUE));
                _paintBackground.setColor(array.getColor(R.styleable.TimelineView_timelineColorBackground, Color.DKGRAY));
                _paintPointer.setColor(array.getColor(R.styleable.TimelineView_timelineColorPointer, Color.RED));
                _paintPointerTimeText.setColor(array.getColor(R.styleable.TimelineView_timelineColorPointerTimeText, Color.WHITE));
                _paintPointerDateText.setColor(array.getColor(R.styleable.TimelineView_timelineColorPointerDateText, Color.DKGRAY));
                _paintNoData.setColor(array.getColor(R.styleable.TimelineView_timelineColorNoData, Color.LTGRAY));
                _paintTextRuler.setColor(array.getColor(R.styleable.TimelineView_timelineColorText, Color.LTGRAY));
                _paintTextRulerMain.setColor(array.getColor(R.styleable.TimelineView_timelineColorText, Color.LTGRAY));
            } finally {
                array.recycle();
            }
        }

        TimeZone tz = TimeZone.getDefault();
        Calendar cal = GregorianCalendar.getInstance(tz);
        _gmtOffsetInMillis = tz.getOffset(cal.getTimeInMillis());

        _gestureDetector = new GestureDetector(context, _gestureListener);
        _scaleDetector = new ScaleGestureDetector(context, _scaleListener);
    }

    private final Rect r = new Rect();
    private final RectF rf = new RectF();
    private final Paint p = new Paint();

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (_needUpdate) {
            update();
            _needUpdate = false;
        }

        canvas.drawRect(_rectNoData.left, _rectNoData.top, _rectNoData.right, _rectNoData.bottom, _paintNoData);

        for (DrawRect rect : _rectsBackground) {
            canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, _paintBackground);
        }

        for (DrawRect rect : _rectsMajor1) {
            if (rect.color != -1) {
                p.setColor(rect.color);
                p.setStyle(Paint.Style.FILL);
                p.setStrokeWidth(2f * _density);
                canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, p);
                canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, p);
            } else {
                canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, _paintMajor1);
                canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, _paintMajor1);
            }
        }

        for (DrawRect rect : _rectsMajor2) {
            if (rect.color != -1) {
                p.setColor(rect.color);
                p.setStyle(Paint.Style.FILL);
                p.setStrokeWidth(2f * _density);
                canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, p);
                canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, p);
            } else {
                canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, _paintMajor2);
                canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, _paintMajor2);
            }
        }

        if (_rectMajor1Selected != null) {
            canvas.drawRect(
                    _rectMajor1Selected.left,
                    _rectMajor1Selected.top,
                    _rectMajor1Selected.right,
                    _rectMajor1Selected.bottom,
                    _paintSelected1);
        }

        if (_rectMajor2Selected != null) {
            canvas.drawRect(
                    _rectMajor2Selected.left,
                    _rectMajor2Selected.top,
                    _rectMajor2Selected.right,
                    _rectMajor2Selected.bottom,
                    _paintSelected2);
        }

        drawRuler(canvas);

        canvas.drawLine(
                getWidth() >> 1,
                6f * _density,
                getWidth() >> 1,
                getHeight() - 6f * _density,
                _paintPointer);

        drawCurrentTimeDate(canvas);
    }

    private void drawCurrentTimeDate(@NonNull Canvas canvas) {
        final String time;
        final boolean isNow;
        final String date;

        if (System.currentTimeMillis() - _selectedMsec < 5000) {
            time = "Now";
            isNow = true;
        } else {
            time = _timedateFormatter.getStringTime(_selectedMsecDate);
            isNow = false;
        }

        // Draw current time box
        _paintPointerTimeText.getTextBounds(time, 0, time.length(), r);

        rf.set((!isNow && _rect000000.width() > r.width()) ? _rect000000 : r);

        rf.offsetTo((getWidth() >> 1) - rf.width() / 2, 0);
        rf.inset(-4f * _density, -3f * _density);
        rf.offset(0, 6f * _density);
        canvas.drawRoundRect(rf, 4f * _density, 3f * _density, _paintPointer);

        // Draw current time
        canvas.drawText(
                time,
                (getWidth() >> 1) - rf.width() / 2 + 4f * _density,
                r.height() + 6 * _density,
                _paintPointerTimeText);

        // Draw date
        Date todayDate = new Date(System.currentTimeMillis());
        Date yesterdayDate = Time.dateAddDays(todayDate, -1);
        if (Time.isSameDay(_selectedMsecDate, todayDate)) {
            date = getContext().getString(R.string.today);
        } else if (Time.isSameDay(_selectedMsecDate, yesterdayDate)) {
            date = getContext().getString(R.string.yesterday);
        } else {
            date = _timedateFormatter.getStringDate(_selectedMsecDate);
        }

        _paintPointerDateText.getTextBounds(date, 0, date.length(), r);
        canvas.drawText(
                date,
                getWidth() - r.width() - 6 * _density,
                r.height() + 6 *_density,
                _paintPointerDateText);
    }

    /**
     * Calculates optimal interval for ruler labels based on available width.
     * Returns the interval in milliseconds that gives readable labels.
     */
    private long getOptimalRulerInterval() {
        float msecInPixels = getWidth() / (float)_intervalMsec;

        // Calculate desired interval in milliseconds
        long desiredIntervalMsec = (long)(MIN_PIXELS_BETWEEN_LABELS / msecInPixels);

        // Round to nice intervals
        return getNiceInterval(desiredIntervalMsec);
    }

    /**
     * Returns a "nice" interval that's easy to read and display.
     */
    private long getNiceInterval(long intervalMsec) {
        // Define nice intervals in ascending order
        long[] niceIntervals = {
                30 * 1000L,                    // 30 seconds
                60 * 1000L,                    // 1 minute
                2 * 60 * 1000L,                // 2 minutes
                5 * 60 * 1000L,                // 5 minutes
                10 * 60 * 1000L,               // 10 minutes
                15 * 60 * 1000L,               // 15 minutes
                30 * 60 * 1000L,               // 30 minutes
                60 * 60 * 1000L,               // 1 hour
                2 * 60 * 60 * 1000L,           // 2 hours
                3 * 60 * 60 * 1000L,           // 3 hours
                6 * 60 * 60 * 1000L,           // 6 hours
                12 * 60 * 60 * 1000L,          // 12 hours
                24 * 60 * 60 * 1000L           // 1 day
        };

        for (long nice : niceIntervals) {
            if (nice >= intervalMsec) {
                return nice;
            }
        }
        return niceIntervals[niceIntervals.length - 1];
    }

    /**
     * Draws adaptive ruler with labels spaced based on available width.
     */
    private void drawAdaptiveRuler(@NonNull Canvas canvas, float msecInPixels, long interval) {
        long minValue = _selectedMsec - _intervalMsec / 2 + _gmtOffsetInMillis;
        long maxValue = _selectedMsec + _intervalMsec / 2 + _gmtOffsetInMillis;

        // Calculate the first label position aligned to interval
        long firstLabelTime = minValue - (minValue % interval);
        if (minValue < 0) {
            firstLabelTime -= interval;
        }
        firstLabelTime += interval;

        int height = getHeight();
        float offsetTopBottomDensity = OFFSET_TOP_BOTTOM * _density;

        // Measure text width for spacing check
        _paintPointerTimeText.getTextBounds("__00:00__", 0, 9, r);
        float halfTextWidth = r.width() / 2f;

        // Calculate actual pixels between labels
        float pixelsBetweenLabels = interval * msecInPixels;

        // Only draw if we have enough space
        if (pixelsBetweenLabels < MIN_PIXELS_BETWEEN_LABELS) {
            return;
        }

        // Draw labels
        for (long labelTime = firstLabelTime; labelTime <= maxValue; labelTime += interval) {
            float x = (labelTime - minValue) * msecInPixels;

            // Skip if too close to edges
            if (x < halfTextWidth || x > getWidth() - halfTextWidth) {
                continue;
            }

            Date date = new Date(labelTime - _gmtOffsetInMillis);
            String text = _formatHourMin.format(date);

            _paintPointerTimeText.getTextBounds(text, 0, text.length(), r);

            // Draw main label
            canvas.drawText(text, x - r.width() / 2f, height - r.height(), _paintTextRuler);

            // Draw ruler line
            canvas.drawLine(
                    x,
                    height - offsetTopBottomDensity / 3,
                    x,
                    height - offsetTopBottomDensity,
                    _paintPointerTimeText);

            // Draw half-interval tick if space permits
            if (pixelsBetweenLabels > MIN_PIXELS_BETWEEN_LABELS * 2) {
                canvas.drawLine(
                        x + pixelsBetweenLabels / 2f,
                        height - offsetTopBottomDensity / 1.5f,
                        x + pixelsBetweenLabels / 2f,
                        height - offsetTopBottomDensity / 2f,
                        _paintPointerDateText);
            }
        }
    }

    private void drawRuler(@NonNull Canvas canvas) {
        float msecInPixels = getWidth() / (float)_intervalMsec;

        // Use adaptive interval based on zoom level
        long rulerInterval = getOptimalRulerInterval();
        drawAdaptiveRuler(canvas, msecInPixels, rulerInterval);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        _needUpdate = true;
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

}