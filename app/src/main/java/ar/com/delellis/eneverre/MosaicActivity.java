package ar.com.delellis.eneverre;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.viewpager2.widget.ViewPager2;

import org.videolan.libvlc.LibVLC;

import ar.com.delellis.eneverre.adapter.MosaicPagerAdapter;
import ar.com.delellis.eneverre.api.ApiClient;
import ar.com.delellis.eneverre.model.Locations;
import ar.com.delellis.eneverre.player.VlcPlayer;

/**
 * Hosts a {@link ViewPager2} of {@link MosaicFragment}s — one live camera grid
 * per location — so the mosaic can be swiped from one location to the next
 * instead of going back to the camera list to pick another one.
 *
 * The activity owns the single shared {@link LibVLC} engine every page's cells
 * play on (see {@link MosaicFragment.SharedLibVlcProvider}) and releases it once
 * in {@link #onDestroy}. Which streams run is up to the fragments: the pager
 * caps every page but the current one to {@code STARTED} and each fragment
 * streams only while resumed, so exactly one location streams at a time.
 */
public class MosaicActivity extends AppCompatActivity implements MosaicFragment.SharedLibVlcProvider {

    private Locations locations;
    private LibVLC libVlc;
    private ViewPager2 viewPager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Recreated cold (process death): the in-memory ApiClient singleton is
        // gone. Bounce through the splash to re-init it, matching ViewActivity.
        try {
            ApiClient.getInstance();
        } catch (IllegalStateException e) {
            startActivity(new Intent(this, SplashActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_mosaic);

        Toolbar toolbar = findViewById(R.id.mosaic_toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        Intent intent = getIntent();
        locations = (Locations) intent.getSerializableExtra(CamerasActivity.ALL_LOCATIONS_DATA);
        int selected = intent.getIntExtra(CamerasActivity.SELECTED_LOCATION_DATA, 0);

        viewPager = findViewById(R.id.mosaic_pager);
        TextView emptyView = findViewById(R.id.mosaic_empty);

        if (locations == null || locations.count() == 0) {
            emptyView.setVisibility(View.VISIBLE);
            viewPager.setVisibility(View.GONE);
            return;
        }

        libVlc = VlcPlayer.newLibVlc(this);

        viewPager.setAdapter(new MosaicPagerAdapter(this, locations));
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                updateTitle(position);
            }
        });

        if (selected > 0 && selected < locations.count()) {
            viewPager.setCurrentItem(selected, false);
        }
        // onPageSelected is not guaranteed to fire for the initial page.
        updateTitle(viewPager.getCurrentItem());

        // Hide the toolbar in landscape so the grid uses the full screen, applied
        // now too (onConfigurationChanged only fires on a later rotation), matching
        // ViewActivity.
        updateToolbarForOrientation(getResources().getConfiguration().orientation);
    }

    /** Shows the paged location's name. */
    private void updateTitle(int position) {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(locations.get(position).getName());
        }
    }

    /** Hides the toolbar in landscape to give the grid the whole screen. */
    private void updateToolbarForOrientation(int orientation) {
        boolean landscape = orientation == Configuration.ORIENTATION_LANDSCAPE;
        findViewById(R.id.app_bar).setVisibility(landscape ? View.GONE : View.VISIBLE);
    }

    @Override
    public LibVLC getSharedLibVlc() {
        return libVlc;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Every page's cells were already torn down when it paused; free the
        // shared engine last.
        if (libVlc != null) {
            libVlc.release();
            libVlc = null;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The grids re-lay out themselves (MosaicFragment.onConfigurationChanged).
        updateToolbarForOrientation(newConfig.orientation);
    }
}
