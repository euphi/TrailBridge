package com.euphi.trailbridge;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The BLE peripheral (server) side of PROTOCOL.md. The BikeComputer stays the
 * BLE *central* exactly like it was for Komoot -- this class just gives it a
 * new kind of server to talk to.
 *
 * Hosts two independent GATT services on one BluetoothGattServer: navigation
 * (from OsmAnd) and GPS position (straight from the phone's GPS chip, see
 * GpsLink). Android only allows one Indicate/Notify "in flight" per connected
 * device at a time -- across *all* characteristics, since onNotificationSent()
 * doesn't say which one just completed -- so both channels share a single
 * in-flight gate (see `indicateInFlight` / `pendingByChannel`) instead of each
 * tracking their own, which would race.
 *
 * Threading: BluetoothGattServerCallback methods run on a Binder thread, not
 * the main thread. Everything that touches channel state / the in-flight gate
 * is synchronized; nothing here touches Android UI directly.
 */
public class BikeComputerGattServer {

    private static final String TAG = "BikeGatt";

    public static final UUID SERVICE_UUID = UUID.fromString("f7ac2b76-986b-45fd-8e44-f116a61f319d");
    public static final UUID CHAR_UUID = UUID.fromString("7473da02-2de8-4f48-9e46-21b36380c176");
    public static final UUID POSITION_SERVICE_UUID = UUID.fromString("66b5835c-9be6-43d1-b24a-f9337c0fcb7f");
    public static final UUID POSITION_CHAR_UUID = UUID.fromString("10c49e7b-4808-4d63-9b68-9ba6c385db0d");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long HEARTBEAT_INTERVAL_MS = 5000L;

    /**
     * Safety net: Android's BluetoothGattServer.onNotificationSent() is not
     * always reliably delivered (observed: indicateInFlight then stays stuck
     * true forever, silently blackholing every future send() for every
     * subscriber, old or new, until the app is restarted). If no callback
     * arrives within this long, force-clear the flag and retry instead of
     * waiting forever.
     */
    private static final long INDICATE_TIMEOUT_MS = 3000L;

    public interface Listener {
        void onSubscriberCountChanged(int count);

        void onError(String message);
    }

    /** One GATT characteristic + its subscribers + its own heartbeat. Sending
     *  still goes through the outer class's shared in-flight gate. */
    private final class Channel {
        final BluetoothGattCharacteristic characteristic;
        final Set<BluetoothDevice> subscribers = new HashSet<>();
        volatile byte[] lastFrame;

        final Runnable heartbeat = new Runnable() {
            @Override
            public void run() {
                send(Channel.this, lastFrame);
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
            }
        };

        Channel(UUID charUuid, byte[] initialFrame) {
            this.lastFrame = initialFrame;
            characteristic = new BluetoothGattCharacteristic(
                    charUuid,
                    BluetoothGattCharacteristic.PROPERTY_INDICATE | BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
            characteristic.addDescriptor(cccd);
            characteristic.setValue(initialFrame);
        }

        void removeSubscriber(BluetoothDevice device) {
            boolean removed;
            synchronized (subscribers) {
                removed = subscribers.remove(device);
            }
            if (removed) notifySubscriberCount();
        }
    }

    private final Context context;
    private final Listener listener;
    private final BluetoothManager bluetoothManager;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;

    private Channel navChannel;
    private Channel positionChannel;
    private final Map<UUID, Channel> channelsByCharUuid = new HashMap<>();

    // Shared in-flight gate across both channels -- see class javadoc.
    private boolean indicateInFlight = false;
    private Channel inFlightChannel = null;
    private final Map<Channel, byte[]> pendingByChannel = new LinkedHashMap<>();

    private boolean running = false;

    private final Runnable indicateTimeout = this::onIndicateTimeout;

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

            navChannel = new Channel(CHAR_UUID, NavFrameEncoder.hello());
            positionChannel = new Channel(POSITION_CHAR_UUID, PositionFrameEncoder.hello());
            channelsByCharUuid.put(CHAR_UUID, navChannel);
            channelsByCharUuid.put(POSITION_CHAR_UUID, positionChannel);

            running = true;
            // Services must be added one at a time -- some BLE stacks silently
            // drop a second addService() call made before onServiceAdded() for
            // the first one fires. Advertising + heartbeats only start once
            // both are confirmed registered (see onServiceAdded below).
            BluetoothGattService navService = new BluetoothGattService(
                    SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            navService.addCharacteristic(navChannel.characteristic);
            gattServer.addService(navService);
        } catch (SecurityException e) {
            // Missing BLUETOOTH_ADVERTISE/CONNECT at runtime on API 31+.
            fail("Fehlende Bluetooth-Berechtigung: " + e.getMessage());
        }
    }

    private void onNavServiceAdded() {
        BluetoothGattService positionService = new BluetoothGattService(
                POSITION_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        positionService.addCharacteristic(positionChannel.characteristic);
        try {
            gattServer.addService(positionService);
        } catch (SecurityException e) {
            fail("addService (Position) fehlgeschlagen: " + e.getMessage());
        }
    }

    private void onPositionServiceAdded() {
        // Not advertised (31-byte legacy limit, see PROTOCOL.md) -- the
        // BikeComputer finds it via GATT service discovery after connecting
        // through the nav service's advertisement below.
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
        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback);
        } catch (SecurityException e) {
            fail("startAdvertising fehlgeschlagen: " + e.getMessage());
            return;
        }
        mainHandler.postDelayed(navChannel.heartbeat, HEARTBEAT_INTERVAL_MS);
        mainHandler.postDelayed(positionChannel.heartbeat, HEARTBEAT_INTERVAL_MS);
    }

    public void stop() {
        running = false;
        if (navChannel != null) mainHandler.removeCallbacks(navChannel.heartbeat);
        if (positionChannel != null) mainHandler.removeCallbacks(positionChannel.heartbeat);
        mainHandler.removeCallbacks(indicateTimeout);
        synchronized (this) {
            indicateInFlight = false;
            inFlightChannel = null;
            pendingByChannel.clear();
        }
        try {
            if (advertiser != null) advertiser.stopAdvertising(advertiseCallback);
        } catch (Exception ignored) {
        }
        try {
            if (gattServer != null) gattServer.close();
        } catch (Exception ignored) {
        }
        if (navChannel != null) {
            synchronized (navChannel.subscribers) {
                navChannel.subscribers.clear();
            }
        }
        if (positionChannel != null) {
            synchronized (positionChannel.subscribers) {
                positionChannel.subscribers.clear();
            }
        }
        channelsByCharUuid.clear();
    }

    public int subscriberCount() {
        int count = 0;
        if (navChannel != null) {
            synchronized (navChannel.subscribers) {
                count += navChannel.subscribers.size();
            }
        }
        if (positionChannel != null) {
            synchronized (positionChannel.subscribers) {
                count += positionChannel.subscribers.size();
            }
        }
        return count;
    }

    /** Called from OsmAndLink whenever a new NavState is ready. */
    public void update(NavState state) {
        if (!running) return;
        send(navChannel, NavFrameEncoder.encode(state));
    }

    /** Called from GpsLink whenever a new GPS fix (or loss of fix) is ready. */
    public void updatePosition(PositionState state) {
        if (!running) return;
        send(positionChannel, PositionFrameEncoder.encode(state));
    }

    private void send(Channel channel, byte[] frame) {
        channel.lastFrame = frame;
        synchronized (this) {
            if (indicateInFlight) {
                // Coalesce: only the newest pending frame per channel matters
                // once the in-flight one is confirmed. Skipping an
                // intermediate ETA/position tick costs nothing -- the next
                // one supersedes it anyway.
                pendingByChannel.put(channel, frame);
                return;
            }
            beginSend(channel, frame);
        }
    }

    /** Caller must hold the monitor lock (synchronized(this)). */
    private void beginSend(Channel channel, byte[] frame) {
        boolean sentAny = trySendToAll(channel, frame);
        if (sentAny) {
            indicateInFlight = true;
            inFlightChannel = channel;
            mainHandler.postDelayed(indicateTimeout, INDICATE_TIMEOUT_MS);
        } else {
            // Nobody subscribed to *this* channel right now -- the platform
            // in-flight gate was never actually engaged, so don't block
            // whatever else might be waiting.
            indicateInFlight = false;
            inFlightChannel = null;
            drainPendingLocked();
        }
    }

    /** Caller must hold the monitor lock. Sends the next queued frame (if
     *  any) across either channel. */
    private void drainPendingLocked() {
        if (pendingByChannel.isEmpty()) return;
        Iterator<Map.Entry<Channel, byte[]>> it = pendingByChannel.entrySet().iterator();
        Map.Entry<Channel, byte[]> next = it.next();
        it.remove();
        beginSend(next.getKey(), next.getValue());
    }

    private void onIndicateTimeout() {
        synchronized (this) {
            if (!indicateInFlight) return; // onNotificationSent already handled it
            Log.w(TAG, "onNotificationSent nicht innerhalb " + INDICATE_TIMEOUT_MS + "ms angekommen -- setze zurück und versuche erneut");
            Channel channel = inFlightChannel;
            indicateInFlight = false;
            inFlightChannel = null;
            byte[] retryFrame = pendingByChannel.remove(channel);
            if (retryFrame == null) retryFrame = channel.lastFrame;
            beginSend(channel, retryFrame);
        }
    }

    /** @return true if at least one indicate actually went out, i.e. we
     *  should wait for onNotificationSent before sending the next one. */
    private boolean trySendToAll(Channel channel, byte[] frame) {
        if (gattServer == null) return false;
        channel.characteristic.setValue(frame);
        boolean sentAny = false;
        synchronized (channel.subscribers) {
            for (BluetoothDevice device : channel.subscribers) {
                try {
                    if (gattServer.notifyCharacteristicChanged(device, channel.characteristic, true)) {
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
                if (navChannel != null) navChannel.removeSubscriber(device);
                if (positionChannel != null) positionChannel.removeSubscriber(device);
            }
        }

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("addService fehlgeschlagen für " + service.getUuid() + ", Status " + status);
                return;
            }
            if (SERVICE_UUID.equals(service.getUuid())) {
                onNavServiceAdded();
            } else if (POSITION_SERVICE_UUID.equals(service.getUuid())) {
                onPositionServiceAdded();
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                 BluetoothGattCharacteristic characteristic) {
            Channel channel = channelsByCharUuid.get(characteristic.getUuid());
            if (channel == null) {
                safeRespond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
                return;
            }
            byte[] value = channel.lastFrame;
            byte[] response = (offset > 0 && offset < value.length)
                    ? Arrays.copyOfRange(value, offset, value.length)
                    : value;
            safeRespond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                              BluetoothGattDescriptor descriptor, boolean preparedWrite,
                                              boolean responseNeeded, int offset, byte[] value) {
            Channel channel = channelsByCharUuid.get(descriptor.getCharacteristic().getUuid());
            if (channel == null || !CCCD_UUID.equals(descriptor.getUuid())) {
                if (responseNeeded) safeRespond(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
                return;
            }
            boolean enabling = Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            synchronized (channel.subscribers) {
                if (enabling) {
                    channel.subscribers.add(device);
                } else {
                    channel.subscribers.remove(device);
                }
            }
            Log.i(TAG, (enabling ? "Indicate aktiviert von " : "Indicate deaktiviert von ")
                    + device.getAddress() + " (" + descriptor.getCharacteristic().getUuid() + ")");
            notifySubscriberCount();
            if (responseNeeded) {
                safeRespond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }
            if (enabling) {
                // Don't make the ESP32 wait up to HEARTBEAT_INTERVAL_MS for
                // its first frame.
                send(channel, channel.lastFrame);
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            synchronized (BikeComputerGattServer.this) {
                mainHandler.removeCallbacks(indicateTimeout);
                indicateInFlight = false;
                inFlightChannel = null;
                drainPendingLocked();
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
