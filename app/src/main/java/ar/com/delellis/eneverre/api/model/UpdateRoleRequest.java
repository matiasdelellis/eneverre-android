package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * Body of {@code PUT /api/users/{username}/role} (admin). {@code role} is
 * {@code "admin"} or {@code "user"}; the server rejects demoting the last admin.
 */
public class UpdateRoleRequest {
    @Expose
    @SerializedName("role")
    private final String role;

    public UpdateRoleRequest(String role) {
        this.role = role;
    }
}
