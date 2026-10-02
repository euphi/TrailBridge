package com.euphi.trailbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RouteNavigatorTest {

    /** North 500 m, east 500 m; flat for 400 m, then a 6 % climb for 600 m, then flat. */
    private static GpxRoute lRoute() {
        return Routes.navRoute(new double[][]{{0, 0}, {0, 500}, {500, 500}}, d -> {
            if (d < 400) return 100;
            if (d < 1000) return 100 + (d - 400) * 0.06;
            return 136;
        });
    }

    private static RouteNavigator.Result fixAt(RouteNavigator n, double east, double north) {
        return n.onFix(Routes.lat(north), Routes.lon(east), 4.0, 253);
    }

    @Test
    public void followsTheRoute() {
        RouteNavigator n = new RouteNavigator(lRoute());
        RouteNavigator.Result r = fixAt(n, 0, 200);
        assertFalse(r.offRoute);
        assertEquals(Maneuver.TURN_RIGHT, r.nav.maneuver);
        assertEquals(300, r.nav.maneuverDistanceM, 15);
        assertEquals(Maneuver.ARRIVE, r.nav.nextManeuver);
        assertEquals(500, r.nav.nextManeuverDistanceM, 15);
        assertEquals(800, r.nav.remainingDistanceM, 5);

        r = fixAt(n, 200, 500);
        assertEquals(Maneuver.ARRIVE, r.nav.maneuver);
        assertEquals(300, r.nav.maneuverDistanceM, 5);
        assertEquals(Maneuver.NONE, r.nav.nextManeuver);
        assertEquals(300, r.nav.remainingDistanceM, 5);
    }

    @Test
    public void startShowsDepartThenTheTurn() {
        RouteNavigator n = new RouteNavigator(lRoute());
        assertEquals(Maneuver.DEPART, fixAt(n, 0, 0).nav.maneuver);
        assertEquals(Maneuver.TURN_RIGHT, fixAt(n, 0, 30).nav.maneuver);
    }

    @Test
    public void offRouteAndBack() {
        RouteNavigator n = new RouteNavigator(lRoute());
        fixAt(n, 0, 100);
        RouteNavigator.Result r = fixAt(n, 120, 100);
        assertTrue(r.offRoute);
        assertEquals(Maneuver.UNKNOWN, r.nav.maneuver);
        assertEquals(120, r.nav.maneuverDistanceM, 5);
        r = fixAt(n, 5, 150);
        assertFalse(r.offRoute);
        assertEquals(Maneuver.TURN_RIGHT, r.nav.maneuver);
    }

    /** North 1200 m, east 1200 m; flat for 1000 m, then a 6 % climb for 600 m, then flat. */
    private static GpxRoute climbRoute() {
        return Routes.navRoute(new double[][]{{0, 0}, {0, 1200}, {1200, 1200}}, d -> {
            if (d < 1000) return 100;
            if (d < 1600) return 100 + (d - 1000) * 0.06;
            return 136;
        });
    }

    /** A fix at a distance along climbRoute(). */
    private static RouteNavigator.Result fixAlong(RouteNavigator n, double d) {
        return d <= 1200 ? fixAt(n, 0, d) : fixAt(n, d - 1200, 1200);
    }

    @Test
    public void profileIsSentAheadOfTheClimbUpToItsSummit() {
        RouteNavigator n = new RouteNavigator(climbRoute());
        assertNull(fixAlong(n, 300).profile);           // foot still 700 m away
        RouteNavigator.Result r = fixAlong(n, 550);     // within APPROACH_M of the foot
        assertNotNull(r.profile);
        assertFalse(r.clearProfile);
        // anchored at the current position, ending at the summit
        assertEquals(2400 - 550, r.profile.startRemainingM, 15);
        assertEquals(1600, r.profile.startAlongM + r.profile.lengthM(), 60);
        assertEquals(136, r.profile.altitudeAtM(r.profile.lengthM()), 1);
        assertEquals(25, r.profile.stepM);

        // one frame for the whole climb: nothing more until the summit
        RouteNavigator.Result x = null;
        double d = 560;
        for (; d < 2400; d += 20) {
            x = fixAlong(n, d);
            assertNull(x.profile);
            if (x.clearProfile) break;
        }
        assertTrue(x.clearProfile);
        assertEquals(1600, d, 80);
        // GPS jitter back over the summit does not bring the profile back
        x = fixAlong(n, d - 40);
        assertNull(x.profile);
        assertFalse(x.clearProfile);
    }

    @Test
    public void joiningMidClimbSendsTheRest() {
        RouteNavigator n = new RouteNavigator(climbRoute());
        RouteNavigator.Result r = fixAlong(n, 1250);
        assertNotNull(r.profile);
        assertEquals(1600, r.profile.startAlongM + r.profile.lengthM(), 60);
        // off the route: profile taken back; back on it: sent again
        assertTrue(fixAt(n, 150, 900).clearProfile);
        assertNotNull(fixAlong(n, 1300).profile);
    }

    @Test
    public void nextStretchIsSentWhenTheRiderPassesTheMiddle() {
        GpxRoute r = Routes.polyline(new double[][]{{0, 0}, {0, 30000}}, d -> d * 0.06);
        RouteNavigator n = new RouteNavigator(r);
        // 60 byte payload -> 40 steps, 10 km per frame even at 250 m: the 30 km need several
        RouteNavigator.Result first = n.onFix(Routes.lat(100), Routes.lon(0), 4, 60);
        assertNotNull(first.profile);
        assertEquals(250, first.profile.stepM);
        int sentM = first.profile.lengthM();
        assertEquals(10000, sentM);
        boolean resent = false;
        for (double s = 100; s < 30000 && !resent; s += 50) {
            RouteNavigator.Result x = n.onFix(Routes.lat(s), Routes.lon(0), 4, 60);
            if (x.profile != null) {
                resent = true;
                assertTrue(s > sentM / 2.0 - 1);
                assertTrue(s < sentM);                       // before the display runs dry
                // same raster, so the BikeComputer can join the two
                assertEquals(0, (first.profile.startRemainingM - x.profile.startRemainingM) % 250);
            }
        }
        assertTrue(resent);
    }

    @Test
    public void smallMtuSendsNoProfile() {
        RouteNavigator n = new RouteNavigator(Routes.polyline(new double[][]{{0, 0}, {0, 2000}}, d -> d * 0.06));
        RouteNavigator.Result r = n.onFix(Routes.lat(100), Routes.lon(0), 4, 20);
        assertNull(r.profile);
    }

    @Test
    public void noElevationNoProfile() {
        RouteNavigator n = new RouteNavigator(Routes.polyline(new double[][]{{0, 0}, {0, 2000}}, null));
        assertNull(fixAt(n, 0, 100).profile);
    }

    @Test
    public void seekToJumpsFarAheadAndRestartsTheProfile() {
        RouteNavigator n = new RouteNavigator(lRoute());
        assertNotNull(fixAt(n, 0, 350).profile);        // climb ahead: profile sent
        n.seekTo(700);                                   // east leg, 200 m after the turn
        RouteNavigator.Result r = fixAt(n, 200, 500);
        assertFalse(r.offRoute);
        assertEquals(Maneuver.ARRIVE, r.nav.maneuver);
        assertEquals(300, r.nav.remainingDistanceM, 5);
        assertEquals(700, n.progressM(), 5);
        // seeking back into the climb: a fresh profile is sent again
        n.seekTo(300);
        assertNotNull(fixAt(n, 0, 350).profile);
    }

    @Test
    public void seekToWorksOnARouteThatDoublesBack() {
        // out 1000 m north and straight back on the same line: a matching window around the
        // wrong half would find the "near" return leg; seekTo must pin the half we mean
        GpxRoute r = Routes.polyline(new double[][]{{0, 0}, {0, 1000}, {0, 0}}, null);
        RouteNavigator n = new RouteNavigator(r);
        n.seekTo(1500);                                  // on the way back, at north = 500
        fixAt(n, 0, 500);
        assertEquals(1500, n.progressM(), 10);
    }
}
