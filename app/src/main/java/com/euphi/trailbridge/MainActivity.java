package com.euphi.trailbridge;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Meilenstein 2: OsmAnd-Anbindung (Meilenstein 1) + BLE-GATT-Server nach
 * PROTOCOL.md. Der BikeComputer verbindet sich noch nicht wirklich hiermit --
 * das ist Meilenstein 3 (Firmware-Änderung). Zum Testen bis dahin taugt jede
 * BLE-Scanner-App (z.B. nRF Connect): Service f7ac2b76-... sollte auftauchen,
 * die Characteristic 7473da02-... lässt sich abonnieren (Indicate) und lesen.
 *
 * Die eigentliche Arbeit (OsmAnd-Anbindung + GATT-Server) macht
 * {@link TrailBridgeService} als Foreground Service, damit sie beim Sperren
 * des Bildschirms weiterläuft -- diese Activity bindet sich nur noch dran,
 * um den Status anzuzeigen.
 *
 * Ablauf:
 *  1. OsmAnd installiert, Offline-Karte vorhanden.
 *  2. Diese App öffnen -> ggf. Bluetooth-/Benachrichtigungs-Berechtigungen
 *     erlauben (Android 12+ bzw. 13+).
 *  3. OsmAnd-Status zeigt "NICHT FREIGESCHALTET" -> in OsmAnd: Menü > Plugins
 *     > TrailBridge > aktivieren -> hier "Erneut versuchen" antippen.
 *  4. BLE-Status sollte "Advertising, 0 Abonnenten" zeigen (oder einen Fehler,
 *     falls das Gerät keine Peripheral-Rolle kann -- siehe README).
 *  5. In OsmAnd eine Route starten -> Textausgabe füllt sich, UND bei
 *     verbundenem BLE-Client wird bei jeder Änderung + alle 5s ein Frame
 *     geschickt (in nRF Connect am Log sichtbar) -- auch bei gesperrtem
 *     Bildschirm, solange der Dienst in der Benachrichtigungsleiste läuft.
 */
public class MainActivity extends AppCompatActivity implements TrailBridgeService.UiListener {

    private static final int REQUEST_BLE_PERMISSIONS = 1001;

    private TextView statusView;
    private TextView bleStatusView;
    private TextView navStateView;
    private TextView positionStateView;
    private View osmandDot;
    private View bleDot;

    private LiveCards live;   // Position and Navigation cards, shared with NaviActivity

    // Moduswahl: which cards are shown (UiMode)
    private static final String PREFS_UI = "ui";
    private static final String PREF_MODE = "mode";
    private UiMode mode = UiMode.ALL;
    private boolean osmandConnected = false;
    private boolean testPanelOpen = false;
    private View osmandRow;
    private View retryButton;
    private View routeCard;
    private View rawCard;
    private TextView modeHint;
    private final java.util.EnumMap<UiMode, Button> modeButtons = new java.util.EnumMap<>(UiMode.class);
    private TextView routeInfoView;
    private Button routeToggleButton;
    private boolean routeLoaded = false;
    private boolean routeActive = false;

    // Testfahrt (GPX abspielen)
    private Button testToggleButton;
    private View testPanel;
    private TextView sensorInfoView;
    private TextView playbackInfoView;
    private CheckBox emulateHrCheck;
    private CheckBox emulateCadCheck;
    private CheckBox emulatePowerCheck;
    private RouteProfileView profileView;
    private Button playButton;
    @Nullable private PlaybackState playback;
    private boolean updatingTestUi = false;

    /** "Manöver »/«" springt so weit vor das Manöver: weit genug, dass die Anzeige des
     *  BikeComputers die Annäherung (Auto-Umschaltung auf Navigation) noch zeigt. */
    private static final double APPROACH_M = 400;

    /** GPX-Datei, die per "Öffnen mit"/Teilen kam, bevor der Dienst gebunden war. */
    @Nullable private Uri pendingGpx;

    private static final int MAX_GPX_BYTES = 20 * 1024 * 1024;

    private final ActivityResultLauncher<String[]> gpxPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) loadGpx(uri);
            });

    @Nullable private TrailBridgeService service;
    private boolean bound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.setContentView(this, R.layout.activity_main, R.id.scroll);

        statusView = findViewById(R.id.statusView);
        bleStatusView = findViewById(R.id.bleStatusView);
        navStateView = findViewById(R.id.navStateView);
        positionStateView = findViewById(R.id.positionStateView);
        osmandDot = findViewById(R.id.osmandDot);
        bleDot = findViewById(R.id.bleDot);
        setupLiveViews();
        findViewById(R.id.naviButton).setOnClickListener(v ->
                startActivity(new Intent(this, NaviActivity.class)));
        retryButton = findViewById(R.id.retryButton);
        retryButton.setOnClickListener(v -> {
            if (service != null) service.retrySubscribe();
        });
        setupModeSelector();
        routeInfoView = findViewById(R.id.routeInfoView);
        routeToggleButton = findViewById(R.id.routeToggleButton);
        findViewById(R.id.loadGpxButton).setOnClickListener(v -> gpxPicker.launch(new String[]{"*/*"}));
        routeToggleButton.setOnClickListener(v -> {
            if (service == null) return;
            if (routeActive) service.stopRoute(); else service.startRoute();
        });
        setupTestPanel();
        updateRouteButton();
        handleIntent(getIntent());
    }

    private void setupModeSelector() {
        osmandRow = findViewById(R.id.osmandRow);
        routeCard = findViewById(R.id.routeCard);
        rawCard = findViewById(R.id.rawCard);
        modeHint = findViewById(R.id.modeHint);
        modeButtons.put(UiMode.OSMAND, findViewById(R.id.modeOsmand));
        modeButtons.put(UiMode.GPX_NAV, findViewById(R.id.modeGpxNav));
        modeButtons.put(UiMode.SIMULATION, findViewById(R.id.modeSimulation));
        modeButtons.put(UiMode.ALL, findViewById(R.id.modeAll));
        for (java.util.Map.Entry<UiMode, Button> e : modeButtons.entrySet()) {
            UiMode m = e.getKey();
            e.getValue().setOnClickListener(v -> setMode(m));
        }
        mode = UiMode.fromKey(getSharedPreferences(PREFS_UI, MODE_PRIVATE).getString(PREF_MODE, null));
    }

    /** The user picked a mode: remember it, show its cards, and stop what it would leave hidden. */
    private void setMode(UiMode m) {
        if (m == mode) return;
        mode = m;
        getSharedPreferences(PREFS_UI, MODE_PRIVATE).edit().putString(PREF_MODE, m.key).apply();
        TrailBridgeService s = service;
        if (s != null) {
            boolean playing = playback != null && playback.active;
            if (playing && !m.keepsPlayback()) {
                s.playbackStop();
                playing = false;
            }
            if (!m.keepsRoute(playing)) s.stopRoute();
        }
        applyMode();
    }

    /** Shows the cards and buttons that belong to the current mode. */
    private void applyMode() {
        for (java.util.Map.Entry<UiMode, Button> e : modeButtons.entrySet()) {
            e.getValue().setSelected(e.getKey() == mode);
        }
        modeHint.setText(mode == UiMode.OSMAND ? R.string.mode_hint_osmand
                : mode == UiMode.GPX_NAV ? R.string.mode_hint_gpx_nav
                : mode == UiMode.SIMULATION ? R.string.mode_hint_simulation
                : R.string.mode_hint_all);

        osmandRow.setVisibility(mode.showsOsmand(osmandConnected) ? View.VISIBLE : View.GONE);
        retryButton.setVisibility(osmandConnected ? View.GONE : View.VISIBLE);
        routeCard.setVisibility(mode.showsRoute() ? View.VISIBLE : View.GONE);
        routeToggleButton.setVisibility(mode.showsRouteStart() ? View.VISIBLE : View.GONE);
        UiMode.TestPanel panel = mode.testPanel();
        testToggleButton.setVisibility(panel == UiMode.TestPanel.TOGGLE ? View.VISIBLE : View.GONE);
        boolean showPanel = routeLoaded && (panel == UiMode.TestPanel.ALWAYS
                || (panel == UiMode.TestPanel.TOGGLE && testPanelOpen));
        testPanel.setVisibility(showPanel ? View.VISIBLE : View.GONE);
        rawCard.setVisibility(mode.showsRaw() ? View.VISIBLE : View.GONE);
    }

    private void setupLiveViews() {
        live = new LiveCards(this);

        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.footerView)).setText(getString(R.string.footer_version, "v" + version));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
    }

    private void setDot(View dot, int colorRes) {
        LiveCards.setDot(this, dot, colorRes);
    }

    /**
     * Colour of the OsmAnd dot, picked from the status text OsmAndLink sets -- a purely
     * cosmetic guess: a text it doesn't know just leaves the dot grey.
     */
    private static int osmandDotColor(String status) {
        if (status.startsWith("verbunden")) return R.color.rr_zone_green;
        if (status.startsWith("NICHT FREIGESCHALTET") || status.startsWith("verbinde")) return R.color.rr_zone_yellow;
        if (status.contains("nicht gefunden") || status.contains("verloren")
                || status.contains("Fehler") || status.contains("abgelehnt")) return R.color.rr_zone_red;
        return R.color.rr_muted;
    }

    private void setupTestPanel() {
        testToggleButton = findViewById(R.id.testToggleButton);
        testPanel = findViewById(R.id.testPanel);
        sensorInfoView = findViewById(R.id.sensorInfoView);
        playbackInfoView = findViewById(R.id.playbackInfoView);
        emulateHrCheck = findViewById(R.id.emulateHrCheck);
        emulateCadCheck = findViewById(R.id.emulateCadCheck);
        emulatePowerCheck = findViewById(R.id.emulatePowerCheck);
        profileView = findViewById(R.id.profileView);
        playButton = findViewById(R.id.playButton);

        testToggleButton.setOnClickListener(v -> {
            testPanelOpen = !testPanelOpen;
            applyMode();
        });
        emulateHrCheck.setChecked(true);
        emulateCadCheck.setChecked(true);
        emulatePowerCheck.setChecked(true);
        View.OnClickListener emulation = v -> {
            if (!updatingTestUi && service != null) {
                service.setPlaybackEmulation(emulateHrCheck.isChecked(), emulateCadCheck.isChecked(),
                        emulatePowerCheck.isChecked());
            }
        };
        emulateHrCheck.setOnClickListener(emulation);
        emulateCadCheck.setOnClickListener(emulation);
        emulatePowerCheck.setOnClickListener(emulation);

        playButton.setOnClickListener(v -> {
            if (service != null) service.playbackToggle();
        });
        findViewById(R.id.stopPlaybackButton).setOnClickListener(v -> {
            if (service != null) service.playbackStop();
        });
        findViewById(R.id.prevManeuverButton).setOnClickListener(v -> jumpToManeuver(false));
        findViewById(R.id.nextManeuverButton).setOnClickListener(v -> jumpToManeuver(true));
        profileView.setOnSeekListener((distM, committed) -> {
            if (committed) {
                if (service != null) service.playbackSeek(distM);
            } else if (playback != null) {
                playbackInfoView.setText(String.format(Locale.getDefault(), "%.2f / %.1f km",
                        distM / 1000, playback.totalM / 1000));
            }
        });
    }

    /** Springt zu APPROACH_M vor das naechste/vorige Manoever (die Strecke vor dem Start zaehlt nicht). */
    private void jumpToManeuver(boolean forward) {
        PlaybackState st = playback;
        if (st == null || service == null) return;
        double cur = st.distM;
        double target = forward ? st.totalM : 0;
        for (GpxRoute.Step s : st.route.steps) {
            if (s.maneuver == Maneuver.DEPART) continue;
            double at = Math.max(0, s.distM - APPROACH_M);
            if (forward && at > cur + 5) {
                target = at;
                break;
            }
            if (!forward && at < cur - 5) {
                target = at;   // steps are sorted: the last one before the cursor wins
            }
        }
        service.playbackSeek(target);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** GPX per "Öffnen mit" (VIEW) oder Teilen (SEND). */
    private void handleIntent(@Nullable Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) return;
        // Nur einmal verarbeiten, auch wenn die Activity neu erzeugt wird.
        intent.setAction(Intent.ACTION_MAIN);
        loadGpx(uri);
    }

    private void loadGpx(Uri uri) {
        // Someone loading a GPX wants to use it: leave the mode that has no route card.
        if (mode == UiMode.OSMAND) setMode(UiMode.GPX_NAV);
        if (service == null) {
            pendingGpx = uri;   // onServiceConnected holt das nach
            return;
        }
        TrailBridgeService target = service;
        String name = displayName(uri);
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Datei nicht lesbar");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_GPX_BYTES) throw new IOException("Datei größer als 20 MB");
                }
                target.loadGpx(out.toByteArray(), name);
            } catch (IOException | SecurityException e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "GPX konnte nicht gelesen werden: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "gpx-read").start();
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n.replaceFirst("(?i)\\.gpx$", "");
            }
        } catch (RuntimeException ignored) {
        }
        return "Route";
    }

    private void updateRouteButton() {
        testToggleButton.setEnabled(routeLoaded);
        routeToggleButton.setEnabled(routeLoaded);
        routeToggleButton.setText(routeActive ? R.string.route_stop : R.string.route_start);
        applyMode();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startServiceIfPermitted();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Nur die Activity-Bindung loesen -- der Service (und damit
        // Advertising + OsmAnd-Verbindung) laeuft als Foreground Service
        // bewusst weiter, auch wenn der Bildschirm gesperrt wird.
        if (bound) {
            if (service != null) service.removeUiListener(this);
            unbindService(serviceConnection);
            bound = false;
        }
    }

    // ---- Bluetooth-/Benachrichtigungs-Berechtigungen (nur ab API 31 bzw. 33 "dangerous") ----

    /** The runtime permissions the service needs that are not granted (yet). */
    static List<String> missingPermissions(Context context) {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return missing;
    }

    private void startServiceIfPermitted() {
        List<String> missing = missingPermissions(this);
        if (missing.isEmpty()) {
            startAndBindService();
        } else {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQUEST_BLE_PERMISSIONS);
        }
    }

    private void startAndBindService() {
        TrailBridgeService.start(this);
        bindService(new Intent(this, TrailBridgeService.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLE_PERMISSIONS) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted) {
                startAndBindService();
            } else {
                bleStatusView.setText("Berechtigung(en) verweigert (Bluetooth/Benachrichtigung/Standort) -- Dienst kann nicht vollständig starten.");
                setDot(bleDot, R.color.rr_zone_red);
            }
        }
    }

    // ---- Service-Bindung ----

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TrailBridgeService.LocalBinder) binder).getService();
            bound = true;
            service.setUiListener(MainActivity.this);
            if (pendingGpx != null) {
                Uri uri = pendingGpx;
                pendingGpx = null;
                loadGpx(uri);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    // ---- TrailBridgeService.UiListener ----

    @Override
    public void onStatusChanged(String status) {
        runOnUiThread(() -> {
            statusView.setText(status);
            setDot(osmandDot, osmandDotColor(status));
            osmandConnected = status.startsWith("verbunden");
            applyMode();
        });
    }

    @Override
    public void onNavState(NavState state) {
        runOnUiThread(() -> {
            navStateView.setText(state.toString());
            live.showNav(state);
        });
    }

    @Override
    public void onGpsStatusChanged(String status) {
        runOnUiThread(() -> positionStateView.setText(status));
    }

    @Override
    public void onPositionUpdate(PositionState state) {
        runOnUiThread(() -> {
            positionStateView.setText(state.toString());
            live.showPosition(state);
        });
    }

    @Override
    public void onSubscriberCountChanged(int count) {
        runOnUiThread(() -> {
            bleStatusView.setText("BLE: Advertising, " + count + " Abonnent(en)");
            // green: the BikeComputer is subscribed; yellow: advertising, nobody there yet
            setDot(bleDot, count > 0 ? R.color.rr_zone_green : R.color.rr_zone_yellow);
        });
    }

    @Override
    public void onRouteChanged(String summary, boolean active) {
        runOnUiThread(() -> {
            routeActive = active;
            // "" = keine Route geladen; Fehlermeldungen lassen den Ladezustand stehen
            if (summary.isEmpty()) routeLoaded = false;
            else if (!summary.startsWith("GPX-Import fehlgeschlagen")) routeLoaded = true;
            routeInfoView.setText(summary.isEmpty() ? getString(R.string.route_none) : summary);
            updateRouteButton();
        });
    }

    @Override
    public void onPlaybackChanged(@Nullable PlaybackState state) {
        runOnUiThread(() -> showPlayback(state));
    }

    private void showPlayback(@Nullable PlaybackState st) {
        playback = st;
        if (st == null) {
            profileView.setRoute(null);
            sensorInfoView.setText("");
            playbackInfoView.setText("");
            return;
        }
        profileView.setRoute(st.route);
        profileView.setPosition(st.distM);

        updatingTestUi = true;
        // Vorhandene GPX-Werte werden immer genommen -- dann gibt es nichts zu emulieren.
        emulateHrCheck.setEnabled(st.hrSource != RoutePlayer.Source.GPX);
        emulateCadCheck.setEnabled(st.cadSource != RoutePlayer.Source.GPX);
        emulatePowerCheck.setEnabled(st.powerSource != RoutePlayer.Source.GPX);
        if (st.hrSource != RoutePlayer.Source.GPX) {
            emulateHrCheck.setChecked(st.hrSource == RoutePlayer.Source.EMULATED);
        }
        if (st.cadSource != RoutePlayer.Source.GPX) {
            emulateCadCheck.setChecked(st.cadSource == RoutePlayer.Source.EMULATED);
        }
        if (st.powerSource != RoutePlayer.Source.GPX) {
            emulatePowerCheck.setChecked(st.powerSource == RoutePlayer.Source.EMULATED);
        }
        updatingTestUi = false;

        sensorInfoView.setText(
                "Geschwindigkeit: " + (st.speedSource == RoutePlayer.Source.GPX
                        ? "aus den GPX-Zeitstempeln" : "berechnet (GPX ohne Zeitstempel)")
                + "\nPuls: " + sourceText(st.hrSource)
                + "\nTrittfrequenz: " + sourceText(st.cadSource)
                + "\nLeistung: " + sourceText(st.powerSource)
                + "\nHöhe: " + (st.hasHeight ? "aus der GPX (Gradient berechnet der BikeComputer selbst)"
                        : "nicht in der GPX -- wird nicht gesendet"));

        StringBuilder info = new StringBuilder(String.format(Locale.getDefault(),
                "%.2f / %.1f km  %s / %s", st.distM / 1000, st.totalM / 1000,
                clock(st.timeS), clock(st.durationS)));
        if (st.active) {
            info.append(String.format(Locale.getDefault(), "\n%.1f km/h", st.speedMs * 3.6));
            if (st.hr >= 0) info.append(String.format(Locale.getDefault(), "  Puls %d", st.hr));
            if (st.cad >= 0) info.append(String.format(Locale.getDefault(), "  Tf %d", st.cad));
            if (st.power >= 0) info.append(String.format(Locale.getDefault(), "  %d W", st.power));
            if (!Double.isNaN(st.eleM)) info.append(String.format(Locale.getDefault(), "  %d m", Math.round(st.eleM)));
            if (st.finished) info.append("\nZiel erreicht");
        }
        playbackInfoView.setText(info);

        playButton.setText(st.active && st.playing ? R.string.pause
                : st.finished ? R.string.replay : R.string.play);
    }

    private static String sourceText(RoutePlayer.Source source) {
        switch (source) {
            case GPX:
                return "aus der GPX";
            case EMULATED:
                return "nicht in der GPX -- emuliert";
            default:
                return "nicht in der GPX -- wird nicht gesendet";
        }
    }

    private static String clock(double seconds) {
        int s = (int) Math.round(seconds);
        return String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    @Override
    public void onBleError(String message) {
        runOnUiThread(() -> {
            bleStatusView.setText("BLE-Fehler: " + message);
            setDot(bleDot, R.color.rr_zone_red);
        });
    }
}
