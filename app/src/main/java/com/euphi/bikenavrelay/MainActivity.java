package com.euphi.bikenavrelay;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
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
 * Ablauf:
 *  1. OsmAnd installiert, Offline-Karte vorhanden.
 *  2. Diese App öffnen -> ggf. Bluetooth-Berechtigungen erlauben (Android 12+).
 *  3. OsmAnd-Status zeigt "NICHT FREIGESCHALTET" -> in OsmAnd: Menü > Plugins
 *     > BikeNavRelay > aktivieren -> hier "Erneut versuchen" antippen.
 *  4. BLE-Status sollte "Advertising, 0 Abonnenten" zeigen (oder einen Fehler,
 *     falls das Gerät keine Peripheral-Rolle kann -- siehe README).
 *  5. In OsmAnd eine Route starten -> Textausgabe füllt sich, UND bei
 *     verbundenem BLE-Client wird bei jeder Änderung + alle 5s ein Frame
 *     geschickt (in nRF Connect am Log sichtbar).
 */
public class MainActivity extends AppCompatActivity
        implements OsmAndLink.Listener, BikeComputerGattServer.Listener {

    private static final int REQUEST_BLE_PERMISSIONS = 1001;

    private TextView statusView;
    private TextView bleStatusView;
    private TextView navStateView;
    private OsmAndLink osmAndLink;
    private BikeComputerGattServer gattServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.statusView);
        bleStatusView = findViewById(R.id.bleStatusView);
        navStateView = findViewById(R.id.navStateView);
        findViewById(R.id.retryButton).setOnClickListener(v -> osmAndLink.retrySubscribe());

        osmAndLink = new OsmAndLink(this, this);
        gattServer = new BikeComputerGattServer(this, this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        osmAndLink.start();
        startGattServerIfPermitted();
    }

    @Override
    protected void onStop() {
        super.onStop();
        osmAndLink.stop();
        gattServer.stop();
    }

    // ---- Bluetooth runtime permissions (only dangerous on API 31+) ----

    private void startGattServerIfPermitted() {
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
        if (missing.isEmpty()) {
            gattServer.start();
        } else {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQUEST_BLE_PERMISSIONS);
        }
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
                gattServer.start();
            } else {
                bleStatusView.setText("Bluetooth-Berechtigung verweigert -- BLE-Server kann nicht starten.");
            }
        }
    }

    // ---- OsmAndLink.Listener ----

    @Override
    public void onStatusChanged(String status) {
        runOnUiThread(() -> statusView.setText(status));
    }

    @Override
    public void onNavState(NavState state) {
        runOnUiThread(() -> navStateView.setText(state.toString()));
        gattServer.update(state);
    }

    // ---- BikeComputerGattServer.Listener ----

    @Override
    public void onSubscriberCountChanged(int count) {
        runOnUiThread(() -> bleStatusView.setText("BLE: Advertising, " + count + " Abonnent(en)"));
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> bleStatusView.setText("BLE-Fehler: " + message));
    }
}
