package ar.com.delellis.eneverre.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.api.model.Session;

/** Lists the current user's login sessions with a per-row "Sign out" action. */
public class SessionsAdapter extends RecyclerView.Adapter<SessionsAdapter.SessionViewHolder> {
    private final Context context;
    private final List<Session> sessions;
    private final OnRevokeListener listener;

    public interface OnRevokeListener {
        void onRevoke(Session session);
    }

    public SessionsAdapter(Context context, List<Session> sessions, OnRevokeListener listener) {
        this.context = context;
        this.sessions = sessions;
        this.listener = listener;
    }

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_session, parent, false);
        return new SessionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        Session session = sessions.get(position);

        String deviceName = session.getDeviceName();
        holder.device.setText(deviceName != null && !deviceName.trim().isEmpty()
                ? deviceName.trim()
                : context.getString(R.string.session_unknown_device));

        if (session.isCurrent()) {
            holder.subtitle.setText(R.string.session_current);
            // Revoking the current session would sign the app out of itself; use
            // the dedicated Log out action for that instead.
            holder.revoke.setVisibility(View.GONE);
        } else {
            String date = DateFormat.getDateInstance(DateFormat.MEDIUM)
                    .format(new Date(session.getCreatedAt() * 1000L));
            holder.subtitle.setText(context.getString(R.string.session_signed_in, date));
            holder.revoke.setVisibility(View.VISIBLE);
            holder.revoke.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onRevoke(session);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {
        final TextView device;
        final TextView subtitle;
        final Button revoke;

        SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            device = itemView.findViewById(R.id.session_device);
            subtitle = itemView.findViewById(R.id.session_subtitle);
            revoke = itemView.findViewById(R.id.session_revoke);
        }
    }
}
