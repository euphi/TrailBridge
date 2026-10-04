package com.euphi.trailbridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class OverviewFrameEncoderTest {

    /** 42 km straight north, flat -- the climbs are handed in. */
    private static GpxRoute base() {
        return Routes.polyline(new double[][]{{0, 0}, {0, 42000}}, null);
    }

    private static ElevationProfile.Climb climb(double footM, double lengthM, float gainM) {
        return new ElevationProfile.Climb(footM, footM + lengthM, 500, 500 + gainM);
    }

    /** The texts the wire examples below are written with. */
    private static final RouteTexts GERMAN = new RouteTexts("Abseits der Route", "Ziel", "Wegpunkt %d");

    @Test
    public void encodesDocumentedLayout() {
        GpxRoute b = base();
        GpxRoute r = Routes.withWaypoints(b,
                new GpxRoute.Waypoint(b.totalM - 30500, "Bäcker"),
                new GpxRoute.Waypoint(b.totalM - 12000, ""));
        // the file's time stamps: 5 m/s all the way
        RouteOverview o = new RouteOverview(r, Arrays.asList(climb(2000, 3000, 180), climb(b.totalM - 22000, 8200, 450)),
                RouteTimes.of(Routes.withTimes(r, d -> d / 5)), GERMAN);
        o.update(6000);                                 // past the first climb
        assertArrayEquals(new byte[]{
                1, 1,                                                       // version, OVERVIEW
                1, 1, 3,                                                    // REVISION = 3
                2, 1, 2,                                                    // WAYPOINTS_AHEAD = 2
                3, 1, 2,                                                    // CLIMBS_TOTAL = 2
                4, 13, 1, 0, 0, 0, 0, 0, 0, 0, 0, 'Z', 'i', 'e', 'l',        // destination
                4, 16, 0, 0x24, 0x77, 0, 0, (byte) 0xD4, 0x17, 0, 0,        // 30500 m, 6100 s before it
                'B', (byte) 0xC3, (byte) 0xA4, 'c', 'k', 'e', 'r',
                5, 11, 2, (byte) 0xC2, 0x01, 0x08, 0x20, 0, 0,              // climb 2: 450 m up, 8200 m long,
                (byte) 0xF0, 0x55, 0, 0,                                    // its foot 22000 m before the destination
                4, 19, 0, (byte) 0xE0, 0x2E, 0, 0, 0x60, 0x09, 0, 0,        // 12000 m, 2400 s
                'W', 'e', 'g', 'p', 'u', 'n', 'k', 't', ' ', '2'},
                OverviewFrameEncoder.encode(o, 3));
    }

    @Test
    public void helloAndNone() {
        assertArrayEquals(new byte[]{1, 0}, OverviewFrameEncoder.hello());
        assertArrayEquals(new byte[]{1, 2}, OverviewFrameEncoder.none());
    }

    @Test
    public void routeWithoutWaypointsOrClimbsIsJustTheDestination() {
        // no time stamps in the file: the time anchor says "unknown"
        RouteOverview o = new RouteOverview(base(), Collections.<ElevationProfile.Climb>emptyList(), null, GERMAN);
        assertArrayEquals(new byte[]{1, 1, 1, 1, 7, 2, 1, 0, 3, 1, 0,
                        4, 13, 1, 0, 0, 0, 0, -1, -1, -1, -1, 'Z', 'i', 'e', 'l'},
                OverviewFrameEncoder.encode(o, 7));
    }

    @Test
    public void whatDoesNotFitIsCutOffAtTheFarEnd() {
        GpxRoute b = base();
        GpxRoute.Waypoint[] wps = new GpxRoute.Waypoint[40];
        for (int i = 0; i < wps.length; i++) {
            // longer than the 32 bytes a name may have, the "ß" straddling the cut
            wps[i] = new GpxRoute.Waypoint(1000 * (i + 1), "Verpflegung bei der langen Straße " + i);
        }
        List<ElevationProfile.Climb> climbs = new ArrayList<>();
        climbs.add(climb(1500, 400, 30));
        RouteOverview o = new RouteOverview(Routes.withWaypoints(b, wps), climbs, null, GERMAN);
        byte[] f = OverviewFrameEncoder.encode(o, 1);
        assertTrue(f.length <= 512);

        // walk the TLVs: every one is complete, the names are cut at a character
        int waypoints = 0;
        int climbsSeen = 0;
        int lastRemaining = Integer.MAX_VALUE;
        int ahead = -1;
        for (int i = 2; i < f.length; i += 2 + (f[i + 1] & 0xFF)) {
            int len = f[i + 1] & 0xFF;
            assertTrue(i + 2 + len <= f.length);
            if (f[i] == OverviewFrameEncoder.TAG_WAYPOINTS_AHEAD) ahead = f[i + 2] & 0xFF;
            if (f[i] == OverviewFrameEncoder.TAG_CLIMB) {
                climbsSeen++;
                assertEquals(1, waypoints - 1);           // foot at 1500 m: after the waypoint at 1000 m
            }
            if (f[i] == OverviewFrameEncoder.TAG_WAYPOINT && f[i + 2] == 0) {
                waypoints++;
                int remaining = (f[i + 3] & 0xFF) | (f[i + 4] & 0xFF) << 8 | (f[i + 5] & 0xFF) << 16;
                assertTrue(remaining < lastRemaining);     // nearest first
                lastRemaining = remaining;
                String name = new String(f, i + 11, len - 9, java.nio.charset.StandardCharsets.UTF_8);
                assertEquals("Verpflegung bei der langen Stra", name);
            } else if (f[i] == OverviewFrameEncoder.TAG_WAYPOINT) {
                waypoints++;                               // the destination, always there
            }
        }
        assertEquals(40, ahead);                           // the count says there are more than were sent
        assertEquals(1, climbsSeen);
        assertTrue(waypoints > 10 && waypoints - 1 < 40);
    }
}
