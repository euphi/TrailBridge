package com.euphi.trailbridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
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

    /** Whether location updates are currently requested. */
    public boolean isRunning() {
        return requested;
    }

    public GpsLink(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    /** Needs ACCESS_FINE_LOCATION: without it this only sets a status text, and
     *  the caller (TrailBridgeService.startGps) tries again once it is granted. */
    public void start() {
        if (requested) return;
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            setStatus(appContext.getString(R.string.gps_permission_missing));
            return;
        }
        locationManager = (LocationManager) appContext.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            setStatus(appContext.getString(R.string.gps_no_manager));
            return;
        }
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, MIN_TIME_MS, MIN_DISTANCE_M,
                    locationListener, Looper.getMainLooper());
            requested = true;
            setStatus(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? appContext.getString(R.string.gps_waiting_fix)
                    : appContext.getString(R.string.gps_provider_off));
        } catch (SecurityException e) {
            setStatus(appContext.getString(R.string.gps_permission_denied, e.getMessage()));
        } catch (IllegalArgumentException e) {
            setStatus(appContext.getString(R.string.gps_no_provider));
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
            setStatus(appContext.getString(R.string.gps_provider_enabled));
        }

        @Override
        public void onProviderDisabled(String provider) {
            setStatus(appContext.getString(R.string.gps_provider_disabled));
            listener.onPositionUpdate(PositionState.NONE);
        }
    };

    private static PositionState toPositionState(Location loc) {
        // Height above sea level (not above the ellipsoid like getAltitude()): Android 14 and later.
        boolean hasMsl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && loc.hasMslAltitude();
        int mslDm = hasMsl ? (int) Math.round(loc.getMslAltitudeMeters() * 10.0) : 0;
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
                loc.getElapsedRealtimeNanos() / 1_000_000L,
                // UTC of the fix; for GPS_PROVIDER this is the satellite time -- if it agrees with
                // the phone's own clock, else the phone's time (GpsTime): the chip's first fixes
                // after being switched on can carry a time that is hours off.
                utcTimeMs(loc),
                false, 0, false, 0, false, 0, false, 0, 0,
                hasMsl, mslDm);
    }

    private static long utcTimeMs(Location loc) {
        long ageMs = SystemClock.elapsedRealtime() - loc.getElapsedRealtimeNanos() / 1_000_000L;
        long utc = GpsTime.utcMs(loc.getTime(), ageMs, System.currentTimeMillis());
        if (GpsTime.replaced(loc.getTime(), utc)) {
            Log.w(TAG, "GPS time of the fix is off the phone clock: the phone's time goes out as UTC_TIME_MS");
        }
        return utc;
    }
}
