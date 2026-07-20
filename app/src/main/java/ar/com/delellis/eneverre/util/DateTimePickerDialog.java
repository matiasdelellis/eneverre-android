package ar.com.delellis.eneverre.util;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.DatePicker;
import android.widget.NumberPicker;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Calendar;
import java.util.Locale;

import ar.com.delellis.eneverre.R;

public class DateTimePickerDialog {
    public interface OnDateTimeSelected {
        void onSelected(Calendar calendar);
    }

    public static void show(Context context, OnDateTimeSelected listener) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_datetime, null);

        Calendar now = Calendar.getInstance();

        DatePicker datePicker = view.findViewById(R.id.datePicker);
        datePicker.setMaxDate(System.currentTimeMillis());
        datePicker.updateDate(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH));

        NumberPicker hourPicker = view.findViewById(R.id.hourPicker);
        hourPicker.setFormatter(v -> String.format(Locale.US, "%02d", v));
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(23);
        hourPicker.setValue(now.get(Calendar.HOUR_OF_DAY));

        NumberPicker minutePicker = view.findViewById(R.id.minutePicker);
        minutePicker.setFormatter(v -> String.format(Locale.US, "%02d", v));
        minutePicker.setMinValue(0);
        minutePicker.setMaxValue(59);
        minutePicker.setValue(now.get(Calendar.MINUTE));

        NumberPicker secondPicker = view.findViewById(R.id.secondPicker);
        secondPicker.setFormatter(v -> String.format(Locale.US, "%02d", v));
        secondPicker.setMinValue(0);
        secondPicker.setMaxValue(59);
        secondPicker.setValue(now.get(Calendar.SECOND));

        hourPicker.setOnValueChangedListener((picker, oldVal, newVal) ->
                validateDateTime(datePicker, hourPicker, minutePicker, secondPicker));

        minutePicker.setOnValueChangedListener((picker, oldVal, newVal) ->
                validateDateTime(datePicker, hourPicker, minutePicker, secondPicker));

        secondPicker.setOnValueChangedListener((picker, oldVal, newVal) ->
                validateDateTime(datePicker, hourPicker, minutePicker, secondPicker));

        // Re-validate when the date changes too: moving the date forward to today
        // can turn a previously-valid time into a future instant that must be clamped.
        datePicker.init(datePicker.getYear(), datePicker.getMonth(), datePicker.getDayOfMonth(),
                (dp, year, month, day) ->
                        validateDateTime(datePicker, hourPicker, minutePicker, secondPicker));

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.go_date_and_time)
                .setView(view)
                .setPositiveButton(R.string.go, (dialog, which) -> {

                    Calendar calendar = Calendar.getInstance();

                    calendar.set(Calendar.YEAR, datePicker.getYear());
                    calendar.set(Calendar.MONTH, datePicker.getMonth());
                    calendar.set(Calendar.DAY_OF_MONTH, datePicker.getDayOfMonth());

                    calendar.set(Calendar.HOUR_OF_DAY, hourPicker.getValue());
                    calendar.set(Calendar.MINUTE, minutePicker.getValue());
                    calendar.set(Calendar.SECOND, secondPicker.getValue());
                    // Drop the stray milliseconds carried over from "now" so the
                    // seek lands on the exact second the user picked.
                    calendar.set(Calendar.MILLISECOND, 0);

                    // Final guard: never hand back a future instant even if the
                    // pickers were left inconsistent (no recordings exist there).
                    Calendar limit = Calendar.getInstance();
                    if (calendar.after(limit)) {
                        calendar.setTimeInMillis(limit.getTimeInMillis());
                    }

                    listener.onSelected(calendar);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private static void validateDateTime(DatePicker datePicker, NumberPicker hourPicker, NumberPicker minutePicker, NumberPicker secondPicker) {
        Calendar selected = Calendar.getInstance();

        selected.set(
                datePicker.getYear(), datePicker.getMonth(), datePicker.getDayOfMonth(),
                hourPicker.getValue(), minutePicker.getValue(), secondPicker.getValue()
        );
        selected.set(Calendar.MILLISECOND, 0);

        Calendar now = Calendar.getInstance();
        if (selected.after(now)) {
            hourPicker.setValue(now.get(Calendar.HOUR_OF_DAY));
            minutePicker.setValue(now.get(Calendar.MINUTE));
            secondPicker.setValue(now.get(Calendar.SECOND));
        }
    }
}
