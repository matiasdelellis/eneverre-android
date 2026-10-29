package ar.com.delellis.eneverre;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import ar.com.delellis.eneverre.util.AppLock;
import ar.com.delellis.eneverre.util.AppPreferences;
import ar.com.delellis.eneverre.util.Logout;
import ar.com.delellis.eneverre.util.SecureStore;
import ar.com.delellis.eneverre.util.UpdateChecker;

/**
 * The app's configuration hub: one place for the options that used to be loose
 * entries in the camera list's overflow (app lock, sessions, users, updates,
 * about, sign out) plus changing the password.
 *
 * <p>Sections are shown in the order a user looks for them: security, account,
 * application; administrative entries are hidden for non-admins (the server
 * enforces the role too).
 */
public class SettingsActivity extends AppCompatActivity {

    private MaterialSwitch lockSwitch;
    /** Guards the programmatic revert of the switch from re-entering the listener. */
    private boolean updatingLockSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.settings_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        // Security
        bindSwitchRow(R.id.row_app_lock, R.drawable.ic_lock_24,
                R.string.app_lock, R.string.settings_app_lock_subtitle);
        bindRow(R.id.row_change_password, R.drawable.ic_key_24,
                R.string.change_password_button, R.string.settings_change_password_subtitle);

        // Account
        bindRow(R.id.row_sessions, R.drawable.ic_devices_24,
                R.string.my_sessions, R.string.settings_sessions_subtitle);
        bindRow(R.id.row_users, R.drawable.ic_account_24,
                R.string.manage_users, R.string.settings_users_subtitle);
        bindLogoutRow();

        // Application: the update row shows the installed version as a subtitle.
        bindRow(R.id.row_check_updates, R.drawable.ic_refresh_24,
                R.string.check_for_updates, 0);
        setRowSubtitle(R.id.row_check_updates,
                getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        bindRow(R.id.row_about, R.drawable.ic_eneverre_logo_24,
                R.string.about_eneverre, 0);

        // Admin-only entry (the endpoint is role-gated server-side too).
        boolean admin = SecureStore.getInstance(this).isAdmin();
        findViewById(R.id.row_users).setVisibility(admin ? View.VISIBLE : View.GONE);
        findViewById(R.id.users_divider).setVisibility(admin ? View.VISIBLE : View.GONE);

        findViewById(R.id.row_change_password).setOnClickListener(v -> {
            Intent intent = new Intent(this, ChangePasswordActivity.class);
            // Not the mandatory flow: ask for the current password and stay here.
            intent.putExtra(ChangePasswordActivity.EXTRA_VOLUNTARY, true);
            startActivity(intent);
        });
        findViewById(R.id.row_sessions).setOnClickListener(v ->
                startActivity(new Intent(this, SessionsActivity.class)));
        findViewById(R.id.row_users).setOnClickListener(v ->
                startActivity(new Intent(this, UsersActivity.class)));
        findViewById(R.id.row_check_updates).setOnClickListener(v -> checkForUpdates());
        findViewById(R.id.row_about).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));

        TextView footer = findViewById(R.id.settings_footer);
        footer.setText(getString(R.string.settings_footer,
                SecureStore.getInstance(this).getConfigHost(), BuildConfig.VERSION_NAME));

        // Keep the last row above the system navigation bar (edge-to-edge).
        View content = findViewById(R.id.settings_content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    dp(24) + bottom);
            return insets;
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void bindRow(int rowId, int iconRes, int titleRes, int subtitleRes) {
        View row = findViewById(rowId);
        ((ImageView) row.findViewById(R.id.setting_icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.setting_title)).setText(titleRes);
        if (subtitleRes != 0) {
            setRowSubtitle(rowId, getString(subtitleRes));
        }
    }

    private void setRowSubtitle(int rowId, String subtitle) {
        TextView subtitleView = findViewById(rowId).findViewById(R.id.setting_subtitle);
        subtitleView.setText(subtitle);
        subtitleView.setVisibility(View.VISIBLE);
    }

    private void bindSwitchRow(int rowId, int iconRes, int titleRes, int subtitleRes) {
        View row = findViewById(rowId);
        ((ImageView) row.findViewById(R.id.setting_switch_icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.setting_switch_title)).setText(titleRes);
        TextView subtitle = row.findViewById(R.id.setting_switch_subtitle);
        subtitle.setText(subtitleRes);
        subtitle.setVisibility(View.VISIBLE);

        lockSwitch = row.findViewById(R.id.setting_switch);
        lockSwitch.setChecked(AppPreferences.getInstance(this).isAppLockEnabled());
        lockSwitch.setOnCheckedChangeListener((button, checked) -> onLockToggled(checked));
    }

    private void bindLogoutRow() {
        View row = findViewById(R.id.row_logout);
        ((ImageView) row.findViewById(R.id.setting_icon)).setImageResource(R.drawable.ic_logout_24);
        TextView title = row.findViewById(R.id.setting_title);
        title.setText(R.string.logout);
        // Destructive action: error colour, no "goes somewhere" chevron.
        title.setTextColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorError));
        row.findViewById(R.id.setting_chevron).setVisibility(View.GONE);
        row.setOnClickListener(v -> Logout.confirm(this));
    }

    /**
     * Persists the opt-in app lock. Enabling requires a device screen lock to
     * authenticate against, so it is refused (with an explanation) when there is
     * none instead of leaving a lock nobody could open.
     */
    private void onLockToggled(boolean enabled) {
        if (updatingLockSwitch) {
            return;
        }
        if (enabled && AppLock.getInstance().authenticators() == 0) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.app_lock_unavailable_title)
                    .setMessage(R.string.app_lock_unavailable_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            updatingLockSwitch = true;
            lockSwitch.setChecked(false);
            updatingLockSwitch = false;
            return;
        }
        AppPreferences.getInstance(this).setAppLockEnabled(enabled);
        Toast.makeText(this,
                enabled ? R.string.app_lock_enabled : R.string.app_lock_disabled,
                Toast.LENGTH_SHORT).show();
    }

    private void checkForUpdates() {
        UpdateChecker.checkForUpdate(this, true, result -> {
            if (result == UpdateChecker.UpdateResult.UP_TO_DATE) {
                Toast.makeText(this, R.string.no_update_available, Toast.LENGTH_SHORT).show();
            } else if (result == UpdateChecker.UpdateResult.FAILED) {
                Toast.makeText(this, R.string.check_for_updates_failed, Toast.LENGTH_LONG).show();
            }
        });
    }
}
