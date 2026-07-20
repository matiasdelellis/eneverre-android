package ar.com.delellis.eneverre.util;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import ar.com.delellis.eneverre.R;

public class UserCodePickerDialog {
    /** Converts a dp value to physical pixels for the given context. */
    private static int dp(@NonNull Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    public static void show(@NonNull Context context, @NonNull OnCodeSelectedListener listener) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER);
        container.setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 4));

        TextInputEditText[] inputs = new TextInputEditText[6];

        for (int i = 0; i < 6; i++) {
            TextInputLayout layout = new TextInputLayout(
                    new ContextThemeWrapper(context, com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox)
            );

            LinearLayout.LayoutParams layoutParams =
                    new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    );

            layoutParams.setMargins(dp(context, 4), 0, dp(context, 4), 0);
            layout.setLayoutParams(layoutParams);

            layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            float corner = dp(context, 8);
            layout.setBoxCornerRadii(corner, corner, corner, corner);

            TextInputEditText input = new TextInputEditText(context);

            input.setGravity(Gravity.CENTER);
            input.setTextSize(22);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            input.setSingleLine(true);

            input.setFilters(new InputFilter[]{
                    new InputFilter.AllCaps(),
                    new InputFilter.LengthFilter(1)
            });

            final int index = i;

            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (s.length() == 1 && index < 5) {
                        inputs[index + 1].requestFocus();
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                }
            });

            input.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL
                        && input.getText() != null
                        && input.getText().length() == 0
                        && index > 0) {

                    inputs[index - 1].requestFocus();
                }

                return false;
            });

            inputs[i] = input;

            layout.addView(input);
            container.addView(layout);
        }

        MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.link_device)
                        .setMessage(R.string.linkDeviceMessage)
                        .setView(container)
                        .setPositiveButton(R.string.accept, null)
                        .setNegativeButton(R.string.cancel, null);

        androidx.appcompat.app.AlertDialog dialog = builder.show();

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    StringBuilder code = new StringBuilder();

                    for (TextInputEditText input : inputs) {
                        String value = "";

                        if (input.getText() != null) {
                            value = input.getText().toString().trim();
                        }

                        if (value.isEmpty()) {
                            input.requestFocus();
                            return;
                        }

                        code.append(value.toUpperCase());
                    }

                    if (code.length() < 6) {
                        return;
                    }

                    listener.onCodeSelected(code.toString());

                    dialog.dismiss();
                });

        inputs[0].requestFocus();
    }

    public interface OnCodeSelectedListener {
        void onCodeSelected(@NonNull String code);
    }
}