package ar.com.delellis.eneverre.util;

import androidx.annotation.StringRes;

import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.api.model.Role;

/** Localized labels for the server's fixed roles. */
public final class RoleLabels {

    private RoleLabels() {
    }

    @StringRes
    public static int labelRes(String role) {
        if (Role.ADMIN.equals(role)) {
            return R.string.user_role_admin;
        }
        if (Role.VIEWER.equals(role)) {
            return R.string.user_role_viewer;
        }
        if (Role.MONITOR.equals(role)) {
            return R.string.user_role_monitor;
        }
        return R.string.user_role_user;
    }
}
