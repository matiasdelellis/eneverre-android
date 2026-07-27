package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * Body of {@code PUT /api/users/{username}/password} (admin resetting another
 * account's password). Distinct from {@link ChangePasswordRequest}, which is the
 * self-service flow ({@code users/me/password}) carrying the current password.
 * Setting {@code must_change_password} forces the target user through the
 * change-password flow on their next login. The server revokes the target's
 * existing sessions.
 */
public class UpdatePasswordRequest {
    @Expose
    @SerializedName("password")
    private final String password;

    @Expose
    @SerializedName("must_change_password")
    private final boolean mustChangePassword;

    public UpdatePasswordRequest(String password, boolean mustChangePassword) {
        this.password = password;
        this.mustChangePassword = mustChangePassword;
    }
}
