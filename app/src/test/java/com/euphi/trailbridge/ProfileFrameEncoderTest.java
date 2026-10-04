package com.euphi.trailbridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProfileFrameEncoderTest {

    @Test
    public void encodesDocumentedLayout() {
        ProfileFrame f = new ProfileFrame(4200, 25, 345, new byte[]{12, 13, -2}, 0);
        byte[] b = ProfileFrameEncoder.encode(f);
        assertArrayEquals(new byte[]{
                1, 1,                                   // version, PROFILE_UPDATE
                1, 4, (byte) 0x68, 0x10, 0, 0,          // START_REMAINING_DISTANCE_M = 4200
                2, 1, 25,                               // STEP_M
                3, 2, 0x59, 0x01,                       // BASE_ALT_DM = 345
                4, 3, 12, 13, -2}, b);
    }

    @Test
    public void coarseRasterCarriesTheDeltaScale() {
        ProfileFrame f = new ProfileFrame(36000, 200, 12000, new byte[]{60, 69, -3}, 2, 0);
        assertArrayEquals(new byte[]{
                1, 1,
                1, 4, (byte) 0xA0, (byte) 0x8C, 0, 0,   // START_REMAINING_DISTANCE_M = 36000
                2, 1, (byte) 200,                       // STEP_M
                3, 2, (byte) 0xE0, 0x2E,                // BASE_ALT_DM = 12000
                5, 1, 2,                                // DELTA_SCALE_DM: deltas in 0.2 m
                4, 3, 60, 69, -3}, ProfileFrameEncoder.encode(f));
        assertEquals(1200 + (60 + 69 - 3) * 0.2f, f.altitudesM()[3], 0.001f);
    }

    @Test
    public void rollingFrameCarriesFlagAndTheClimbsWholeExtent() {
        ProfileFrame.ClimbInfo climb = new ProfileFrame.ClimbInfo(5000, 3500, 1000, 1360);
        ProfileFrame f = new ProfileFrame(4200, 25, 345, new byte[]{12, 13, -2}, 0).asRolling(climb);
        byte[] b = ProfileFrameEncoder.encode(f);
        assertArrayEquals(new byte[]{
                1, 1,
                1, 4, (byte) 0x68, 0x10, 0, 0,
                2, 1, 25,
                3, 2, 0x59, 0x01,
                6, 1, 1,                                            // FLAGS: rolling
                7, 4, (byte) 0x88, 0x13, 0, 0,                      // foot at 5000 m remaining
                8, 4, (byte) 0xAC, 0x0D, 0, 0,                      // summit at 3500 m remaining
                9, 2, (byte) 0xE8, 0x03,                            // foot altitude 100.0 m
                10, 2, 0x50, 0x05,                                  // summit altitude 136.0 m
                4, 3, 12, 13, -2}, b);
        // no climb close: the flag alone
        byte[] none = ProfileFrameEncoder.encode(new ProfileFrame(4200, 25, 345, new byte[]{12, 13, -2}, 0).asRolling(null));
        assertArrayEquals(new byte[]{1, 1, 1, 4, (byte) 0x68, 0x10, 0, 0, 2, 1, 25, 3, 2, 0x59, 0x01, 6, 1, 1, 4, 3, 12, 13, -2}, none);
        // the extra bytes a rolling frame with a climb needs are what ROLLING_EXTRA reserves
        assertEquals(20 + ProfileFrameEncoder.ROLLING_EXTRA, b.length);
    }

    @Test
    public void helloAndNone() {
        assertArrayEquals(new byte[]{1, 0}, ProfileFrameEncoder.hello());
        assertArrayEquals(new byte[]{1, 2}, ProfileFrameEncoder.none());
    }

    @Test
    public void budgetFollowsPayload() {
        assertEquals(0, ProfileFrameEncoder.maxSteps(20));        // MTU 23
        assertEquals(0, ProfileFrameEncoder.maxSteps(20 + 7));    // just under the minimum
        assertEquals(8, ProfileFrameEncoder.maxSteps(20 + 8));
        assertEquals(200, ProfileFrameEncoder.maxSteps(253));     // MTU 256: capped
        int steps = ProfileFrameEncoder.maxSteps(100);
        ProfileFrame f = new ProfileFrame(1, 250, 0, new byte[steps], 2, 0);
        assertEquals(100, ProfileFrameEncoder.encode(f).length);
    }
}
