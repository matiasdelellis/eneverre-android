package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ar.com.delellis.eneverre.adapter.UsersAdapter;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.User;
import ar.com.delellis.eneverre.util.ApiCallback;

/**
 * "Manage users" screen (admin only): lists every account and opens each for
 * editing; the top button creates a new one. Backed by the admin user API
 * ({@code GET /api/users} and friends). The menu entry that launches this is
 * gated on {@code SecureStore.isAdmin()}, but every endpoint is also enforced
 * server-side, so a non-admin who reaches it simply gets errors.
 */
public class UsersActivity extends AppCompatActivity implements UsersAdapter.OnUserClickListener {

    private final List<User> users = new ArrayList<>();
    private UsersAdapter adapter;

    private RecyclerView list;
    private TextView emptyView;
    private ProgressBar progressBar;

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

        setContentView(R.layout.activity_users);

        Toolbar toolbar = findViewById(R.id.users_toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        list = findViewById(R.id.users_list);
        emptyView = findViewById(R.id.users_empty);
        progressBar = findViewById(R.id.users_progress);

        adapter = new UsersAdapter(this, users, this);
        list.setAdapter(adapter);

        MaterialButton addUserButton = findViewById(R.id.add_user_button);
        addUserButton.setOnClickListener(v ->
                startActivity(new Intent(this, UserEditActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload on every resume so changes made in UserEditActivity (create,
        // rename, role change, delete) are reflected when we come back.
        loadUsers();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadUsers() {
        progressBar.setVisibility(VISIBLE);
        emptyView.setVisibility(GONE);

        ApiClient.getApiService().users().enqueue(new ApiCallback<List<User>>(this) {
            @Override
            public void onSuccess(List<User> body) {
                progressBar.setVisibility(GONE);
                users.clear();
                if (body != null) {
                    users.addAll(body);
                }
                Collections.sort(users, (a, b) -> {
                    String ua = a.getUsername() != null ? a.getUsername() : "";
                    String ub = b.getUsername() != null ? b.getUsername() : "";
                    return ua.compareToIgnoreCase(ub);
                });
                adapter.notifyDataSetChanged();
                updateEmptyState();
            }

            @Override
            public void onError(int httpCode, String message) {
                progressBar.setVisibility(GONE);
                updateEmptyState();
                Toast.makeText(UsersActivity.this, R.string.error_users, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onUserClick(User user) {
        Intent intent = new Intent(this, UserEditActivity.class);
        intent.putExtra(UserEditActivity.EXTRA_USER, user);
        startActivity(intent);
    }

    /** Shows the empty message only when the list is truly empty (not while loading). */
    private void updateEmptyState() {
        emptyView.setVisibility(users.isEmpty() ? VISIBLE : GONE);
    }
}
