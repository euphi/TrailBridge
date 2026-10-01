package com.euphi.trailbridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/**
 * Navi-Modus: the screen for riding. Only the navigation, the position and -- while one is on
 * its way to the BikeComputer -- the elevation profile; none of the set-up controls of the
 * main screen. Look and feel are the main screen's (same cards via {@link LiveCards}).
 *
 * The profile card shows exactly the {@link ProfileFrame} the BikeComputer was sent, with the
 * rider placed in it the way the BikeComputer places him (PROTOCOL.md): so it appears with a
 * climb ahead and disappears with PROFILE_NONE, in step with the device.
 *
 * Keeps the screen on while it is in front. Start the route (or a test ride) on the main screen first.
 */
public class NaviActivity extends AppCompatActivity implements TrailBridgeService.UiListener {

    private LiveCards live;
    private View bleDot;
    private View profileCard;
    private ClimbProfileView climbView;
    private TextView gradeView;
    private TextView riseView;

    @Nullable private TrailBridgeService service;
    private boolean bound = false;

    private NavState nav = NavState.NONE;
    @Nullable private ProfileFrame profile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.setContentView(this, R.layout.activity_navi, R.id.scroll);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        live = new LiveCards(this);
        bleDot = findViewById(R.id.bleDot);
        profileCard = findViewById(R.id.profileCard);
        climbView = findViewById(R.id.climbView);
        gradeView = findViewById(R.id.gradeView);
        riseView = findViewById(R.id.riseView);
        findViewById(R.id.closeButton).setOnClickListener(v -> finish());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Normally the main screen has asked for the permissions and started the service
        // before it brought us here; if that is no longer true (revoked, process restart),
        // hand back to it.
        if (!MainActivity.missingPermissions(this).isEmpty()) {
            finish();
            return;
        }
        TrailBridgeService.start(this);
        bindService(new Intent(this, TrailBridgeService.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) {
            if (service != null) service.removeUiListener(this);
            unbindService(serviceConnection);
            bound = false;
        }
    }

    private final android.content.ServiceConnection serviceConnection = new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TrailBridgeService.LocalBinder) binder).getService();
            bound = true;
            service.setUiListener(NaviActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    private void showProfile() {
        ProfileFrame f = profile;
        double offset = f == null || !nav.navigating ? Double.NaN : f.riderOffsetM(nav.remainingDistanceM);
        if (f == null || Double.isNaN(offset)) {
            profileCard.setVisibility(View.GONE);   // none sent, stale, or not reached yet
            return;
        }
        profileCard.setVisibility(View.VISIBLE);
        climbView.setProfile(f, offset);

        double rise = f.altitudeAtM(f.lengthM()) - f.altitudeAtM(offset);
        double run = f.lengthM() - offset;
        gradeView.setText(run >= 50 ? String.format(Locale.getDefault(), "%+.1f %%", rise / run * 100)
                : getString(R.string.dash));
        riseView.setText(String.format(Locale.getDefault(), "%+d m", Math.round(rise)));
    }

    // ---- TrailBridgeService.UiListener ----

    @Override
    public void onNavState(NavState state) {
        runOnUiThread(() -> {
            nav = state;
            live.showNav(state);
            showProfile();
        });
    }

    @Override
    public void onPositionUpdate(PositionState state) {
        runOnUiThread(() -> live.showPosition(state));
    }

    @Override
    public void onProfileChanged(@Nullable ProfileFrame frame) {
        runOnUiThread(() -> {
            profile = frame;
            showProfile();
        });
    }

    @Override
    public void onSubscriberCountChanged(int count) {
        // green: the BikeComputer is subscribed; yellow: advertising, nobody there yet
        runOnUiThread(() -> LiveCards.setDot(this, bleDot,
                count > 0 ? R.color.rr_zone_green : R.color.rr_zone_yellow));
    }

    @Override
    public void onBleError(String message) {
        runOnUiThread(() -> LiveCards.setDot(this, bleDot, R.color.rr_zone_red));
    }

    @Override
    public void onStatusChanged(String status) {
    }

    @Override
    public void onGpsStatusChanged(String status) {
    }

    @Override
    public void onRouteChanged(String summary, boolean active) {
    }

    @Override
    public void onPlaybackChanged(@Nullable PlaybackState state) {
    }
}
