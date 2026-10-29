package ar.com.delellis.eneverre.util;

import android.app.Activity;
import android.content.Intent;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ar.com.delellis.eneverre.LoginActivity;
import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.api.ApiClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * The one "sign out" flow used by every screen: confirm, best-effort revocation
 * of the current token, clear the stored session and restart the task on login.
 *
 * <p>Keeping it here avoids the copies that had drifted between the camera list,
 * the monitor screen and the splash, and makes the confirmation wording (and the
 * host kept by {@link SecureStore#clearCredentials()}) consistent everywhere.
 */
public final class Logout {
    private Logout() {
    }

    /** Asks for confirmation and, if accepted, signs out. */
    public static void confirm(Activity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.logout_confirm_title)
                .setMessage(R.string.logout_confirm_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.logout, (dialog, which) -> perform(activity))
                .show();
    }

    private static void perform(Activity activity) {
        // Best-effort server-side revocation; don't block the UI on it (if offline
        // the token simply lapses at its TTL).
        try {
            ApiClient.getApiService().logout().enqueue(new Callback<Void>() {
                @Override
                public void onResponse(Call<Void> call, Response<Void> response) {
                }

                @Override
                public void onFailure(Call<Void> call, Throwable throwable) {
                }
            });
        } catch (IllegalStateException ignored) {
            // No API client (e.g. the splash never had a usable host).
        }

        SecureStore.getInstance(activity).clearCredentials();

        Intent intent = new Intent(activity, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }
}
