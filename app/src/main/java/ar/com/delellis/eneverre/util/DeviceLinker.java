package ar.com.delellis.eneverre.util;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.UserCode;
import ar.com.delellis.eneverre.api.model.VerifyStatus;

/**
 * Device-linking flow shared between the "Link device" menu option (manual code
 * entry from {@code SessionsActivity}) and the device-linking deep link
 * ({@code <host>/?usercode=XXXXXX}, confirmed from {@code CamerasActivity}). Both
 * POST the 6-digit user code to {@code device_verify} and report the outcome.
 */
public class DeviceLinker {

    private DeviceLinker() {
    }

    /**
     * Confirms the code with the user before authorizing. Used by the deep link,
     * where the code is not typed by the user but delivered from the link.
     */
    public static void confirm(@NonNull Context context, @NonNull String userCode) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.link_device)
                .setMessage(context.getString(R.string.link_device_confirm, userCode))
                .setPositiveButton(R.string.accept, (dialog, which) -> verify(context, userCode))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Authorizes the given device user code and toasts the resulting status. */
    public static void verify(@NonNull Context context, @NonNull String userCode) {
        UserCode code = new UserCode(userCode);

        ApiClient.getApiService().device_verify(code).enqueue(new ApiCallback<VerifyStatus>(context) {
            @Override
            public void onSuccess(VerifyStatus verifyStatus) {
                if (verifyStatus == null) {
                    onError(ApiError.NO_HTTP_CODE, null);
                    return;
                }

                String status = verifyStatus.getStatus();
                if ("approved".equals(status)) {
                    String deviceName = verifyStatus.getDeviceName();
                    String message = deviceName != null && !deviceName.trim().isEmpty()
                            ? context.getString(R.string.approved_device_named, deviceName.trim())
                            : context.getString(R.string.approved_device);
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                } else if ("expired".equals(status)) {
                    Toast.makeText(context, R.string.the_code_has_expired, Toast.LENGTH_LONG).show();
                } else if ("invalid".equals(status)) {
                    Toast.makeText(context, R.string.invalid_user_code, Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onError(int httpCode, String message) {
                Toast.makeText(context, R.string.device_linking_failed, Toast.LENGTH_LONG).show();
            }
        });
    }
}
