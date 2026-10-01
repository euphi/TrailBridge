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

    @Test
    public void profileIsSentForTheClimbAndCleared() {
        RouteNavigator n = new RouteNavigator(lRoute());
        assertNull(fixAt(n, 0, 100).profile);          // flat ahead
        RouteNavigator.Result r = fixAt(n, 0, 350);    // the 200 m ahead now hold enough of the ramp
        assertNotNull(r.profile);
        assertFalse(r.clearProfile);
        // anchored at the current position: remaining = route length - 350
        assertEquals(1000 - 350, r.profile.startRemainingM, 30);
        assertNull(fixAt(n, 0, 370).profile);           // no resend while inside the sent stretch

        // beyond the crest (route distance 1000, at east=500 on the second leg)
        RouteNavigator.Result end = null;
        for (double e = 0; e <= 500; e += 25) {
            RouteNavigator.Result x = fixAt(n, e, 500);
            if (x.clearProfile) end = x;
        }
        assertNotNull(end);
    }

    @Test
    public void nextStretchIsSentWhenTheRiderPassesTheMiddle() {
        GpxRoute r = Routes.polyline(new double[][]{{0, 0}, {0, 4000}}, d -> d * 0.06);
        RouteNavigator n = new RouteNavigator(r);
        // 60 byte payload -> 43 steps (1075 m) per frame, so several are needed
        RouteNavigator.Result first = n.onFix(Routes.lat(100), Routes.lon(0), 4, 60);
        assertNotNull(first.profile);
        int sentSteps = first.profile.deltasDm.length;
        boolean resent = false;
        for (double s = 100; s < 4000 && !resent; s += 20) {
            RouteNavigator.Result x = n.onFix(Routes.lat(s), Routes.lon(0), 4, 60);
            if (x.profile != null) {
                resent = true;
                assertTrue(s > 100 + sentSteps * 25 / 2.0 - 1);
                assertTrue(s < 100 + sentSteps * 25);       // before the display runs dry
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
