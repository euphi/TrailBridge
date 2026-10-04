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
 * Hosts four GATT services on one BluetoothGattServer: navigation
 * (from OsmAnd or an imported GPX route), GPS position (straight from the
 * phone's GPS chip, see GpsLink), the elevation profile of an imported
 * route (event-driven, no heartbeat) and the route overview (read only, see
 * `overviewValue`). Android only allows one Indicate/Notify "in flight" per connected
 * device at a time -- across *all* characteristics, since onNotificationSent()
 * doesn't say which one just completed -- so all channels share a single
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
    public static final UUID PROFILE_SERVICE_UUID = UUID.fromString("3c1f6a90-5b2e-4d7a-9c48-e0a1b7d25f63");
    public static final UUID PROFILE_CHAR_UUID = UUID.fromString("a84e0d17-6f3b-4c52-8e9d-1b70c2f4a596");
    public static final UUID OVERVIEW_SERVICE_UUID = UUID.fromString("7ee954a6-7a19-4b48-b052-f00d00e01e58");
    public static final UUID OVERVIEW_CHAR_UUID = UUID.fromString("f031faa3-083d-4818-9eca-38420be835d0");
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

        /** Event-driven channels (profile) don't repeat their last frame. */
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
    private Channel profileChannel;
    private final Map<UUID, Channel> channelsByCharUuid = new HashMap<>();

    // The route overview is only read, never indicated: no Channel, no
    // subscribers. It may be longer than one ATT packet; a device then reads
    // it in pieces (Read Blob), all of which must come from the same value --
    // hence the copy per device, taken when its read starts at offset 0.
    private volatile byte[] overviewValue = OverviewFrameEncoder.hello();
    private final Map<BluetoothDevice, byte[]> overviewReadByDevice = new HashMap<>();

    // Shared in-flight gate across both channels -- see class javadoc.
    private boolean indicateInFlight = false;
    private Channel inFlightChannel = null;
    private final Map<Channel, byte[]> pendingByChannel = new LinkedHashMap<>();

    // Negotiated ATT_MTU per connected device; the smallest one bounds how
    // long a frame we may send (see maxPayload()).
    private final Map<BluetoothDevice, Integer> mtuByDevice = new HashMap<>();

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
                fail(context.getString(R.string.ble_no_peripheral));
                return;
            }

            gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
            if (gattServer == null) {
                fail(context.getString(R.string.ble_gatt_open_failed));
                return;
            }

            navChannel = new Channel(CHAR_UUID, NavFrameEncoder.hello());
            positionChannel = new Channel(POSITION_CHAR_UUID, PositionFrameEncoder.hello());
            profileChannel = new Channel(PROFILE_CHAR_UUID, ProfileFrameEncoder.hello());
            channelsByCharUuid.put(CHAR_UUID, navChannel);
            channelsByCharUuid.put(POSITION_CHAR_UUID, positionChannel);
            channelsByCharUuid.put(PROFILE_CHAR_UUID, profileChannel);

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
            fail(context.getString(R.string.ble_permission_missing, e.getMessage()));
        }
    }

    private void onNavServiceAdded() {
        BluetoothGattService positionService = new BluetoothGattService(
                POSITION_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        positionService.addCharacteristic(positionChannel.characteristic);
        try {
            gattServer.addService(positionService);
        } catch (SecurityException e) {
            fail(context.getString(R.string.ble_add_service_failed, "position", e.getMessage()));
        }
    }

    private void onPositionServiceAdded() {
        BluetoothGattService profileService = new BluetoothGattService(
                PROFILE_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        profileService.addCharacteristic(profileChannel.characteristic);
        try {
            gattServer.addService(profileService);
        } catch (SecurityException e) {
            fail(context.getString(R.string.ble_add_service_failed, "profile", e.getMessage()));
        }
    }

    private void onProfileServiceAdded() {
        BluetoothGattService overviewService = new BluetoothGattService(
                OVERVIEW_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        overviewService.addCharacteristic(new BluetoothGattCharacteristic(
                OVERVIEW_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ));
        try {
            gattServer.addService(overviewService);
        } catch (SecurityException e) {
            fail(context.getString(R.string.ble_add_service_failed, "overview", e.getMessage()));
        }
    }

    private void onOverviewServiceAdded() {
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
            fail(context.getString(R.string.ble_advertise_failed, e.getMessage()));
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
        mtuByDevice.clear();
        synchronized (overviewReadByDevice) {
            overviewReadByDevice.clear();
        }
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
        if (profileChannel != null) {
            synchronized (profileChannel.subscribers) {
                profileChannel.subscribers.clear();
            }
        }
        channelsByCharUuid.clear();
    }

    /**
     * Number of connected devices that subscribed to at least one service --
     * a BikeComputer subscribing to navigation, position and profile is still
     * one subscriber, not three.
     */
    public int subscriberCount() {
        Set<BluetoothDevice> devices = new HashSet<>();
        for (Channel channel : new Channel[]{navChannel, positionChannel, profileChannel}) {
            if (channel == null) continue;
            synchronized (channel.subscribers) {
                devices.addAll(channel.subscribers);
            }
        }
        return devices.size();
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

    /**
     * Sends (or, with null, clears) the elevation profile. Event-driven: the
     * frame is kept for Read and for devices that subscribe later, but not
     * repeated by a heartbeat.
     */
    public void updateProfile(ProfileFrame frame) {
        if (!running) return;
        send(profileChannel, frame == null ? ProfileFrameEncoder.none() : ProfileFrameEncoder.encode(frame));
    }

    /**
     * Sets (or, with null, clears) the route overview the BikeComputer reads.
     * Nothing is sent: the nav frames carry the overview's revision, and a
     * device that sees a new one comes and reads.
     */
    public void updateOverview(byte[] frame) {
        overviewValue = frame == null ? OverviewFrameEncoder.none() : frame;
    }

    /**
     * Largest frame every connected device accepts in one indicate: the
     * smallest negotiated ATT_MTU minus the 3 byte ATT header. Without a
     * known MTU we assume the 256 the BikeComputer firmware requests
     * (setMTU(256)); a device that fell back to the default 23 shows up
     * here as soon as onMtuChanged reports it.
     */
    public int maxPayload() {
        int mtu = 256;
        synchronized (mtuByDevice) {
            for (int m : mtuByDevice.values()) {
                mtu = Math.min(mtu, m);
            }
        }
        return mtu - 3;
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
                if (profileChannel != null) profileChannel.removeSubscriber(device);
                synchronized (mtuByDevice) {
                    mtuByDevice.remove(device);
                }
                synchronized (overviewReadByDevice) {
                    overviewReadByDevice.remove(device);
                }
            }
        }

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(context.getString(R.string.ble_add_service_failed_uuid, service.getUuid(), status));
                return;
            }
            if (SERVICE_UUID.equals(service.getUuid())) {
                onNavServiceAdded();
            } else if (POSITION_SERVICE_UUID.equals(service.getUuid())) {
                onPositionServiceAdded();
            } else if (PROFILE_SERVICE_UUID.equals(service.getUuid())) {
                onProfileServiceAdded();
            } else if (OVERVIEW_SERVICE_UUID.equals(service.getUuid())) {
                onOverviewServiceAdded();
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                 BluetoothGattCharacteristic characteristic) {
            if (OVERVIEW_CHAR_UUID.equals(characteristic.getUuid())) {
                byte[] value;
                synchronized (overviewReadByDevice) {
                    if (offset == 0) overviewReadByDevice.put(device, overviewValue);
                    value = overviewReadByDevice.get(device);
                }
                if (value == null || offset > value.length) {
                    safeRespond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
                    return;
                }
                // The stack cuts the response to what fits into one packet; a full
                // packet makes the device ask for the rest (ending with an empty one
                // if the value is an exact multiple of the packet size).
                safeRespond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));
                return;
            }
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
            synchronized (mtuByDevice) {
                mtuByDevice.put(device, mtu);
            }
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
            fail(context.getString(R.string.ble_advertise_failed_code, errorCode));
        }
    };
}
