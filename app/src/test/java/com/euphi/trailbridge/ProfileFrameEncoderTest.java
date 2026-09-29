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
    public void helloAndNone() {
        assertArrayEquals(new byte[]{1, 0}, ProfileFrameEncoder.hello());
        assertArrayEquals(new byte[]{1, 2}, ProfileFrameEncoder.none());
    }

    @Test
    public void budgetFollowsPayload() {
        assertEquals(0, ProfileFrameEncoder.maxSteps(20));        // MTU 23
        assertEquals(0, ProfileFrameEncoder.maxSteps(17 + 7));    // just under the minimum
        assertEquals(8, ProfileFrameEncoder.maxSteps(17 + 8));
        assertEquals(200, ProfileFrameEncoder.maxSteps(253));     // MTU 256: capped
        int steps = ProfileFrameEncoder.maxSteps(100);
        ProfileFrame f = new ProfileFrame(1, 25, 0, new byte[steps], 0);
        assertEquals(100, ProfileFrameEncoder.encode(f).length);
    }
}
