package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.io.Serializable;

/**
 * One account as returned by {@code GET /api/users} (admin only). The backend
 * user model is intentionally small — {@code username} is the primary key
 * (there is no numeric id or email), and admin is derived from
 * {@code role == "admin"}. Serializable so it can be handed to
 * {@code UserEditActivity} as an intent extra.
 */
public class User implements Serializable {
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";

    @Expose
    @SerializedName("username")
    private String username;

    @Expose
    @SerializedName("role")
    private String role;

    @Expose
    @SerializedName("first_name")
    private String firstName;

    @Expose
    @SerializedName("last_name")
    private String lastName;

    @Expose
    @SerializedName("must_change_password")
    private boolean mustChangePassword;

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    /** Whether this account has the admin role. */
    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    /**
     * "First Last" trimmed, or the empty string when neither name is set.
     * Callers decide the fallback (usually the username).
     */
    public String getFullName() {
        StringBuilder sb = new StringBuilder();
        if (firstName != null && !firstName.trim().isEmpty()) {
            sb.append(firstName.trim());
        }
        if (lastName != null && !lastName.trim().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(lastName.trim());
        }
        return sb.toString();
    }
}
