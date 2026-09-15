package com.euphi.trailbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Haelt OsmAndLink und BikeComputerGattServer am Leben, unabhaengig vom
 * Lebenszyklus von MainActivity. Ohne diesen Service wuerde
 * Activity.onStop() (z.B. beim Sperren des Bildschirms -- genau der
 * Anwendungsfall dieser App auf dem Fahrrad) Advertising und GATT-Server
 * sofort abwuergen.
 *
 * MainActivity bindet sich nur noch dran, um Status-Updates fuers UI zu
 * bekommen; der Service selbst laeuft als Foreground Service mit
 * Dauerbenachrichtigung weiter, auch wenn keine Activity gebunden ist.
 */
public class TrailBridgeService extends Service
        implements OsmAndLink.Listener, GpsLink.Listener, BikeComputerGattServer.Listener {

    public interface UiListener {
        void onStatusChanged(String status);
        void onNavState(NavState state);
        void onGpsStatusChanged(String status);
        void onPositionUpdate(PositionState state);
        void onSubscriberCountChanged(int count);
        void onBleError(String message);
    }

    public static final String ACTION_STOP = "com.euphi.trailbridge.action.STOP";
    private static final String CHANNEL_ID = "trailbridge_running";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();

    private OsmAndLink osmAndLink;
    private GpsLink gpsLink;
    private BikeComputerGattServer gattServer;
    @Nullable private UiListener uiListener;

    // MainActivity ruft TrailBridgeService.start() bei jedem onStart() auf
    // (auch wenn der Service laengst laeuft, z.B. nach Bildschirm an/aus)
    // -- onStartCommand() muss daher idempotent sein, sonst startet
    // osmAndLink.start()/gattServer.start() ein zweites Mal auf demselben
    // Objekt und startAdvertising() schlaegt mit
    // ADVERTISE_FAILED_ALREADY_STARTED fehl.
    private boolean started = false;

    // Zuletzt bekannter Stand, damit eine (neu) gebundene Activity sofort den
    // aktuellen Stand zeigt statt bis zum naechsten Event zu warten.
    private String lastStatus = "";
    @Nullable private NavState lastNavState;
    private String lastGpsStatus = "";
    @Nullable private PositionState lastPositionState;
    private int lastSubscriberCount = 0;
    @Nullable private String lastBleError;

    public class LocalBinder extends Binder {
        TrailBridgeService getService() {
            return TrailBridgeService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        osmAndLink = new OsmAndLink(this, this);
        gpsLink = new GpsLink(this, this);
        gattServer = new BikeComputerGattServer(this, this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundNotification();
        if (!started) {
            started = true;
            osmAndLink.start();
            // Wird nur aufgerufen, nachdem MainActivity die noetigen
            // Laufzeit-Berechtigungen (Bluetooth, Standort) bereits erteilt
            // bekommen hat (siehe MainActivity.startServiceIfPermitted) --
            // der Service selbst kann keine Berechtigungsdialoge zeigen.
            gpsLink.start();
            gattServer.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        osmAndLink.stop();
        gpsLink.stop();
        gattServer.stop();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ---- API fuer die (optional) gebundene MainActivity ----

    public void setUiListener(@Nullable UiListener listener) {
        this.uiListener = listener;
        if (listener != null) {
            // aktuellen Stand sofort nachreichen
            listener.onStatusChanged(lastStatus);
            if (lastNavState != null) listener.onNavState(lastNavState);
            listener.onGpsStatusChanged(lastGpsStatus);
            if (lastPositionState != null) listener.onPositionUpdate(lastPositionState);
            listener.onSubscriberCountChanged(lastSubscriberCount);
            if (lastBleError != null) listener.onBleError(lastBleError);
        }
    }

    public void retrySubscribe() {
        osmAndLink.retrySubscribe();
    }

    // ---- OsmAndLink.Listener ----

    @Override
    public void onStatusChanged(String status) {
        lastStatus = status;
        if (uiListener != null) uiListener.onStatusChanged(status);
    }

    @Override
    public void onNavState(NavState state) {
        lastNavState = state;
        if (uiListener != null) uiListener.onNavState(state);
        gattServer.update(state);
    }

    // ---- GpsLink.Listener ----

    @Override
    public void onGpsStatusChanged(String status) {
        lastGpsStatus = status;
        if (uiListener != null) uiListener.onGpsStatusChanged(status);
    }

    @Override
    public void onPositionUpdate(PositionState state) {
        lastPositionState = state;
        if (uiListener != null) uiListener.onPositionUpdate(state);
        gattServer.updatePosition(state);
    }

    // ---- BikeComputerGattServer.Listener ----

    @Override
    public void onSubscriberCountChanged(int count) {
        lastSubscriberCount = count;
        if (uiListener != null) uiListener.onSubscriberCountChanged(count);
    }

    @Override
    public void onError(String message) {
        lastBleError = message;
        if (uiListener != null) uiListener.onBleError(message);
    }

    // ---- Foreground-Notification ----

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "TrailBridge aktiv", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Zeigt an, dass TrailBridge im Hintergrund Navigations- und GPS-Daten an den BikeComputer weiterleitet.");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private void startForegroundNotification() {
        Intent stopIntent = new Intent(this, TrailBridgeService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TrailBridge aktiv")
                .setContentText("Leitet OsmAnd-Navigation und GPS-Position per BLE an den BikeComputer weiter")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .addAction(0, "Beenden", stopPendingIntent)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            // Claiming the "location" FGS type without already holding the
            // underlying dangerous permission throws SecurityException --
            // observed in practice on a START_STICKY restart (e.g. right
            // after adb install -r, or any OS-triggered restart) that races
            // ahead of MainActivity's permission dialog. Leave the type out
            // until it's actually granted; onStartCommand() re-runs this on
            // every TrailBridgeService.start() call (see MainActivity ->
            // startAndBindService() after the permission grant), so it
            // upgrades itself the moment the user grants it, no separate
            // wiring needed.
            if (hasLocationPermission()) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---- Hilfsfunktion fuer MainActivity ----

    public static void start(Context context) {
        Intent intent = new Intent(context, TrailBridgeService.class);
        context.startForegroundService(intent);
    }
}
