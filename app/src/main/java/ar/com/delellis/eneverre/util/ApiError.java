package ar.com.delellis.eneverre.util;

import android.content.Context;

import java.io.IOException;

import ar.com.delellis.eneverre.R;

/**
 * Maps API failures (HTTP error codes or thrown exceptions) to a localized,
 * user-facing message. Keeps the wording in a single place so every call site
 * reports errors consistently.
 */
public final class ApiError {
    /** Sentinel passed to error handlers when there is no HTTP response. */
    public static final int NO_HTTP_CODE = -1;

    private ApiError() {}

    /** Message for a non-successful HTTP response. */
    public static String message(Context context, int httpCode) {
        if (httpCode == 401 || httpCode == 403) {
            return context.getString(R.string.error_unauthorized);
        }
        if (httpCode >= 500) {
            return context.getString(R.string.error_server);
        }
        return context.getString(R.string.error_unexpected);
    }

    /** Message for a request that never completed (network failure, etc.). */
    public static String message(Context context, Throwable throwable) {
        if (throwable instanceof IOException) {
            return context.getString(R.string.error_network);
        }
        return context.getString(R.string.error_unexpected);
    }
}
