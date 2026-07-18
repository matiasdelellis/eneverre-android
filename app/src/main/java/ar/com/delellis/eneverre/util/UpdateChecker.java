package ar.com.delellis.eneverre.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ar.com.delellis.eneverre.BuildConfig;
import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.UpdateBuild;
import ar.com.delellis.eneverre.api.model.UpdateManifest;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Implements the client side of the auto-update protocol described in
 * {@code eneverre/doc/UPDATES.md}.
 *
 * <p>Flow (see "Client checklist" in the doc):
 * <ol>
 *   <li>On cold start, fire {@code GET /api/app/phone/update} <em>in parallel
 *       with the auth flow</em>. The check is anonymous; the existing
 *       {@code BearerInterceptor} is a no-op when no tokens are held yet.</li>
 *   <li>204/503/non-2xx → silently do nothing (also covers "no update" /
 *       "feature disabled on the server").</li>
 *   <li>200 with {@code versionCode > BuildConfig.VERSION_CODE} → show the
 *       update dialog. {@code mandatory=true} removes the Later/Skip
 *       buttons and makes the dialog non-cancelable.</li>
 *   <li>Pick the right build: walk {@code Build.SUPPORTED_ABIS} in order,
 *       fall back to {@code universal}, then to the first build.</li>
 *   <li>On user accept: download to a private cache file, verify SHA-256
 *       while streaming, then fire {@code ACTION_INSTALL_PACKAGE} with a
 *       {@code FileProvider} URI.</li>
 *   <li>On Skip: persist the versionName (a strictly-higher one is shown
 *       again automatically).</li>
 *   <li>At most one check per process (doc rule: "never re-GET the same
 *       /api/app/<track>/update more than once per cold start").</li>
 * </ol>
 */
public final class UpdateChecker {
    private static final String TAG = "UpdateChecker";

    /** Sub-directory under {@code getCacheDir()} where downloaded APKs land. */
    private static final String CACHE_SUBDIR = "updates";
    private static final String FILE_PROVIDER_AUTHORITY = BuildConfig.APPLICATION_ID + ".fileprovider";

    /** Serializes downloads so two parallel dialogs can't fight on disk. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    /**
     * Doc rule: never re-GET the same endpoint more than once per cold
     * start. The flag is process-scoped (resets on cold start) and also
     * protects the user from the splash re-checking after a logout / re-
     * login on the same server.
     */
    private static boolean checked = false;

    private UpdateChecker() {}

    /**
     * Outcome of a forced update check, surfaced through
     * {@link UpdateCallback#onResult(UpdateResult)} so the caller can show
     * its own feedback. {@link #UPDATE_AVAILABLE} is also delivered — the
     * dialog is shown either way, but the callback fires first so the
     * caller can re-enable a "Check" button etc.
     */
    public enum UpdateResult {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        FAILED
    }

    /** Result callback for {@link #checkForUpdate(Activity, boolean, UpdateCallback)}. */
    public interface UpdateCallback {
        void onResult(UpdateResult result);
    }

    /**
     * Fires the update check. Safe to call from any activity; subsequent
     * calls within the same process are a no-op. The dialog (if an update
     * is found) is shown in the supplied activity — if that activity has
     * already finished by the time the response arrives, the check
     * silently bails.
     */
    public static synchronized void checkForUpdate(Activity activity) {
        checkForUpdate(activity, false, null);
    }

    /**
     * Same as {@link #checkForUpdate(Activity)}, but lets the caller force a
     * second check (the splash's auto-check counts as the first) and
     * receive a callback with the outcome. The callback is invoked on the
     * UI thread for every terminal outcome, including {@code UPDATE_AVAILABLE}
     * (so the caller can re-enable a button even when the dialog covers
     * most of the screen). Pass {@code null} to opt out of feedback.
     */
    public static synchronized void checkForUpdate(Activity activity, boolean force,
                                                    UpdateCallback callback) {
        if (checked && !force) {
            return;
        }
        checked = true;

        try {
            ApiClient.getInstance();
        } catch (IllegalStateException e) {
            // Not initialized (e.g. called from LoginActivity before
            // performLogin): bail silently. The next caller (after login)
            // gets its own shot because checked is set, but that's fine —
            // the doc rule is one check per cold start.
            if (callback != null) {
                activity.runOnUiThread(() -> callback.onResult(UpdateResult.FAILED));
            }
            return;
        }

        Call<UpdateManifest> call = ApiClient.getApiService().checkUpdate();
        call.enqueue(new retrofit2.Callback<UpdateManifest>() {
            @Override
            public void onResponse(Call<UpdateManifest> call, Response<UpdateManifest> response) {
                // 204 No Content and 503 (feature disabled) are the documented
                // "no update" responses; we also drop any other non-2xx.
                if (response.code() == 204 || response.code() == 503) {
                    if (callback != null) {
                        activity.runOnUiThread(() -> callback.onResult(UpdateResult.UP_TO_DATE));
                    }
                    return;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    Log.w(TAG, "Update check returned HTTP " + response.code());
                    if (callback != null) {
                        activity.runOnUiThread(() -> callback.onResult(UpdateResult.FAILED));
                    }
                    return;
                }

                UpdateManifest manifest = response.body();
                if (manifest.getVersionCode() <= BuildConfig.VERSION_CODE) {
                    if (callback != null) {
                        activity.runOnUiThread(() -> callback.onResult(UpdateResult.UP_TO_DATE));
                    }
                    return; // not newer than what's installed
                }

                UpdatePreferences prefs = UpdatePreferences.getInstance(activity);
                if (prefs.isSkipped(manifest.getVersionName())) {
                    if (callback != null) {
                        activity.runOnUiThread(() -> callback.onResult(UpdateResult.UP_TO_DATE));
                    }
                    return; // user previously chose to skip this version
                }

                UpdateBuild build = selectBuild(manifest.getBuilds());
                if (build == null) {
                    Log.w(TAG, "Manifest has no builds to pick from");
                    if (callback != null) {
                        activity.runOnUiThread(() -> callback.onResult(UpdateResult.FAILED));
                    }
                    return;
                }

                if (activity.isFinishing() || activity.isDestroyed()) {
                    return; // nothing left to host the dialog
                }
                showDialog(activity, manifest, build, prefs);
                if (callback != null) {
                    activity.runOnUiThread(() -> callback.onResult(UpdateResult.UPDATE_AVAILABLE));
                }
            }

            @Override
            public void onFailure(Call<UpdateManifest> call, Throwable t) {
                // Transient error: drop silently (the user is up-to-date by
                // omission and will retry on the next cold start).
                Log.w(TAG, "Update check failed", t);
                if (callback != null) {
                    activity.runOnUiThread(() -> callback.onResult(UpdateResult.FAILED));
                }
            }
        });
    }

    /**
     * Walks {@code Build.SUPPORTED_ABIS} in order looking for an exact ABI
     * match, then {@code "universal"}, then the first build as a last
     * resort.
     */
    @Nullable
    private static UpdateBuild selectBuild(List<UpdateBuild> builds) {
        if (builds == null || builds.isEmpty()) {
            return null;
        }
        for (String abi : Build.SUPPORTED_ABIS) {
            for (UpdateBuild build : builds) {
                if (abi.equals(build.getVariant())) {
                    return build;
                }
            }
        }
        for (UpdateBuild build : builds) {
            if ("universal".equals(build.getVariant())) {
                return build;
            }
        }
        return builds.get(0);
    }

    private static void showDialog(Activity activity, UpdateManifest manifest,
                                   UpdateBuild build, UpdatePreferences prefs) {
        String notes = manifest.getReleaseNotes();
        String message;
        if (notes != null && !notes.trim().isEmpty()) {
            message = activity.getString(
                    R.string.update_available_message_with_notes,
                    manifest.getVersionName(), notes.trim());
        } else {
            message = activity.getString(
                    R.string.update_available_message, manifest.getVersionName());
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(message)
                .setPositiveButton(R.string.update_button_update,
                        (dialog, which) -> downloadAndInstall(activity, build));

        if (manifest.isMandatory()) {
            // Doc: mandatory removes Later/Skip and makes the dialog
            // non-cancelable; dismissing the system installer causes the
            // app to call finish(). We don't yet wire the latter, but
            // setting cancelable=false matches the dialog half.
            builder.setCancelable(false);
        } else {
            builder.setNegativeButton(R.string.update_button_later, null)
                    .setNeutralButton(R.string.update_button_skip,
                            (dialog, which) -> prefs.setSkippedVersion(manifest.getVersionName()));
        }

        builder.show();
    }

    // --- download / verify / install ----------------------------------------

    private static void downloadAndInstall(Activity activity, UpdateBuild build) {
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show();

        final Context appContext = activity.getApplicationContext();
        final String url = build.getUrl();
        final String expectedSha = build.getSha256();
        final String apkFilename = build.getFilename();

        IO.execute(() -> {
            File apkFile = new File(appContext.getCacheDir(), CACHE_SUBDIR + "/" + apkFilename);
            String computedSha;
            try {
                computedSha = downloadAndHash(appContext, url, apkFile);
            } catch (IOException | NoSuchAlgorithmException e) {
                Log.e(TAG, "Failed to download update", e);
                postError(activity, R.string.update_download_failed);
                return;
            }

            if (expectedSha == null
                    || !expectedSha.equalsIgnoreCase(computedSha)) {
                Log.e(TAG, "Integrity check failed: expected " + expectedSha
                        + " but computed " + computedSha);
                // Discard the bad file so we don't accumulate junk in cache.
                if (apkFile.exists() && !apkFile.delete()) {
                    apkFile.deleteOnExit();
                }
                postError(activity, R.string.update_integrity_failed);
                return;
            }

            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            install(appContext, apkFile);
        });
    }

    /**
     * Streams the response body to {@code outFile} while feeding the same
     * bytes to a SHA-256 digest. Returns the lowercase hex digest.
     */
    private static String downloadAndHash(Context context, String url, File outFile)
            throws IOException, NoSuchAlgorithmException {
        // Make sure the parent exists; ignore mkdirs() failure here — the
        // openOutputStream call will fail loudly if it really can't write.
        File parent = outFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        Response<ResponseBody> response = ApiClient.getApiService()
                .downloadUpdate(url)
                .execute();
        try (ResponseBody body = response.body();
             InputStream in = body != null ? body.byteStream() : null;
             OutputStream out = new FileOutputStream(outFile)) {
            if (!response.isSuccessful() || in == null) {
                throw new IOException("Download failed: HTTP " + response.code());
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
            out.flush();
        }

        return bytesToHex(digest.digest());
    }

    private static void install(Context context, File apkFile) {
        Uri uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, apkFile);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // The download may finish after the activity that started it has
        // already been torn down (e.g. the user signed in and bounced to
        // CamerasActivity while the APK was still streaming). Use the
        // application context and FLAG_ACTIVITY_NEW_TASK so the system
        // installer can launch regardless of which activity is on top.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch installer", e);
        }
    }

    private static void postError(Activity activity, int messageRes) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        activity.runOnUiThread(() ->
                Toast.makeText(activity, messageRes, Toast.LENGTH_LONG).show());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
