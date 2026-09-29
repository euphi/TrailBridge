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
import android.widget.Button;
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
    private TextView routeInfoView;
    private Button routeToggleButton;
    private boolean routeLoaded = false;
    private boolean routeActive = false;

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
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.statusView);
        bleStatusView = findViewById(R.id.bleStatusView);
        navStateView = findViewById(R.id.navStateView);
        positionStateView = findViewById(R.id.positionStateView);
        findViewById(R.id.retryButton).setOnClickListener(v -> {
            if (service != null) service.retrySubscribe();
        });
        routeInfoView = findViewById(R.id.routeInfoView);
        routeToggleButton = findViewById(R.id.routeToggleButton);
        findViewById(R.id.loadGpxButton).setOnClickListener(v -> gpxPicker.launch(new String[]{"*/*"}));
        routeToggleButton.setOnClickListener(v -> {
            if (service == null) return;
            if (routeActive) service.stopRoute(); else service.startRoute();
        });
        updateRouteButton();
        handleIntent(getIntent());
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
        routeToggleButton.setEnabled(routeLoaded);
        routeToggleButton.setText(routeActive ? R.string.route_stop : R.string.route_start);
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
            if (service != null) service.setUiListener(null);
            unbindService(serviceConnection);
            bound = false;
        }
    }

    // ---- Bluetooth-/Benachrichtigungs-Berechtigungen (nur ab API 31 bzw. 33 "dangerous") ----

    private void startServiceIfPermitted() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
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
        runOnUiThread(() -> statusView.setText(status));
    }

    @Override
    public void onNavState(NavState state) {
        runOnUiThread(() -> navStateView.setText(state.toString()));
    }

    @Override
    public void onGpsStatusChanged(String status) {
        runOnUiThread(() -> positionStateView.setText(status));
    }

    @Override
    public void onPositionUpdate(PositionState state) {
        runOnUiThread(() -> positionStateView.setText(state.toString()));
    }

    @Override
    public void onSubscriberCountChanged(int count) {
        runOnUiThread(() -> bleStatusView.setText("BLE: Advertising, " + count + " Abonnent(en)"));
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
    public void onBleError(String message) {
        runOnUiThread(() -> bleStatusView.setText("BLE-Fehler: " + message));
    }
}
