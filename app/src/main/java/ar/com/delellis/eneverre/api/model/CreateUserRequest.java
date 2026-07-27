package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/**
 * Body of {@code POST /api/users} (admin only). {@code role} may be left empty
 * (the server defaults it to {@code "user"}); {@code first_name}/{@code last_name}
 * are nullable.
 */
public class CreateUserRequest {
    @Expose
    @SerializedName("username")
    private final String username;

    @Expose
    @SerializedName("password")
    private final String password;

    @Expose
    @SerializedName("role")
    private final String role;

    @Expose
    @SerializedName("first_name")
    private final String firstName;

    @Expose
    @SerializedName("last_name")
    private final String lastName;

    @Expose
    @SerializedName("must_change_password")
    private final boolean mustChangePassword;

    public CreateUserRequest(String username, String password, String role,
                             String firstName, String lastName, boolean mustChangePassword) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.firstName = firstName;
        this.lastName = lastName;
        this.mustChangePassword = mustChangePassword;
    }
}
