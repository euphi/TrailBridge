package com.euphi.bikenavrelay;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The BLE peripheral (server) side of PROTOCOL.md. The BikeComputer stays the
 * BLE *central* exactly like it was for Komoot -- this class just gives it a
 * new kind of server to talk to.
 *
 * Threading: BluetoothGattServerCallback methods run on a Binder thread, not
 * the main thread. Everything that touches `subscribers` / the in-flight flag
 * is synchronized; nothing here touches Android UI directly.
 */
public class BikeComputerGattServer {

    private static final String TAG = "BikeGatt";

    public static final UUID SERVICE_UUID = UUID.fromString("f7ac2b76-986b-45fd-8e44-f116a61f319d");
    public static final UUID CHAR_UUID = UUID.fromString("7473da02-2de8-4f48-9e46-21b36380c176");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long HEARTBEAT_INTERVAL_MS = 5000L;

    public interface Listener {
        void onSubscriberCountChanged(int count);

        void onError(String message);
    }

    private final Context context;
    private final Listener listener;
    private final BluetoothManager bluetoothManager;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattCharacteristic navCharacteristic;

    private final Set<BluetoothDevice> subscribers = new HashSet<>();

    private volatile byte[] lastFrame = NavFrameEncoder.hello();
    private byte[] pendingFrame = null;
    private boolean indicateInFlight = false;
    private boolean running = false;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            send(lastFrame);
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    public BikeComputerGattServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.bluetoothManager = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
    }

    /**
     * Starts advertising + the GATT server. Requires BLUETOOTH_ADVERTISE and
     * BLUETOOTH_CONNECT to already be granted on API 31+ -- call this only
     * after the caller has confirmed that (see MainActivity).
     */
    public void start() {
        try {
            BluetoothAdapter adapter = bluetoothManager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                fail("Bluetooth ist aus.");
                return;
            }
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                fail("Dieses Gerät unterstützt keine BLE-Peripheral-Rolle (kein Advertiser).");
                return;
            }

            gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
            if (gattServer == null) {
                fail("openGattServer() ist fehlgeschlagen.");
                return;
            }

            navCharacteristic = new BluetoothGattCharacteristic(
                    CHAR_UUID,
                    BluetoothGattCharacteristic.PROPERTY_INDICATE | BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            navCharacteristic.addDescriptor(cccd);
            navCharacteristic.setValue(lastFrame);

            BluetoothGattService service = new BluetoothGattService(
                    SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            service.addCharacteristic(navCharacteristic);
            gattServer.addService(service);

            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build();
            // Hauptpaket bewusst schlank halten (31-Byte-Legacy-Limit): die
            // 128-Bit-Service-UUID allein braucht schon 18 Bytes. Der
            // Gerätename kommt ins separate Scan-Response-Paket (eigenes
            // 31-Byte-Budget) -- sonst schlägt startAdvertising() mit
            // ADVERTISE_FAILED_DATA_TOO_LARGE (Fehlercode 1) fehl, sobald
            // der Bluetooth-Name des Handys mehr als ein paar Zeichen hat.
            AdvertiseData data = new AdvertiseData.Builder()
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();
            AdvertiseData scanResponse = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);

            running = true;
            mainHandler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS);
        } catch (SecurityException e) {
            // Missing BLUETOOTH_ADVERTISE/CONNECT at runtime on API 31+.
            fail("Fehlende Bluetooth-Berechtigung: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        mainHandler.removeCallbacks(heartbeat);
        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        } catch (Exception ignored) {
        }
        try {
            if (gattServer != null) gattServer.close();
        } catch (Exception ignored) {
        }
        synchronized (subscribers) {
            subscribers.clear();
        }
    }

    public int subscriberCount() {
        synchronized (subscribers) {
            return subscribers.size();
        }
    }

    /** Called from OsmAndLink whenever a new NavState is ready. */
    public void update(NavState state) {
        if (!running) return;
        send(NavFrameEncoder.encode(state));
    }

    private void send(byte[] frame) {
        lastFrame = frame;
        synchronized (this) {
            if (indicateInFlight) {
                // Coalesce: only the newest pending frame matters once the
                // in-flight one is confirmed. Skipping an intermediate ETA
                // tick costs nothing -- the next one supersedes it anyway.
                pendingFrame = frame;
                return;
            }
            indicateInFlight = trySendToAll(frame);
        }
    }

    /** @return true if at least one indicate actually went out, i.e. we
     *  should wait for onNotificationSent before sending the next one. */
    private boolean trySendToAll(byte[] frame) {
        if (gattServer == null || navCharacteristic == null) return false;
        navCharacteristic.setValue(frame);
        boolean sentAny = false;
        synchronized (subscribers) {
            for (BluetoothDevice device : subscribers) {
                try {
                    if (gattServer.notifyCharacteristicChanged(device, navCharacteristic, true)) {
                        sentAny = true;
                    }
                } catch (SecurityException e) {
                    Log.w(TAG, "notifyCharacteristicChanged denied", e);
                }
            }
        }
        return sentAny;
    }

    private void fail(String message) {
        Log.w(TAG, message);
        if (listener != null) {
            mainHandler.post(() -> listener.onError(message));
        }
    }

    private void notifySubscriberCount() {
        int count = subscriberCount();
        if (listener != null) {
            mainHandler.post(() -> listener.onSubscriberCountChanged(count));
        }
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            Log.i(TAG, "Verbindung " + device.getAddress() + " -> " + newState);
            if (newState != BluetoothProfile.STATE_CONNECTED) {
                boolean removed;
                synchronized (subscribers) {
                    removed = subscribers.remove(device);
                }
                if (removed) notifySubscriberCount();
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                 BluetoothGattCharacteristic characteristic) {
            if (!CHAR_UUID.equals(characteristic.getUuid())) {
                safeRespond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
                return;
            }
            byte[] value = lastFrame;
            byte[] response = (offset > 0 && offset < value.length)
                    ? Arrays.copyOfRange(value, offset, value.length)
                    : value;
            safeRespond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                              BluetoothGattDescriptor descriptor, boolean preparedWrite,
                                              boolean responseNeeded, int offset, byte[] value) {
            if (!CCCD_UUID.equals(descriptor.getUuid())) {
                if (responseNeeded) safeRespond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
                return;
            }
            boolean enabling = Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            synchronized (subscribers) {
                if (enabling) {
                    subscribers.add(device);
                } else {
                    subscribers.remove(device);
                }
            }
            Log.i(TAG, (enabling ? "Indicate aktiviert von " : "Indicate deaktiviert von ") + device.getAddress());
            notifySubscriberCount();
            if (responseNeeded) {
                safeRespond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
            if (enabling) {
                // Don't make the ESP32 wait up to HEARTBEAT_INTERVAL_MS for
                // its first frame.
                send(lastFrame);
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            synchronized (BikeComputerGattServer.this) {
                indicateInFlight = false;
                if (pendingFrame != null) {
                    byte[] next = pendingFrame;
                    pendingFrame = null;
                    indicateInFlight = trySendToAll(next);
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            Log.i(TAG, "MTU mit " + device.getAddress() + " ausgehandelt: " + mtu);
        }
    };

    private void safeRespond(BluetoothDevice device, int requestId, int status, int offset, byte[] value) {
        try {
            if (gattServer != null) {
                gattServer.sendResponse(device, requestId, status, offset, value);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "sendResponse denied", e);
        }
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            fail("Advertising fehlgeschlagen, Fehlercode " + errorCode);
        }
    };
}
