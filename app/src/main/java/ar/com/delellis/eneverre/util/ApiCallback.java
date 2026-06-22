package ar.com.delellis.eneverre.util;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Base Retrofit callback that centralizes error handling.
 *
 * It splits the three Retrofit outcomes — successful (2xx) response, HTTP error
 * response, and transport failure — and routes the last two through
 * {@link ApiError} so the user always gets a meaningful, localized message.
 *
 * Subclasses implement {@link #onSuccess(Object)}. The default {@link #onError}
 * shows the mapped message as a toast; override it to add behavior (re-enable a
 * button, navigate away) or to show a more specific message.
 *
 * @param <T> the response body type. For endpoints returning {@code Void} the
 *            body delivered to {@link #onSuccess(Object)} is simply {@code null}.
 */
public abstract class ApiCallback<T> implements Callback<T> {
    private static final String TAG = "ApiCallback";

    private final Context context;

    public ApiCallback(Context context) {
        this.context = context.getApplicationContext();
    }

    public abstract void onSuccess(T body);

    /** Default behavior: toast the mapped message. Override to extend or replace. */
    public void onError(int httpCode, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public final void onResponse(Call<T> call, Response<T> response) {
        if (response.isSuccessful()) {
            onSuccess(response.body());
        } else {
            Log.e(TAG, "HTTP error " + response.code() + " for " + call.request().url());
            onError(response.code(), ApiError.message(context, response.code()));
        }
    }

    @Override
    public final void onFailure(Call<T> call, Throwable throwable) {
        Log.e(TAG, "Request failed for " + call.request().url(), throwable);
        onError(ApiError.NO_HTTP_CODE, ApiError.message(context, throwable));
    }
}
