package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.CreateUserRequest;
import ar.com.delellis.eneverre.api.model.Role;
import ar.com.delellis.eneverre.api.model.UpdateNameRequest;
import ar.com.delellis.eneverre.api.model.UpdatePasswordRequest;
import ar.com.delellis.eneverre.api.model.UpdateRoleRequest;
import ar.com.delellis.eneverre.api.model.User;
import ar.com.delellis.eneverre.api.model.UserCameras;
import ar.com.delellis.eneverre.util.ApiCallback;
import ar.com.delellis.eneverre.util.RoleLabels;

/**
 * Create or edit a single account (admin only). Launched with no extra for a
 * new user, or with {@link #EXTRA_USER} to edit an existing one.
 *
 * <p>In edit mode the username is read-only, "Save" persists only the name/role
 * fields that actually changed (chained sequentially), and password reset and
 * deletion are separate destructive actions. The last-admin protections
 * (demote/delete) are enforced by the server and surfaced here as a 400.
 */
public class UserEditActivity extends AppCompatActivity {

    /** Optional {@link User} extra; present = edit mode, absent = create mode. */
    public static final String EXTRA_USER = "user";

    @Nullable
    private User user;

    private TextInputLayout usernameLayout;
    private TextInputEditText usernameInput;
    private TextInputLayout passwordLayout;
    private TextInputEditText passwordInput;
    private TextInputEditText firstNameInput;
    private TextInputEditText lastNameInput;
    private MaterialAutoCompleteTextView roleInput;
    /** Currently selected role; the dropdown shows its localized label. */
    private String selectedRole = Role.USER;
    private MaterialCheckBox mustChangeCheck;
    private MaterialButton saveButton;
    private MaterialButton resetPasswordButton;
    private MaterialButton deleteButton;
    private MaterialButton camerasButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Recreated cold (process death): bounce through the splash to re-init the client.
        try {
            ApiClient.getInstance();
        } catch (IllegalStateException e) {
            startActivity(new Intent(this, SplashActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_user_edit);

        Object extra = getIntent().getSerializableExtra(EXTRA_USER);
        if (extra instanceof User) {
            user = (User) extra;
        }

        Toolbar toolbar = findViewById(R.id.user_edit_toolbar);
        toolbar.setTitle(user == null ? R.string.create_user : R.string.edit_user);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        usernameLayout = findViewById(R.id.username_layout);
        usernameInput = findViewById(R.id.username_input);
        passwordLayout = findViewById(R.id.password_layout);
        passwordInput = findViewById(R.id.password_input);
        firstNameInput = findViewById(R.id.first_name_input);
        lastNameInput = findViewById(R.id.last_name_input);
        roleInput = findViewById(R.id.role_input);
        mustChangeCheck = findViewById(R.id.must_change_password_check);
        saveButton = findViewById(R.id.save_button);
        resetPasswordButton = findViewById(R.id.reset_password_button);
        deleteButton = findViewById(R.id.delete_button);
        camerasButton = findViewById(R.id.user_cameras_button);

        setupRoleInput();

        if (user == null) {
            bindCreateMode();
        } else {
            bindEditMode();
        }

        saveButton.setOnClickListener(v -> onSave());
    }

    private void bindCreateMode() {
        // Defaults: fresh accounts must change their password on first login.
        mustChangeCheck.setChecked(true);
        resetPasswordButton.setVisibility(GONE);
        deleteButton.setVisibility(GONE);
        // A camera ACL only makes sense once the account exists (and is not an
        // admin, who bypasses the ACL).
        camerasButton.setVisibility(GONE);
        setRole(Role.USER);
    }

    private void bindEditMode() {
        usernameInput.setText(user.getUsername());
        usernameLayout.setEnabled(false);

        // Password is reset through its own action, not the main form.
        passwordLayout.setVisibility(GONE);
        // "Must change password" only applies when setting a password (create /
        // reset); it has no meaning for a plain name/role save.
        mustChangeCheck.setVisibility(GONE);

        firstNameInput.setText(user.getFirstName());
        lastNameInput.setText(user.getLastName());
        setRole(user.getRole());

        camerasButton.setOnClickListener(v -> showCameraAclDialog());
        updateCamerasButtonVisibility();

        resetPasswordButton.setVisibility(VISIBLE);
        resetPasswordButton.setOnClickListener(v -> showResetPasswordDialog());
        deleteButton.setVisibility(VISIBLE);
        deleteButton.setOnClickListener(v -> confirmDelete());
    }

    /** Fills the role dropdown with the four fixed roles. */
    private void setupRoleInput() {
        String[] labels = new String[Role.ALL.length];
        for (int i = 0; i < Role.ALL.length; i++) {
            labels[i] = getString(RoleLabels.labelRes(Role.ALL[i]));
        }
        roleInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        roleInput.setOnItemClickListener((parent, view, position, id) -> {
            selectedRole = Role.ALL[position];
            updateCamerasButtonVisibility();
        });
    }

    /**
     * Selects a role in the dropdown. A server that predates roles only reports
     * the admin flag, so fall back to it rather than silently demoting an admin.
     */
    private void setRole(@Nullable String role) {
        if (role == null) {
            role = user != null && user.isAdmin() ? Role.ADMIN : Role.USER;
        }
        selectedRole = role;
        roleInput.setText(getString(RoleLabels.labelRes(role)), false);
    }

    /** The ACL only applies to non-admin accounts: admin bypasses it. */
    private void updateCamerasButtonVisibility() {
        boolean show = user != null && !Role.isAdmin(selectedRole);
        camerasButton.setVisibility(show ? VISIBLE : GONE);
    }

    private void onSave() {
        if (user == null) {
            createUser();
        } else {
            saveEdits();
        }
    }

    // --- Create ---------------------------------------------------------------

    private void createUser() {
        usernameLayout.setError(null);
        passwordLayout.setError(null);

        String username = text(usernameInput);
        String password = text(passwordInput);

        if (username.isEmpty()) {
            usernameLayout.setError(getString(R.string.error_username_required));
            return;
        }
        // Mirror the server's cleanUsername: no whitespace, at most 64 chars.
        if (username.length() > 64 || username.matches(".*\\s.*")) {
            usernameLayout.setError(getString(R.string.error_username_invalid));
            return;
        }
        if (password.isEmpty()) {
            passwordLayout.setError(getString(R.string.error_password_empty));
            return;
        }

        CreateUserRequest request = new CreateUserRequest(
                username, password, role(),
                nullIfEmpty(text(firstNameInput)), nullIfEmpty(text(lastNameInput)),
                mustChangeCheck.isChecked());

        saveButton.setEnabled(false);
        ApiClient.getApiService().createUser(request).enqueue(new ApiCallback<Void>(this) {
            @Override
            public void onSuccess(Void body) {
                Toast.makeText(UserEditActivity.this, R.string.user_created, Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onError(int httpCode, String message) {
                saveButton.setEnabled(true);
                // The only documented 400 here is a duplicate username.
                int msg = httpCode == 400 ? R.string.error_user_exists : R.string.error_create_user;
                Toast.makeText(UserEditActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    // --- Edit -----------------------------------------------------------------

    private void saveEdits() {
        boolean nameChanged = !equalsNorm(user.getFirstName(), text(firstNameInput))
                || !equalsNorm(user.getLastName(), text(lastNameInput));
        boolean roleChanged = !TextUtils.equals(user.getRole(), selectedRole);

        if (!nameChanged && !roleChanged) {
            finish();
            return;
        }

        saveButton.setEnabled(false);
        if (nameChanged) {
            UpdateNameRequest request = new UpdateNameRequest(
                    nullIfEmpty(text(firstNameInput)), nullIfEmpty(text(lastNameInput)));
            ApiClient.getApiService().updateUserName(user.getUsername(), request)
                    .enqueue(new ApiCallback<Void>(this) {
                        @Override
                        public void onSuccess(Void body) {
                            // Chain the role update (if any) so both land before finishing.
                            if (roleChanged) {
                                updateRole();
                            } else {
                                finishSaved();
                            }
                        }

                        @Override
                        public void onError(int httpCode, String message) {
                            saveButton.setEnabled(true);
                            Toast.makeText(UserEditActivity.this, R.string.error_update_user, Toast.LENGTH_LONG).show();
                        }
                    });
        } else {
            updateRole();
        }
    }

    private void updateRole() {
        UpdateRoleRequest request = new UpdateRoleRequest(role());
        ApiClient.getApiService().updateUserRole(user.getUsername(), request)
                .enqueue(new ApiCallback<Void>(this) {
                    @Override
                    public void onSuccess(Void body) {
                        finishSaved();
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        saveButton.setEnabled(true);
                        // 400 here means the server blocked demoting the last admin.
                        int msg = httpCode == 400 ? R.string.error_last_admin : R.string.error_update_user;
                        Toast.makeText(UserEditActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void finishSaved() {
        Toast.makeText(this, R.string.user_updated, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showResetPasswordDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_reset_password, null, false);
        TextInputLayout layout = content.findViewById(R.id.reset_password_layout);
        TextInputEditText input = content.findViewById(R.id.reset_password_input);
        MaterialCheckBox mustChange = content.findViewById(R.id.reset_must_change_check);
        mustChange.setChecked(true);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_password)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.show();
        // Override the positive button so a validation failure keeps the dialog open.
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String password = input.getText() == null ? "" : input.getText().toString();
            if (TextUtils.isEmpty(password)) {
                layout.setError(getString(R.string.error_password_empty));
                return;
            }
            dialog.dismiss();
            resetPassword(password, mustChange.isChecked());
        });
    }

    private void resetPassword(String password, boolean mustChange) {
        UpdatePasswordRequest request = new UpdatePasswordRequest(password, mustChange);
        ApiClient.getApiService().updateUserPassword(user.getUsername(), request)
                .enqueue(new ApiCallback<Void>(this) {
                    @Override
                    public void onSuccess(Void body) {
                        Toast.makeText(UserEditActivity.this, R.string.password_reset_done, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        Toast.makeText(UserEditActivity.this, R.string.error_reset_password, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_user_confirm_title)
                .setMessage(getString(R.string.delete_user_confirm_message, user.getUsername()))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete_user, (dialog, which) -> deleteUser())
                .show();
    }

    private void deleteUser() {
        ApiClient.getApiService().deleteUser(user.getUsername()).enqueue(new ApiCallback<Void>(this) {
            @Override
            public void onSuccess(Void body) {
                Toast.makeText(UserEditActivity.this, R.string.user_deleted, Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onError(int httpCode, String message) {
                // 400 here means the server blocked deleting the last admin.
                int msg = httpCode == 400 ? R.string.error_last_admin : R.string.error_delete_user;
                Toast.makeText(UserEditActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    // --- Camera ACL -----------------------------------------------------------

    /**
     * Loads the account's ACL and the camera list, then offers a multi-choice
     * dialog. "Save" restricts the account to the checked cameras; "All cameras"
     * removes the restriction (so a camera added later is included
     * automatically).
     */
    private void showCameraAclDialog() {
        camerasButton.setEnabled(false);
        ApiClient.getApiService().userCameras(user.getUsername())
                .enqueue(new ApiCallback<UserCameras>(this) {
                    @Override
                    public void onSuccess(UserCameras body) {
                        if (body == null) {
                            camerasButton.setEnabled(true);
                            Toast.makeText(UserEditActivity.this,
                                    R.string.error_user_cameras, Toast.LENGTH_LONG).show();
                            return;
                        }
                        loadCamerasForAcl(body);
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        camerasButton.setEnabled(true);
                        Toast.makeText(UserEditActivity.this,
                                R.string.error_user_cameras, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void loadCamerasForAcl(UserCameras acl) {
        ApiClient.getApiService().cameras().enqueue(new ApiCallback<List<Camera>>(this) {
            @Override
            public void onSuccess(List<Camera> cameras) {
                camerasButton.setEnabled(true);
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                showAclDialog(acl, cameras != null ? cameras : new ArrayList<>());
            }

            @Override
            public void onError(int httpCode, String message) {
                camerasButton.setEnabled(true);
                Toast.makeText(UserEditActivity.this,
                        R.string.error_user_cameras, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showAclDialog(UserCameras acl, List<Camera> cameras) {
        // Deliberately no setMessage(): AppCompat gives the message and the
        // multi-choice list the same content panel, and with both set the list
        // renders empty. The buttons below are self-explanatory.
        String[] labels = new String[cameras.size()];
        boolean[] checked = new boolean[cameras.size()];
        List<String> allowed = acl.getCameraIds();
        for (int i = 0; i < cameras.size(); i++) {
            Camera camera = cameras.get(i);
            labels[i] = camera.getName();
            // An unrestricted account may use every camera.
            checked[i] = !acl.isRestricted() || allowed.contains(camera.getId());
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.user_cameras)
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    List<String> ids = new ArrayList<>();
                    for (int i = 0; i < cameras.size(); i++) {
                        if (checked[i]) {
                            ids.add(cameras.get(i).getId());
                        }
                    }
                    saveAcl(true, ids);
                })
                .setNeutralButton(R.string.user_cameras_all,
                        (dialog, which) -> saveAcl(false, null))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void saveAcl(boolean restricted, @Nullable List<String> cameraIds) {
        UserCameras request = new UserCameras(restricted, cameraIds);
        ApiClient.getApiService().setUserCameras(user.getUsername(), request)
                .enqueue(new ApiCallback<UserCameras>(this) {
                    @Override
                    public void onSuccess(UserCameras body) {
                        Toast.makeText(UserEditActivity.this,
                                R.string.user_cameras_saved, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        Toast.makeText(UserEditActivity.this,
                                R.string.error_save_user_cameras, Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // --- helpers --------------------------------------------------------------

    private String role() {
        return selectedRole;
    }

    private static String text(TextInputEditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    @Nullable
    private static String nullIfEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Treats {@code null} and blank as equal, so a never-set name isn't "changed" into an empty one. */
    private static boolean equalsNorm(@Nullable String stored, String input) {
        String a = stored == null ? "" : stored.trim();
        return a.equals(input.trim());
    }
}
