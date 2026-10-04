package com.euphi.trailbridge;

/**
 * The UTC time that goes to the BikeComputer as its clock (PROTOCOL.md, UTC_TIME_MS). Right after
 * the GNSS chip was switched on the time of the first fixes can be wrong by minutes up to hours --
 * on the test ride of 2026-10-04 it was 14.8 h behind (the chip's own clock, stuck at the last
 * time it ran) for the first hour, while the position was fine. The phone's own clock (network
 * time) is the second opinion: a GPS time that agrees with it is sent as it is (it is the better
 * one, sent from the fix); one that does not is replaced by the phone's time. The BikeComputer
 * checks the result once more (ClockSync::offerGpsTime) -- it knows its own clock.
 *
 * Pure, no Android: testable on the JVM.
 */
public final class GpsTime {

    /** The two clocks may differ by this much (BLE latency and rounding are far below). */
    public static final long TOLERANCE_MS = 5000;
    /** A phone clock before 2023-01-01 is not set. */
    private static final long MIN_PHONE_MS = 1_672_531_200_000L;

    private GpsTime() {
    }

    /**
     * @param fixUtcMs   Location.getTime(), 0 if the fix has none
     * @param fixAgeMs   age of the fix now (elapsedRealtime minus the fix's elapsedRealtime)
     * @param phoneNowMs System.currentTimeMillis()
     * @return the time of the fix in the frame's sense (UTC of the fix; the receiver adds the
     *         fix age): the GPS time if it fits the phone's clock, else the phone's clock then
     *         minus the age. 0 = no usable time at all (the tag is left out).
     */
    public static long utcMs(long fixUtcMs, long fixAgeMs, long phoneNowMs) {
        if (phoneNowMs < MIN_PHONE_MS) return fixUtcMs > 0 ? fixUtcMs : 0;   // no second opinion
        if (fixUtcMs > 0 && Math.abs(fixUtcMs + fixAgeMs - phoneNowMs) <= TOLERANCE_MS) return fixUtcMs;
        return phoneNowMs - fixAgeMs;
    }

    /** True if utcMs() replaced the GPS time (for the log). */
    public static boolean replaced(long fixUtcMs, long chosenUtcMs) {
        return fixUtcMs != chosenUtcMs;
    }
}
