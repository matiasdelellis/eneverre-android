package ar.com.delellis.eneverre.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ar.com.delellis.eneverre.R;
import ar.com.delellis.eneverre.api.model.User;

/** Lists every account; tapping a row opens it for editing. */
public class UsersAdapter extends RecyclerView.Adapter<UsersAdapter.UserViewHolder> {
    private final Context context;
    private final List<User> users;
    private final OnUserClickListener listener;

    public interface OnUserClickListener {
        void onUserClick(User user);
    }

    public UsersAdapter(Context context, List<User> users, OnUserClickListener listener) {
        this.context = context;
        this.users = users;
        this.listener = listener;
    }

    @NonNull
    @Override
    public UserViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_user, parent, false);
        return new UserViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull UserViewHolder holder, int position) {
        User user = users.get(position);

        holder.name.setText(user.getUsername());

        String role = context.getString(user.isAdmin()
                ? R.string.user_role_admin
                : R.string.user_role_user);
        String fullName = user.getFullName();
        holder.subtitle.setText(fullName.isEmpty()
                ? role
                : context.getString(R.string.user_subtitle, fullName, role));

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onUserClick(user);
            }
        });
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    static class UserViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView subtitle;

        UserViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.user_name);
            subtitle = itemView.findViewById(R.id.user_subtitle);
        }
    }
}
