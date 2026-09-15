package com.euphi.trailbridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Reads GPS fixes straight from Android's LocationManager (GPS_PROVIDER),
 * deliberately not through OsmAnd or a fused/network provider -- this is
 * what lets TrailBridge relay a live position even when OsmAnd isn't running
 * at all, per the user's request.
 */
public class GpsLink {

    private static final String TAG = "GpsLink";
    private static final long MIN_TIME_MS = 1000L;
    private static final float MIN_DISTANCE_M = 0f;

    public interface Listener {
        void onGpsStatusChanged(String status);

        void onPositionUpdate(PositionState state);
    }

    private final Context appContext;
    private final Listener listener;
    private LocationManager locationManager;
    private boolean requested = false;

    public GpsLink(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    /** Requires ACCESS_FINE_LOCATION to already be granted -- caller (see
     *  MainActivity) must have requested it first, same pattern as the
     *  Bluetooth permissions before BikeComputerGattServer.start(). */
    public void start() {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            setStatus("GPS: Berechtigung fehlt (ACCESS_FINE_LOCATION)");
            return;
        }
        locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            setStatus("GPS: kein LocationManager auf diesem Gerät");
            return;
        }
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M,
                    locationListener, Looper.getMainLooper());
            requested = true;
            setStatus(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? "GPS: warte auf ersten Fix"
                    : "GPS: Ortungsdienst (GPS) ist deaktiviert");
        } catch (SecurityException e) {
            setStatus("GPS: Berechtigung verweigert: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            setStatus("GPS: kein GPS-Provider auf diesem Gerät");
        }
    }

    public void stop() {
        if (locationManager != null && requested) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
        }
        requested = false;
        locationManager = null;
    }

    private void setStatus(String status) {
        Log.i(TAG, status);
        if (listener != null) {
            listener.onGpsStatusChanged(status);
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            listener.onPositionUpdate(toPositionState(location));
        }

        @Override
        public void onProviderEnabled(String provider) {
            setStatus("GPS: Ortungsdienst aktiviert, warte auf Fix");
        }

        @Override
        public void onProviderDisabled(String provider) {
            setStatus("GPS: Ortungsdienst deaktiviert");
            listener.onPositionUpdate(PositionState.NONE);
        }
    };

    private static PositionState toPositionState(Location loc) {
        return new PositionState(
                true,
                (int) Math.round(loc.getLatitude() * 1e7),
                (int) Math.round(loc.getLongitude() * 1e7),
                loc.hasAltitude(), (int) Math.round(loc.getAltitude()),
                loc.hasSpeed(), Math.round(loc.getSpeed() * 100f),
                loc.hasBearing(), Math.round(loc.getBearing() * 100f) % 36000,
                loc.hasAccuracy(), Math.round(loc.getAccuracy() * 10f),
                // Same elapsed-realtime base as SystemClock.elapsedRealtime(),
                // so PositionFrameEncoder can compute a correct age even on a
                // heartbeat resend, not just at the moment of the fix.
                loc.getElapsedRealtimeNanos() / 1_000_000L);
    }
}
