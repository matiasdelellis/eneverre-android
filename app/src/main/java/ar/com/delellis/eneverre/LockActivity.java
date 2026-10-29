package ar.com.delellis.eneverre;

import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import ar.com.delellis.eneverre.util.AppLock;

/**
 * Full-screen gate shown when the opt-in app lock is enabled and the app is
 * opened after being in the background (or on a cold start). It authenticates
 * with the device's own biometric/credential, so the app never has to store a
 * PIN of its own.
 *
 * <p>Unlocking finishes with {@link #RESULT_OK} so {@code SplashActivity} can
 * continue its stored-session validation; a lock launched over an already
 * running screen simply returns to it. The screen cannot be dismissed with Back
 * (that sends the task to the background) and never clears the session.
 */
public class LockActivity extends AppCompatActivity {

    private BiometricPrompt prompt;
    private boolean promptShowing;
    private boolean autoPromptShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        MaterialButton unlock = findViewById(R.id.lock_unlock);
        unlock.setOnClickListener(v -> authenticate());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Back must not reveal the content behind the lock: leave the
                // app in the background (the task stays locked) instead.
                moveTaskToBack(true);
            }
        });

        prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        promptShowing = false;
                        AppLock.getInstance().unlock();
                        setResult(RESULT_OK);
                        finish();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                                                      @NonNull CharSequence errString) {
                        promptShowing = false;
                        // Stay locked; the Unlock button retries (lockout, cancel).
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!autoPromptShown) {
            autoPromptShown = true;
            authenticate();
        }
    }

    /** Shows the system biometric/credential prompt. */
    private void authenticate() {
        if (!AppLock.getInstance().isLocked()) {
            // Already unlocked (or nothing to protect): do not keep the user here.
            finishUnlocked();
            return;
        }
        if (promptShowing) {
            return;
        }

        int authenticators = AppLock.getInstance().authenticators();
        if (authenticators == 0) {
            finishUnlocked();
            return;
        }

        BiometricPrompt.PromptInfo.Builder info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_prompt_title))
                .setSubtitle(getString(R.string.lock_prompt_subtitle))
                .setAllowedAuthenticators(authenticators);
        if ((authenticators & BiometricManager.Authenticators.DEVICE_CREDENTIAL) == 0) {
            // Required when the prompt does not offer the device credential.
            info.setNegativeButtonText(getString(R.string.cancel));
        }

        promptShowing = true;
        prompt.authenticate(info.build());
    }

    private void finishUnlocked() {
        setResult(RESULT_OK);
        finish();
    }
}
