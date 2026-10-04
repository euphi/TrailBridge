package com.euphi.trailbridge;

/**
 * Plausibility of the UTC time of a GPS fix, before it goes to the BikeComputer as its clock
 * (PROTOCOL.md, UTC_TIME_MS). Right after the GNSS chip was switched on the time of the first
 * fixes can be wrong by minutes up to hours -- on the test ride of 2026-10-04 it was 14.8 h behind
 * (the chip's own clock, stuck at the last time it ran) for the first hour, while the position
 * was fine. The phone's own clock (network time) is the second opinion: a fix whose time does not
 * agree with it sends no time -- better no clock than one that is wrong.
 *
 * Pure, no Android: testable on the JVM.
 */
public final class GpsTime {

    /** The two clocks may differ by this much (BLE latency and rounding are far below). */
    public static final long TOLERANCE_MS = 5000;

    private GpsTime() {
    }

    /**
     * @param fixUtcMs   Location.getTime(), 0 if the fix has none
     * @param fixAgeMs   age of the fix now (elapsedRealtime minus the fix's elapsedRealtime)
     * @param phoneNowMs System.currentTimeMillis()
     * @return fixUtcMs if it fits the phone's clock, else 0 (= "no time", the tag is left out)
     */
    public static long plausibleUtcMs(long fixUtcMs, long fixAgeMs, long phoneNowMs) {
        if (fixUtcMs <= 0) return 0;
        long now = fixUtcMs + fixAgeMs;
        return Math.abs(now - phoneNowMs) <= TOLERANCE_MS ? fixUtcMs : 0;
    }
}
