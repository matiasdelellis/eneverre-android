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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ar.com.delellis.eneverre.adapter.SessionsAdapter;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Session;
import ar.com.delellis.eneverre.api.model.SessionsResponse;
import ar.com.delellis.eneverre.util.ApiCallback;

/**
 * "My sessions" screen: lists the user's active login sessions and lets them
 * revoke any device other than the current one (see {@code GET/DELETE
 * /api/users/me/sessions}).
 */
public class SessionsActivity extends AppCompatActivity implements SessionsAdapter.OnRevokeListener {

    private final List<Session> sessions = new ArrayList<>();
    private SessionsAdapter adapter;

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

        setContentView(R.layout.activity_sessions);

        Toolbar toolbar = findViewById(R.id.sessions_toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        list = findViewById(R.id.sessions_list);
        emptyView = findViewById(R.id.sessions_empty);
        progressBar = findViewById(R.id.sessions_progress);

        adapter = new SessionsAdapter(this, sessions, this);
        list.setAdapter(adapter);

        loadSessions();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadSessions() {
        progressBar.setVisibility(VISIBLE);
        emptyView.setVisibility(GONE);

        ApiClient.getApiService().sessions().enqueue(new ApiCallback<SessionsResponse>(this) {
            @Override
            public void onSuccess(SessionsResponse body) {
                progressBar.setVisibility(GONE);
                sessions.clear();
                if (body != null && body.getActive() != null) {
                    sessions.addAll(body.getActive());
                }
                // Keep the current device pinned to the top.
                Collections.sort(sessions, (a, b) -> Boolean.compare(b.isCurrent(), a.isCurrent()));
                adapter.notifyDataSetChanged();
                updateEmptyState();
            }

            @Override
            public void onError(int httpCode, String message) {
                progressBar.setVisibility(GONE);
                updateEmptyState();
                Toast.makeText(SessionsActivity.this, R.string.error_sessions, Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onRevoke(Session session) {
        String name = session.getDeviceName() != null && !session.getDeviceName().trim().isEmpty()
                ? session.getDeviceName().trim()
                : getString(R.string.session_unknown_device);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.session_revoke_confirm_title)
                .setMessage(getString(R.string.session_revoke_confirm_message, name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.session_revoke, (dialog, which) -> revokeSession(session))
                .show();
    }

    private void revokeSession(Session session) {
        ApiClient.getApiService().revokeSession(session.getId()).enqueue(new ApiCallback<Void>(this) {
            @Override
            public void onSuccess(Void body) {
                int index = indexOfSession(session.getId());
                if (index >= 0) {
                    sessions.remove(index);
                    adapter.notifyItemRemoved(index);
                }
                updateEmptyState();
                Toast.makeText(SessionsActivity.this, R.string.session_revoked, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(int httpCode, String message) {
                Toast.makeText(SessionsActivity.this, R.string.error_revoke_session, Toast.LENGTH_LONG).show();
            }
        });
    }

    private int indexOfSession(long id) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).getId() == id) {
                return i;
            }
        }
        return -1;
    }

    /** Shows the empty message only when the list is truly empty (not while loading). */
    private void updateEmptyState() {
        emptyView.setVisibility(sessions.isEmpty() ? VISIBLE : GONE);
    }
}
