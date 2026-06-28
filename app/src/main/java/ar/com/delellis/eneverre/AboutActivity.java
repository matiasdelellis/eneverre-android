package ar.com.delellis.eneverre;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import ar.com.delellis.eneverre.util.UpdateChecker;

public class AboutActivity extends AppCompatActivity {

    private MaterialButton checkUpdatesButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        MaterialToolbar toolbar = findViewById(R.id.about_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView version = findViewById(R.id.about_version);
        version.setText(getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        MaterialButton sourceButton = findViewById(R.id.about_source_button);
        sourceButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.source_code_url)));
            startActivity(intent);
        });

        checkUpdatesButton = findViewById(R.id.about_check_updates_button);
        checkUpdatesButton.setOnClickListener(v -> {
            checkUpdatesButton.setEnabled(false);
            UpdateChecker.checkForUpdate(this, true, result -> {
                checkUpdatesButton.setEnabled(true);
                if (result == UpdateChecker.UpdateResult.UP_TO_DATE) {
                    Snackbar.make(checkUpdatesButton, R.string.no_update_available, Snackbar.LENGTH_SHORT).show();
                } else if (result == UpdateChecker.UpdateResult.FAILED) {
                    Snackbar.make(checkUpdatesButton, R.string.check_for_updates_failed, Snackbar.LENGTH_LONG).show();
                }
            });
        });
    }
}
