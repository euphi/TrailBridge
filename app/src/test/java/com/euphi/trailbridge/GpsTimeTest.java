package com.euphi.trailbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GpsTimeTest {

    private static final long NOW = 1_791_109_700_000L;     // 2026-10-04 10:28:20 UTC

    @Test
    public void aTimeThatFitsThePhoneClockIsKept() {
        assertEquals(NOW - 300, GpsTime.utcMs(NOW - 300, 300, NOW));
        assertEquals(NOW - 2000 - 900, GpsTime.utcMs(NOW - 2000 - 900, 2000, NOW + 1100));
        assertFalse(GpsTime.replaced(NOW - 300, GpsTime.utcMs(NOW - 300, 300, NOW)));
    }

    @Test
    public void theStaleChipTimeOfTheTestRideIsReplacedByThePhoneTime() {
        long gps = NOW - 53_350_119L;
        long sent = GpsTime.utcMs(gps, 200, NOW);
        assertEquals(NOW - 200, sent);                      // the receiver adds the age: its sum is the phone's now
        assertEquals(NOW, sent + 200);
        assertTrue(GpsTime.replaced(gps, sent));
    }

    @Test
    public void tenMinutesOffIsReplacedToo() {
        assertEquals(NOW - 200, GpsTime.utcMs(NOW - 600_000, 200, NOW));
        assertEquals(NOW - 200, GpsTime.utcMs(NOW + 600_000, 200, NOW));
    }

    @Test
    public void aFixWithoutATimeGetsThePhoneTime() {
        assertEquals(NOW - 100, GpsTime.utcMs(0, 100, NOW));
        assertEquals(NOW - 100, GpsTime.utcMs(-5, 100, NOW));
    }

    @Test
    public void theEdgeOfTheToleranceStillCounts() {
        assertEquals(NOW - GpsTime.TOLERANCE_MS, GpsTime.utcMs(NOW - GpsTime.TOLERANCE_MS, 0, NOW));
        assertEquals(NOW, GpsTime.utcMs(NOW - GpsTime.TOLERANCE_MS - 1, 0, NOW));
    }

    @Test
    public void withoutAPhoneClockTheGpsTimeStandsAlone() {
        assertEquals(NOW - 53_350_119L, GpsTime.utcMs(NOW - 53_350_119L, 200, 1_000_000L));
        assertEquals(0, GpsTime.utcMs(0, 200, 1_000_000L));
    }
}
