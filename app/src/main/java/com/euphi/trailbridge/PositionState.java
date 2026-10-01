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

    /** UTC time of the fix, ms since the Unix epoch (Location.getTime()), 0 if
     *  unknown. Sent as UTC_TIME_MS so the BikeComputer can set its clock
     *  without WLAN/NTP. */
    public final long fixUtcTimeMs;

    /** Heart rate and cadence of a sensor TrailBridge knows about -- today only
     *  the simulated ones of the GPX playback (see RoutePlayer, simFlags); the
     *  phone's GPS chip has no such sensors. Same value kind either way: whether it's
     *  real or made up is what simFlags says. */
    public final boolean hasHeartRate;
    public final int heartRateBpm;
    public final boolean hasCadence;
    public final int cadenceRpm;

    /** Barometer-style height in decimetres above sea level (NHN), more precise than
     *  altitudeM -- the BikeComputer derives its gradient from it. Today only simulated. */
    public final boolean hasBaroHeight;
    public final int baroHeightDm;

    /** Pedalling power in watts (0 = coasting), only simulated today. */
    public final boolean hasPower;
    public final int powerW;

    /** SIM_* bits (0 = everything real): what of this fix is made up, see PROTOCOL.md. */
    public final int simFlags;

    /** The position (lat/lon/altitude/bearing) is made up, not from the GPS chip. */
    public static final int SIM_POSITION = 0x01;
    /** SPEED_CMS is the speed of a simulated wheel sensor, heart rate / cadence are
     *  simulated, and an absent heart rate / cadence means "no such sensor". */
    public static final int SIM_SENSORS = 0x02;

    public static final PositionState NONE =
            new PositionState(false, 0, 0, false, 0, false, 0, false, 0, false, 0, 0L, 0L);

    public PositionState(boolean hasFix, int latitudeE7, int longitudeE7,
                          boolean hasAltitude, int altitudeM,
                          boolean hasSpeed, int speedCms,
                          boolean hasBearing, int bearingDegX100,
                          boolean hasAccuracy, int accuracyMx10,
                          long fixElapsedRealtimeMs, long fixUtcTimeMs) {
        this(hasFix, latitudeE7, longitudeE7, hasAltitude, altitudeM, hasSpeed, speedCms,
                hasBearing, bearingDegX100, hasAccuracy, accuracyMx10,
                fixElapsedRealtimeMs, fixUtcTimeMs, false, 0, false, 0, false, 0, false, 0, 0);
    }

    public PositionState(boolean hasFix, int latitudeE7, int longitudeE7,
                          boolean hasAltitude, int altitudeM,
                          boolean hasSpeed, int speedCms,
                          boolean hasBearing, int bearingDegX100,
                          boolean hasAccuracy, int accuracyMx10,
                          long fixElapsedRealtimeMs, long fixUtcTimeMs,
                          boolean hasHeartRate, int heartRateBpm,
                          boolean hasCadence, int cadenceRpm,
                          boolean hasBaroHeight, int baroHeightDm, boolean hasPower, int powerW,
                          int simFlags) {
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
        this.fixUtcTimeMs = fixUtcTimeMs;
        this.hasHeartRate = hasHeartRate;
        this.heartRateBpm = heartRateBpm;
        this.hasCadence = hasCadence;
        this.cadenceRpm = cadenceRpm;
        this.hasBaroHeight = hasBaroHeight;
        this.baroHeightDm = baroHeightDm;
        this.hasPower = hasPower;
        this.powerW = powerW;
        this.simFlags = simFlags;
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
        if (hasHeartRate) s.append(", ").append(heartRateBpm).append("bpm");
        if (hasCadence) s.append(", ").append(cadenceRpm).append("rpm");
        if (hasPower) s.append(", ").append(powerW).append("W");
        if (hasBaroHeight) s.append(", Höhe ").append(baroHeightDm / 10.0).append("m");
        if (simFlags != 0) s.append(", SIMULIERT");
        return s.append("}").toString();
    }
}
