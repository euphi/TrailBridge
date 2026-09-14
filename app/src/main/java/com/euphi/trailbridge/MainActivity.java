package com.euphi.trailbridge;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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

    @Nullable private TrailBridgeService service;
    private boolean bound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.statusView);
        bleStatusView = findViewById(R.id.bleStatusView);
        navStateView = findViewById(R.id.navStateView);
        findViewById(R.id.retryButton).setOnClickListener(v -> {
            if (service != null) service.retrySubscribe();
        });
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
                bleStatusView.setText("Bluetooth-/Benachrichtigungs-Berechtigung verweigert -- BLE-Server kann nicht starten.");
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
    public void onSubscriberCountChanged(int count) {
        runOnUiThread(() -> bleStatusView.setText("BLE: Advertising, " + count + " Abonnent(en)"));
    }

    @Override
    public void onBleError(String message) {
        runOnUiThread(() -> bleStatusView.setText("BLE-Fehler: " + message));
    }
}
