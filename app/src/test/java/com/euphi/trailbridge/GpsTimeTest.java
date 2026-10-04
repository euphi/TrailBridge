package com.euphi.trailbridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GpsTimeTest {

    private static final long NOW = 1_791_109_700_000L;     // 2026-10-04 10:28:20 UTC

    @Test
    public void aTimeThatFitsThePhoneClockIsKept() {
        assertEquals(NOW - 300, GpsTime.plausibleUtcMs(NOW - 300, 300, NOW));
        assertEquals(NOW - 2000 - 900, GpsTime.plausibleUtcMs(NOW - 2000 - 900, 2000, NOW + 1100));
    }

    @Test
    public void theStaleChipTimeOfTheTestRideIsDropped() {
        long fourteenPointEightHours = 53_350_119L;
        assertEquals(0, GpsTime.plausibleUtcMs(NOW - fourteenPointEightHours, 200, NOW));
    }

    @Test
    public void tenMinutesOffIsDroppedToo() {
        assertEquals(0, GpsTime.plausibleUtcMs(NOW - 600_000, 200, NOW));
        assertEquals(0, GpsTime.plausibleUtcMs(NOW + 600_000, 200, NOW));
    }

    @Test
    public void noTimeStaysNoTime() {
        assertEquals(0, GpsTime.plausibleUtcMs(0, 100, NOW));
        assertEquals(0, GpsTime.plausibleUtcMs(-5, 100, NOW));
    }

    @Test
    public void theEdgeOfTheToleranceStillCounts() {
        assertEquals(NOW - GpsTime.TOLERANCE_MS, GpsTime.plausibleUtcMs(NOW - GpsTime.TOLERANCE_MS, 0, NOW));
        assertEquals(0, GpsTime.plausibleUtcMs(NOW - GpsTime.TOLERANCE_MS - 1, 0, NOW));
    }
}
