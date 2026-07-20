package ar.com.delellis.eneverre.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * Runtime {@code WRITE_EXTERNAL_STORAGE} gate for saving snapshots/clips.
 *
 * <p>Only API 24-28 need it: {@link Download} writes through the {@code File}
 * API there, while API 29+ uses {@link android.provider.MediaStore}, which needs
 * no storage permission. Since {@code minSdk} is 24, every pre-Q device is API
 * 23+ and therefore requires a runtime grant.
 */
public final class StoragePermission {
    private StoragePermission() {}

    public static final String PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE;

    /** Whether saving to public storage needs a runtime permission on this device. */
    public static boolean isRequired() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    /** True if the permission isn't needed here or is already granted. */
    public static boolean isGranted(Context context) {
        if (!isRequired()) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
