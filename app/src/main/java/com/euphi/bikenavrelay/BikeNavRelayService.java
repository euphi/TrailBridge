package com.euphi.bikenavrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

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
public class BikeNavRelayService extends Service
        implements OsmAndLink.Listener, BikeComputerGattServer.Listener {

    public interface UiListener {
        void onStatusChanged(String status);
        void onNavState(NavState state);
        void onSubscriberCountChanged(int count);
        void onBleError(String message);
    }

    public static final String ACTION_STOP = "com.euphi.bikenavrelay.action.STOP";
    private static final String CHANNEL_ID = "bikenavrelay_running";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();

    private OsmAndLink osmAndLink;
    private BikeComputerGattServer gattServer;
    @Nullable private UiListener uiListener;

    // MainActivity ruft BikeNavRelayService.start() bei jedem onStart() auf
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
    private int lastSubscriberCount = 0;
    @Nullable private String lastBleError;

    public class LocalBinder extends Binder {
        BikeNavRelayService getService() {
            return BikeNavRelayService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        osmAndLink = new OsmAndLink(this, this);
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
            // Wird nur aufgerufen, nachdem MainActivity BLUETOOTH_ADVERTISE/_CONNECT
            // bereits erteilt bekommen hat (siehe MainActivity.startServiceIfPermitted) --
            // der Service selbst kann keine Laufzeit-Berechtigungsdialoge zeigen.
            gattServer.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        osmAndLink.stop();
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
                CHANNEL_ID, "BikeNavRelay aktiv", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Zeigt an, dass BikeNavRelay im Hintergrund Navigationsdaten an den BikeComputer weiterleitet.");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private void startForegroundNotification() {
        Intent stopIntent = new Intent(this, BikeNavRelayService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BikeNavRelay aktiv")
                .setContentText("Leitet OsmAnd-Navigation per BLE an den BikeComputer weiter")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .addAction(0, "Beenden", stopPendingIntent)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ---- Hilfsfunktion fuer MainActivity ----

    public static void start(Context context) {
        Intent intent = new Intent(context, BikeNavRelayService.class);
        context.startForegroundService(intent);
    }
}
