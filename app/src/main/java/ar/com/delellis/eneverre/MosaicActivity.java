package ar.com.delellis.eneverre;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
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
import ar.com.delellis.eneverre.util.AppPreferences;

/**
 * Hosts a {@link ViewPager2} of {@link MosaicFragment}s — one live camera grid
 * per location — so the mosaic can be swiped from one location to the next
 * instead of going back to the camera list to pick another one.
 *
 * The activity owns the single shared {@link LibVLC} engine every page's cells
 * play on (see {@link MosaicHost}) and releases it once in
 * {@link #onDestroy}. Which streams run is up to the fragments: the pager caps
 * every page but the current one to {@code STARTED} and each fragment streams
 * only while resumed, so exactly one location streams at a time.
 *
 * The toolbar also switches every page's view mode between the scrolling grid and
 * one that fits the whole location on screen. The choice is remembered in
 * {@link AppPreferences} and pushed to the page currently on screen, the only one
 * registered as a {@link MosaicHost.LayoutModeListener}; the others read the
 * preference when they resume.
 */
public class MosaicActivity extends AppCompatActivity implements MosaicHost {

    private Locations locations;
    private LibVLC libVlc;
    private ViewPager2 viewPager;
    private boolean fitToScreen;
    private MosaicHost.LayoutModeListener layoutModeListener;

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

        fitToScreen = AppPreferences.getInstance(this).isMosaicFitToScreen();
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
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.mosaic_top_app_bar, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.mosaic_view_mode);
        // Nothing to switch when there are no cameras to lay out.
        item.setVisible(locations != null && locations.count() > 0);
        // Show what tapping switches to, not the mode currently on screen.
        item.setIcon(fitToScreen ? R.drawable.ic_view_stream_24 : R.drawable.ic_grid_24);
        item.setTitle(fitToScreen ? R.string.mosaic_scrolling_grid : R.string.mosaic_fit_to_screen);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.mosaic_view_mode) {
            fitToScreen = !fitToScreen;
            AppPreferences.getInstance(this).setMosaicFitToScreen(fitToScreen);
            invalidateOptionsMenu();
            if (layoutModeListener != null) {
                layoutModeListener.onFitToScreenChanged(fitToScreen);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public LibVLC getSharedLibVlc() {
        return libVlc;
    }

    @Override
    public boolean isMosaicFitToScreen() {
        return fitToScreen;
    }

    @Override
    public void registerLayoutModeListener(MosaicHost.LayoutModeListener listener) {
        layoutModeListener = listener;
    }

    @Override
    public void unregisterLayoutModeListener(MosaicHost.LayoutModeListener listener) {
        // Identity-checked: a page pausing after its successor resumed must not
        // clear the incoming page's registration.
        if (layoutModeListener == listener) {
            layoutModeListener = null;
        }
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
