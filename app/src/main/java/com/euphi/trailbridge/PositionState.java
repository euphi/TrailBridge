package com.euphi.trailbridge;

/**
 * One GPS fix snapshot, decoupled from android.location.Location the same
 * way NavState is decoupled from AppInfoParams -- GpsLink builds this from
 * whatever LocationManager hands it, PositionFrameEncoder only knows this
 * type.
 */
public final class PositionState {

    public final boolean hasFix;

    public final int latitudeE7;
    public final int longitudeE7;

    public final boolean hasAltitude;
    public final int altitudeM;

    public final boolean hasSpeed;
    public final int speedCms;

    public final boolean hasBearing;
    public final int bearingDegX100;

    public final boolean hasAccuracy;
    public final int accuracyMx10;

    /** SystemClock.elapsedRealtime() timestamp of the fix -- used to compute
     *  FIX_AGE_MS fresh at send time, including on heartbeat resends. */
    public final long fixElapsedRealtimeMs;

    public static final PositionState NONE =
            new PositionState(false, 0, 0, false, 0, false, 0, false, 0, false, 0, 0L);

    public PositionState(boolean hasFix, int latitudeE7, int longitudeE7,
                          boolean hasAltitude, int altitudeM,
                          boolean hasSpeed, int speedCms,
                          boolean hasBearing, int bearingDegX100,
                          boolean hasAccuracy, int accuracyMx10,
                          long fixElapsedRealtimeMs) {
        this.hasFix = hasFix;
        this.latitudeE7 = latitudeE7;
        this.longitudeE7 = longitudeE7;
        this.hasAltitude = hasAltitude;
        this.altitudeM = altitudeM;
        this.hasSpeed = hasSpeed;
        this.speedCms = speedCms;
        this.hasBearing = hasBearing;
        this.bearingDegX100 = bearingDegX100;
        this.hasAccuracy = hasAccuracy;
        this.accuracyMx10 = accuracyMx10;
        this.fixElapsedRealtimeMs = fixElapsedRealtimeMs;
    }

    @Override
    public String toString() {
        if (!hasFix) return "PositionState{kein Fix}";
        StringBuilder s = new StringBuilder("PositionState{")
                .append(latitudeE7 / 1e7).append(", ").append(longitudeE7 / 1e7);
        if (hasAltitude) s.append(", ").append(altitudeM).append("m");
        if (hasSpeed) s.append(", ").append(speedCms / 100.0).append("m/s");
        if (hasBearing) s.append(", ").append(bearingDegX100 / 100.0).append("°");
        if (hasAccuracy) s.append(", ±").append(accuracyMx10 / 10.0).append("m");
        return s.append("}").toString();
    }
}
