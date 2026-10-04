package com.euphi.trailbridge;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.util.Collections;

public class NavFrameEncoderTest {

    private static NavState state() {
        return new NavState(true, Maneuver.TURN_LEFT, 120, 0, "Bahnhofstraße", Collections.<Lane>emptyList(), 0,
                Maneuver.KEEP_RIGHT, 300, "", Collections.<Lane>emptyList(), 0, 4200, 600);
    }

    private static final byte[] DOCUMENTED = {
            1, 1,
            1, 1, 5,
            2, 4, 0x78, 0, 0, 0,
            4, 14, 'B', 'a', 'h', 'n', 'h', 'o', 'f', 's', 't', 'r', 'a', (byte) 0xC3, (byte) 0x9F, 'e',
            5, 1, 11,
            6, 4, 0x2C, 1, 0, 0,
            7, 0,
            8, 4, 0x68, 0x10, 0, 0,
            9, 4, 0x58, 2, 0, 0};

    @Test
    public void encodesDocumentedLayout() {
        assertArrayEquals(DOCUMENTED, NavFrameEncoder.encode(state()));
    }

    @Test
    public void overviewRevisionIsAppendedOnlyIfThereIsAnOverview() {
        byte[] withRevision = java.util.Arrays.copyOf(DOCUMENTED, DOCUMENTED.length + 3);
        withRevision[DOCUMENTED.length] = 0x0E;
        withRevision[DOCUMENTED.length + 1] = 1;
        withRevision[DOCUMENTED.length + 2] = 3;
        assertArrayEquals(withRevision, NavFrameEncoder.encode(state().withOverviewRevision(3)));
        assertArrayEquals(DOCUMENTED, NavFrameEncoder.encode(state().withOverviewRevision(0)));
    }
}
