package ar.com.delellis.eneverre;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class AboutActivity extends AppCompatActivity {

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
    }
}
