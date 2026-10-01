package com.euphi.trailbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProfileFrameTest {

    /** The example of PROTOCOL.md: start at 34.5 m, three steps of +1.2 m, +1.2 m, -0.4 m. */
    private static ProfileFrame sample() {
        return new ProfileFrame(4200, 25, 345, new byte[]{12, 12, -4}, 0);
    }

    @Test
    public void altitudesAreTheRunningSumOfTheDeltas() {
        float[] alt = sample().altitudesM();
        assertEquals(4, alt.length);
        assertEquals(34.5f, alt[0], 0.001f);
        assertEquals(35.7f, alt[1], 0.001f);
        assertEquals(36.9f, alt[2], 0.001f);
        assertEquals(36.5f, alt[3], 0.001f);
    }

    @Test
    public void altitudeIsInterpolatedAndClamped() {
        ProfileFrame f = sample();
        assertEquals(35.1, f.altitudeAtM(12.5), 0.001);
        assertEquals(34.5, f.altitudeAtM(-30), 0.001);
        assertEquals(36.5, f.altitudeAtM(999), 0.001);
    }

    @Test
    public void riderOffsetIsStartRemainingMinusRemaining() {
        ProfileFrame f = sample();   // 75 m long
        assertEquals(0, f.riderOffsetM(4200), 0.001);
        assertEquals(50, f.riderOffsetM(4150), 0.001);
        assertEquals(75, f.riderOffsetM(4125), 0.001);
    }

    @Test
    public void riderOffsetToleratesOneStepAndClamps() {
        ProfileFrame f = sample();
        assertEquals(0, f.riderOffsetM(4212), 0.001);    // 12 m before the first point
        assertEquals(75, f.riderOffsetM(4110), 0.001);   // 15 m behind the last
    }

    @Test
    public void riderOffsetIsNaNWhenTheProfileIsStaleOrNotReached() {
        ProfileFrame f = sample();
        assertTrue(Double.isNaN(f.riderOffsetM(4300)));   // still 100 m before the profile
        assertTrue(Double.isNaN(f.riderOffsetM(4000)));   // long past it
    }
}
