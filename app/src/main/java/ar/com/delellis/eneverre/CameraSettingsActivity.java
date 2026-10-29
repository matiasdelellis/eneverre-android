package ar.com.delellis.eneverre;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.api.model.Camera;
import ar.com.delellis.eneverre.api.model.CameraConfig;
import ar.com.delellis.eneverre.api.model.CameraSettings;
import ar.com.delellis.eneverre.api.model.PtzPosition;
import ar.com.delellis.eneverre.api.model.SetCameraSettingsRequest;
import ar.com.delellis.eneverre.util.ApiCallback;

/**
 * Live-settings editor for one camera (admin only): day/night mode, the
 * illuminators (IR cut, IR850, IR940, white light), audio in/out and motion
 * detection. Backed by {@code GET/PUT /api/camera/{id}/settings}, which the
 * server only serves for cameras advertising {@code capabilities.settings}.
 *
 * <p>Every control PUTs its own change and then re-reads the snapshot, so the
 * screen repaints with camera-confirmed values (the server refreshes the
 * cached state on a successful update). The day/night selector works like the
 * web UI: the snapshot cannot tell "manual" apart from a fixed mode, so only
 * the selector remembers the operator chose manual — and the illuminator
 * switches are only shown then, since they are the operator's business once
 * the photosensor is out of the way.
 */
public class CameraSettingsActivity extends AppCompatActivity {

    /** The {@link Camera} whose settings are edited. */
    public static final String EXTRA_CAMERA = "camera";

    private static final String MODE_AUTO = "auto";
    private static final String MODE_DAY = "day";
    private static final String MODE_NIGHT = "night";
    private static final String MODE_MANUAL = "manual";

    @Nullable
    private Camera camera;

    private ProgressBar progressBar;
    private View errorBox;
    private TextView errorText;
    private MaterialButton retryButton;
    private NestedScrollView scroll;
    private TextView updatedText;
    private MaterialButtonToggleGroup daynightGroup;
    private View lightSection;

    private final Map<String, Integer> modeButtonIds = new HashMap<>();

    /**
     * The selector's client-side position. Starts from the snapshot but
     * persists across repaints, because the snapshot cannot tell "manual"
     * apart from a fixed day/night mode — only the selector knows the
     * operator chose manual.
     */
    @Nullable
    private String uiMode;

    /** The last painted snapshot, used to restore the selector on a failed change. */
    @Nullable
    private CameraSettings snapshot;

    /** Guards against {@link #applySnapshot} tripping the listeners it sets. */
    private boolean applyingSnapshot = false;

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

        Object extra = getIntent().getSerializableExtra(EXTRA_CAMERA);
        if (!(extra instanceof Camera)) {
            finish();
            return;
        }
        camera = (Camera) extra;

        setContentView(R.layout.activity_camera_settings);

        Toolbar toolbar = findViewById(R.id.camera_settings_toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        progressBar = findViewById(R.id.camera_settings_progress);
        errorBox = findViewById(R.id.camera_settings_error_box);
        errorText = findViewById(R.id.camera_settings_error);
        retryButton = findViewById(R.id.camera_settings_retry);
        scroll = findViewById(R.id.camera_settings_scroll);
        updatedText = findViewById(R.id.camera_settings_updated);
        lightSection = findViewById(R.id.camera_settings_light_section);

        daynightGroup = findViewById(R.id.camera_settings_daynight_group);
        modeButtonIds.put(MODE_AUTO, R.id.daynight_auto);
        modeButtonIds.put(MODE_DAY, R.id.daynight_day);
        modeButtonIds.put(MODE_NIGHT, R.id.daynight_night);
        modeButtonIds.put(MODE_MANUAL, R.id.daynight_manual);

        daynightGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked || applyingSnapshot) {
                return;
            }
            onModeSelected(modeFor(checkedId));
        });

        setupToggle(R.id.audio_mic, SetCameraSettingsRequest::mic);
        setupToggle(R.id.audio_speaker, SetCameraSettingsRequest::speaker);
        setupToggle(R.id.motion_enabled, SetCameraSettingsRequest::motion);
        setupToggle(R.id.light_ircut, SetCameraSettingsRequest::ircut);
        setupToggle(R.id.light_ir850, SetCameraSettingsRequest::ir850);
        setupToggle(R.id.light_ir940, SetCameraSettingsRequest::ir940);
        setupToggle(R.id.light_white, SetCameraSettingsRequest::white);

        setupPtzSection();

        retryButton.setOnClickListener(v -> loadSettings());
    }

    /**
     * PTZ maintenance actions: recalibrate the gimbal, or record the camera's
     * current position as the configured Home or Privacy position. The latter
     * two reuse the camera-config endpoints (GET/PUT /api/camera/{id} with the
     * home/privacy coords), exactly like the web frontend's camera dialog: the
     * current position is read in degrees, patched into the stored config and
     * PUT back. Only offered for PTZ cameras; recalibration also needs a
     * thingino backend, but the server enforces that anyway.
     */
    private void setupPtzSection() {
        if (camera == null || !camera.getPtz()) {
            return;
        }
        findViewById(R.id.camera_settings_ptz_section).setVisibility(VISIBLE);

        findViewById(R.id.ptz_recalibrate_button).setOnClickListener(v ->
                ApiClient.getApiService().recalibrate(camera.getId()).enqueue(commandCallback()));

        findViewById(R.id.ptz_set_home_button).setOnClickListener(v -> saveCurrentPositionAs(false));
        findViewById(R.id.ptz_set_privacy_button).setOnClickListener(v -> saveCurrentPositionAs(true));
    }

    /** Saves the camera's current position as home (privacy=false) or privacy (privacy=true). */
    private void saveCurrentPositionAs(boolean privacy) {
        if (camera == null) {
            return;
        }
        ApiClient.getApiService().ptzPosition(camera.getId())
                .enqueue(new ApiCallback<PtzPosition>(this) {
                    @Override
                    public void onSuccess(PtzPosition position) {
                        if (position == null || position.getPan() == null || position.getTilt() == null) {
                            Toast.makeText(CameraSettingsActivity.this, R.string.error_ptz_position, Toast.LENGTH_LONG).show();
                            return;
                        }
                        patchConfigAndSave(position, privacy);
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        Toast.makeText(CameraSettingsActivity.this, R.string.error_ptz_position, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void patchConfigAndSave(PtzPosition position, boolean privacy) {
        ApiClient.getApiService().cameraConfig(camera.getId())
                .enqueue(new ApiCallback<CameraConfig>(this) {
                    @Override
                    public void onSuccess(CameraConfig config) {
                        // One decimal keeps the stored value tidy and mirrors the
                        // rounding the wizard applies when prefilling these fields.
                        double pan = Math.round(position.getPan() * 10.0) / 10.0;
                        double tilt = Math.round(position.getTilt() * 10.0) / 10.0;
                        if (privacy) {
                            config.setPrivacyX(pan);
                            config.setPrivacyY(tilt);
                        } else {
                            config.setHomeX(pan);
                            config.setHomeY(tilt);
                        }
                        ApiClient.getApiService().updateCamera(camera.getId(), config)
                                .enqueue(new ApiCallback<Void>(CameraSettingsActivity.this) {
                                    @Override
                                    public void onSuccess(Void body) {
                                        int message = privacy ? R.string.ptz_set_privacy_done : R.string.ptz_set_home_done;
                                        Toast.makeText(CameraSettingsActivity.this, message, Toast.LENGTH_SHORT).show();
                                    }

                                    @Override
                                    public void onError(int httpCode, String message) {
                                        Toast.makeText(CameraSettingsActivity.this, R.string.error_save_position, Toast.LENGTH_LONG).show();
                                    }
                                });
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        Toast.makeText(CameraSettingsActivity.this, R.string.error_camera_config, Toast.LENGTH_LONG).show();
                    }
                });
    }

    /** Fire-and-forget command callback: failures surface a localized toast. */
    private ApiCallback<Void> commandCallback() {
        return new ApiCallback<Void>(this) {
            @Override
            public void onSuccess(Void body) {
                // Nothing to do: the command was accepted.
            }
        };
    }

    /** Maps a checked button id back to its mode value. */
    private String modeFor(int buttonId) {
        for (Map.Entry<String, Integer> entry : modeButtonIds.entrySet()) {
            if (entry.getValue() == buttonId) {
                return entry.getKey();
            }
        }
        return MODE_AUTO;
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // --- Snapshot loading ------------------------------------------------------

    private void loadSettings() {
        if (camera == null) {
            return;
        }
        progressBar.setVisibility(VISIBLE);
        errorBox.setVisibility(GONE);

        ApiClient.getApiService().cameraSettings(camera.getId())
                .enqueue(new ApiCallback<CameraSettings>(this) {
                    @Override
                    public void onSuccess(CameraSettings settings) {
                        progressBar.setVisibility(GONE);
                        scroll.setVisibility(VISIBLE);
                        applySnapshot(settings);
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        progressBar.setVisibility(GONE);
                        // 404 = camera without the capability or the server has not
                        // fetched a snapshot yet (e.g. unreachable at startup).
                        if (httpCode == 404) {
                            errorText.setText(R.string.camera_settings_unavailable);
                        } else {
                            errorText.setText(R.string.camera_settings_load_failed);
                        }
                        errorBox.setVisibility(VISIBLE);
                    }
                });
    }

    /** Paints every control from a fresh snapshot, skipping controls mid-PUT. */
    private void applySnapshot(CameraSettings si) {
        snapshot = si;
        applyingSnapshot = true;
        try {
            if (si.getUpdatedAt() > 0) {
                String time = DateFormat.getTimeFormat(this).format(new Date(si.getUpdatedAt() * 1000L));
                updatedText.setText(getString(R.string.camera_settings_updated, time));
            }

            String mode = uiMode != null ? uiMode : modeValue(si);
            Integer buttonId = modeButtonIds.get(mode);
            if (buttonId != null) {
                daynightGroup.check(buttonId);
            }
            lightSection.setVisibility(MODE_MANUAL.equals(mode) ? VISIBLE : GONE);

            setIfIdle(R.id.audio_mic, si.isMicEnabled());
            setIfIdle(R.id.audio_speaker, si.isSpkEnabled());
            setIfIdle(R.id.motion_enabled, si.isMotionEnabled());
            setIfIdle(R.id.light_ircut, si.isIrcut());
            setIfIdle(R.id.light_ir850, si.isIr850());
            setIfIdle(R.id.light_ir940, si.isIr940());
            setIfIdle(R.id.light_white, si.isWhite());
        } finally {
            applyingSnapshot = false;
        }
    }

    /** The selector position derived from the snapshot: Auto when the photosensor is on, else the fixed mode. */
    private String modeValue(CameraSettings si) {
        if (si.isDaynightEnabled()) {
            return MODE_AUTO;
        }
        return MODE_NIGHT.equals(si.getDaynightMode()) ? MODE_NIGHT : MODE_DAY;
    }

    /** Sets a switch only when it is not mid-PUT (disabled by the user's tap). */
    private void setIfIdle(int switchId, boolean checked) {
        MaterialSwitch toggle = findViewById(switchId);
        if (toggle.isEnabled()) {
            toggle.setChecked(checked);
        }
    }

    // --- Updates ---------------------------------------------------------------

    private interface ToggleRequest {
        SetCameraSettingsRequest build(boolean checked);
    }

    /**
     * Wires one switch so a user tap PUTs the flipped field and repaints from
     * the snapshot; a failed PUT reverts the switch.
     */
    private void setupToggle(int switchId, ToggleRequest requestBuilder) {
        MaterialSwitch toggle = findViewById(switchId);
        toggle.setOnCheckedChangeListener((view, checked) -> {
            if (applyingSnapshot) {
                return;
            }
            toggle.setEnabled(false);
            sendUpdate(requestBuilder.build(checked), () -> {
                // Accepted: re-enable so the snapshot repaint can repaint this
                // switch with the camera-confirmed value.
                toggle.setEnabled(true);
                loadSettings();
            }, () -> {
                // Failed: revert to the previous (server-confirmed) state.
                applyingSnapshot = true;
                toggle.setChecked(!checked);
                applyingSnapshot = false;
                toggle.setEnabled(true);
            });
        });
    }

    private void onModeSelected(String mode) {
        SetCameraSettingsRequest request = MODE_AUTO.equals(mode)
                ? SetCameraSettingsRequest.daynightAuto()
                : SetCameraSettingsRequest.daynightMode(mode);
        setGroupEnabled(false);
        sendUpdate(request, () -> {
            // Stick to the operator's choice across the snapshot repaint: the
            // snapshot reports manual as a fixed mode.
            uiMode = mode;
            setGroupEnabled(true);
            loadSettings();
        }, () -> {
            uiMode = null;
            setGroupEnabled(true);
            if (snapshot != null) {
                applySnapshot(snapshot);
            }
        });
    }

    private void setGroupEnabled(boolean enabled) {
        for (int buttonId : modeButtonIds.values()) {
            MaterialButton button = findViewById(buttonId);
            button.setEnabled(enabled);
        }
    }

    private void sendUpdate(SetCameraSettingsRequest request, Runnable onDone, Runnable onFailed) {
        if (camera == null) {
            return;
        }
        ApiClient.getApiService().setCameraSettings(camera.getId(), request)
                .enqueue(new ApiCallback<Void>(this) {
                    @Override
                    public void onSuccess(Void body) {
                        onDone.run();
                    }

                    @Override
                    public void onError(int httpCode, String message) {
                        Toast.makeText(CameraSettingsActivity.this,
                                R.string.camera_settings_update_failed, Toast.LENGTH_LONG).show();
                        onFailed.run();
                    }
                });
    }
}
