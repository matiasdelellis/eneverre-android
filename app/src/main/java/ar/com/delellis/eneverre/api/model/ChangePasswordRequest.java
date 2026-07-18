package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * Body of {@code PUT /api/users/me/password}. {@code current_password} is optional
 * (the server does not require it when the account is flagged
 * {@code must_change_password}); {@code new_password} is required.
 */
public class ChangePasswordRequest {
    @Expose
    @SerializedName("current_password")
    private final String currentPassword;

    @Expose
    @SerializedName("new_password")
    private final String newPassword;

    public ChangePasswordRequest(String currentPassword, String newPassword) {
        this.currentPassword = currentPassword;
        this.newPassword = newPassword;
    }
}
