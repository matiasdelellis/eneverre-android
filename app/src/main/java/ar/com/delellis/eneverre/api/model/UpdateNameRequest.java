package ar.com.delellis.eneverre.api.model;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

/** Body of {@code PUT /api/users/{username}/name} (admin). Both fields nullable. */
public class UpdateNameRequest {
    @Expose
    @SerializedName("first_name")
    private final String firstName;

    @Expose
    @SerializedName("last_name")
    private final String lastName;

    public UpdateNameRequest(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }
}
