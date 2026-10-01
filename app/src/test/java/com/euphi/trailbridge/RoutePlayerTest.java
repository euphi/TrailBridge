package com.euphi.trailbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class RoutePlayerTest {

    private static final double[][] STRAIGHT_5K = {{0, 0}, {0, 5000}};

    /** The straight route with time / heart rate / cadence per point where asked for (10 m point spacing). */
    private static GpxRoute recorded(double[][] corners, java.util.function.DoubleUnaryOperator ele,
                                     java.util.function.DoubleUnaryOperator seconds,
                                     java.util.function.DoubleUnaryOperator hr,
                                     java.util.function.DoubleUnaryOperator cad) {
        GpxRoute base = Routes.polyline(corners, ele);
        int n = base.pointCount();
        long[] time = seconds == null ? null : new long[n];
        short[] h = hr == null ? null : new short[n];
        short[] c = cad == null ? null : new short[n];
        for (int i = 0; i < n; i++) {
            if (time != null) time[i] = 1_700_000_000_000L + Math.round(seconds.applyAsDouble(base.cum[i]) * 1000);
            if (h != null) h[i] = (short) Math.round(hr.applyAsDouble(base.cum[i]));
            if (c != null) c[i] = (short) Math.round(cad.applyAsDouble(base.cum[i]));
        }
        return new GpxRoute("rec", base.lat, base.lon, base.ele, time, h, c, new ArrayList<GpxRoute.Step>(), false);
    }

    private static double averageKmh(RoutePlayer p, GpxRoute r) {
        return r.totalM / p.durationS() * 3.6;
    }

    @Test
    public void fileTimestampsDriveTheRide() {
        // 5 km at a steady 25 km/h
        double v = 25 / 3.6;
        GpxRoute r = recorded(STRAIGHT_5K, null, d -> d / v, null, null);
        RoutePlayer p = new RoutePlayer(r, 1);
        assertEquals(RoutePlayer.Source.GPX, p.speedSource());
        assertEquals(5000 / v, p.durationS(), 2);
        p.seek(2500);
        RoutePlayer.Sample s = p.step(0, true);
        assertEquals(2500, s.distM, 1);
        assertEquals(v, s.speedMs, 0.1);
    }

    @Test
    public void longStopsInARecordingAreCut() {
        // 1 km, a 10 minute stop at 500 m, 1 km on: the ride is 20 minutes of riding + max. 30 s standing
        double v = 5;
        GpxRoute r = recorded(new double[][]{{0, 0}, {0, 1000}},
                null, d -> d < 500 ? d / v : 100 + 600 + (d - 500) / v, null, null);
        // all points up to 500 m and from there on, but the stop is one 600 s gap between two points
        RoutePlayer p = new RoutePlayer(r, 1);
        assertEquals(200 + RoutePlayer.MAX_PAUSE_S, p.durationS(), 3);
    }

    @Test
    public void withoutTimestampsSpeedIsMadeUpAndPlausible() {
        GpxRoute r = Routes.polyline(STRAIGHT_5K, null);
        RoutePlayer p = new RoutePlayer(r, 1);
        assertEquals(RoutePlayer.Source.EMULATED, p.speedSource());
        // a bit under the 20 km/h cruising speed because of the start and the arrival
        double avg = averageKmh(p, r);
        assertTrue("avg " + avg, avg > 17 && avg < 20.1);
        // standing at the start, standing at the end, never absurdly fast
        double max = 0;
        p.seek(0);
        assertEquals(0, p.step(0, true).speedMs, 0.01);
        while (!p.finished()) {
            max = Math.max(max, p.step(1, true).speedMs);
        }
        assertTrue("max " + max, max <= 20 / 3.6 + 0.3);
        assertEquals(0, p.step(1, true).speedMs, 0.01);
        assertEquals(5000, p.distanceM(), 0.5);
    }

    @Test
    public void uphillIsSlowerThanDownhill() {
        // 3 km up 8 %, then 3 km down 8 %
        GpxRoute r = Routes.polyline(new double[][]{{0, 0}, {0, 6000}},
                d -> d < 3000 ? d * 0.08 : (6000 - d) * 0.08);
        RoutePlayer p = new RoutePlayer(r, 1);
        p.seek(1500);
        double up = p.step(0, true).speedMs;
        p.seek(4500);
        double down = p.step(0, true).speedMs;
        assertTrue("up " + up + " down " + down, up < 14 / 3.6);
        assertTrue("up " + up + " down " + down, down > 28 / 3.6);
    }

    @Test
    public void sharpCornersAreTakenSlowly() {
        GpxRoute r = Routes.polyline(new double[][]{{0, 0}, {0, 1000}, {1000, 1000}}, null);
        RoutePlayer p = new RoutePlayer(r, 1);
        p.seek(1000);
        assertTrue(p.step(0, true).speedMs < 4.0);
        p.seek(500);
        assertTrue(p.step(0, true).speedMs > 5.0);
    }

    @Test
    public void seekAndPause() {
        GpxRoute r = Routes.polyline(STRAIGHT_5K, null);
        RoutePlayer p = new RoutePlayer(r, 1);
        p.seek(3000);
        assertEquals(3000, p.distanceM(), 0.5);
        RoutePlayer.Sample moving = p.step(0, true);
        assertTrue(moving.speedMs > 3);
        // paused: the clock stands, speed 0, cadence 0
        double t = p.timeS();
        RoutePlayer.Sample paused = p.step(10, false);
        assertEquals(t, p.timeS(), 1e-9);
        assertEquals(0, paused.speedMs, 1e-9);
        assertEquals(0, paused.cad);
        // seeking is clamped
        p.seek(-50);
        assertEquals(0, p.distanceM(), 0.01);
        p.seek(99999);
        assertEquals(5000, p.distanceM(), 0.5);
        assertTrue(p.finished());
    }

    @Test
    public void positionAndBearingFollowTheRoute() {
        GpxRoute r = Routes.polyline(new double[][]{{0, 0}, {0, 1000}, {1000, 1000}}, null);
        RoutePlayer p = new RoutePlayer(r, 1);
        p.seek(500);
        RoutePlayer.Sample s = p.step(0, true);
        assertEquals(Routes.lat(500), s.lat, 1e-6);
        assertEquals(Routes.lon(0), s.lon, 1e-6);
        assertEquals(0, s.bearingDeg, 1);        // north
        p.seek(1500);
        s = p.step(0, true);
        assertEquals(Routes.lat(1000), s.lat, 1e-6);
        assertEquals(Routes.lon(500), s.lon, 1e-5);
        assertEquals(90, s.bearingDeg, 1);       // east
    }

    @Test
    public void sensorsFromTheFileAreUsedAsTheyAre() {
        GpxRoute r = recorded(STRAIGHT_5K, null, d -> d / 5, d -> 100 + d / 100, d -> 80);
        RoutePlayer p = new RoutePlayer(r, 1);
        p.setEmulation(true, true, true);     // makes no difference: the file has the values
        assertEquals(RoutePlayer.Source.GPX, p.heartRateSource());
        assertEquals(RoutePlayer.Source.GPX, p.cadenceSource());
        p.seek(2000);
        RoutePlayer.Sample s = p.step(0, true);
        assertEquals(120, s.hr);
        assertEquals(80, s.cad);
        p.setEmulation(false, false, false);
        s = p.step(0, true);
        assertEquals(120, s.hr);
        assertEquals(80, s.cad);
    }

    @Test
    public void gapsInTheFilesSensorValuesAreBridged() {
        GpxRoute base = Routes.polyline(STRAIGHT_5K, null);
        int n = base.pointCount();
        short[] hr = new short[n];
        java.util.Arrays.fill(hr, GpxRoute.NO_VALUE);
        for (int i = 0; i < n; i++) {
            if (i % 10 != 5) hr[i] = (short) (100 + i / 10);       // 10 % dropouts
        }
        GpxRoute r = new GpxRoute("x", base.lat, base.lon, base.ele, null, hr, null,
                new ArrayList<GpxRoute.Step>(), false);
        RoutePlayer p = new RoutePlayer(r, 1);
        p.setEmulation(false, false, false);
        assertEquals(RoutePlayer.Source.GPX, p.heartRateSource());
        assertEquals(RoutePlayer.Source.NONE, p.cadenceSource());
        p.seek(55);                        // right on a dropout
        assertEquals(100, p.step(0, true).hr, 1);
    }

    @Test
    public void missingSensorsAreEmulatedOrAbsent() {
        GpxRoute r = Routes.polyline(STRAIGHT_5K, null);
        RoutePlayer p = new RoutePlayer(r, 42);
        p.setEmulation(false, false, false);
        assertEquals(RoutePlayer.Source.NONE, p.heartRateSource());
        p.seek(2000);
        RoutePlayer.Sample s = p.step(0, true);
        assertEquals(-1, s.hr);
        assertEquals(-1, s.cad);

        p.setEmulation(true, true, true);
        assertEquals(RoutePlayer.Source.EMULATED, p.heartRateSource());
        Set<Integer> hrs = new HashSet<>();
        Set<Integer> cads = new HashSet<>();
        p.seek(300);
        for (int i = 0; i < 200; i++) {
            s = p.step(1, true);
            assertTrue("hr " + s.hr, s.hr >= 90 && s.hr <= 160);
            assertTrue("cad " + s.cad, s.cad >= 60 && s.cad <= 100);
            hrs.add(s.hr);
            cads.add(s.cad);
        }
        assertTrue("heart rate varies", hrs.size() > 3);
        assertTrue("cadence varies", cads.size() > 3);
    }

    @Test
    public void emulatedHeartRateFollowsTheEffort() {
        // flat, then a steep climb
        GpxRoute r = Routes.polyline(new double[][]{{0, 0}, {0, 4000}}, d -> d < 2000 ? 100 : 100 + (d - 2000) * 0.10);
        RoutePlayer p = new RoutePlayer(r, 7);
        p.seek(500);
        double flat = 0;
        for (int i = 0; i < 120; i++) flat = p.step(1, true).hr;
        p.seek(2200);
        double steep = 0;
        for (int i = 0; i < 120; i++) steep = p.step(1, true).hr;
        assertTrue("flat " + flat + " steep " + steep, steep > flat + 20);
    }

    @Test
    public void emulatedCadenceIsZeroWhenStandingOrCoasting() {
        // steep descent in the middle
        GpxRoute r = Routes.polyline(new double[][]{{0, 0}, {0, 3000}}, d -> d < 1000 ? 500 : d < 2000 ? 500 - (d - 1000) * 0.08 : 420);
        RoutePlayer p = new RoutePlayer(r, 3);
        p.seek(1500);
        assertEquals(0, p.step(0, true).cad);                  // coasting
        p.seek(500);
        assertTrue(p.step(0, true).cad > 0);                   // pedalling on the flat
        assertEquals(0, p.step(0, false).cad);                 // paused
    }

    @Test
    public void rideEndsAtTheEndOfTheRoute() {
        GpxRoute r = Routes.polyline(new double[][]{{0, 0}, {0, 300}}, null);
        RoutePlayer p = new RoutePlayer(r, 1);
        assertFalse(p.finished());
        for (int i = 0; i < 1000 && !p.finished(); i++) p.step(1, true);
        assertTrue(p.finished());
        RoutePlayer.Sample s = p.step(1, true);
        assertEquals(300, s.distM, 0.5);
        assertEquals(0, s.speedMs, 1e-9);
    }

    @Test
    public void routeWithoutLengthDoesNotBreak() {
        GpxRoute r = new GpxRoute("same", new double[]{52.5, 52.5}, new double[]{13.4, 13.4},
                new float[]{Float.NaN, Float.NaN}, new ArrayList<GpxRoute.Step>(), false);
        RoutePlayer p = new RoutePlayer(r, 1);
        RoutePlayer.Sample s = p.step(1, true);
        assertEquals(0, s.distM, 1e-9);
        assertTrue(p.finished());
    }

    @Test
    public void powerFromTheFileIsUsed() {
        GpxRoute base = Routes.polyline(STRAIGHT_5K, null);
        short[] power = new short[base.pointCount()];
        for (int i = 0; i < power.length; i++) power[i] = (short) (i < 250 ? 200 : 0);   // first 2.5 km 200 W, then coasting
        GpxRoute r = new GpxRoute("x", base.lat, base.lon, base.ele, null, null, null, power,
                new ArrayList<GpxRoute.Step>(), false);
        RoutePlayer p = new RoutePlayer(r, 1);
        p.setEmulation(false, false, false);
        assertEquals(RoutePlayer.Source.GPX, p.powerSource());
        p.seek(1000);
        assertEquals(200, p.step(0, true).power);
        p.seek(4000);
        assertEquals(0, p.step(0, true).power);
        assertEquals(0, p.step(0, false).power);                // paused
    }

    @Test
    public void emulatedPowerFollowsSpeedAndGradeAndIsZeroWhenCoasting() {
        // flat 2 km, 8 % climb for 1 km, steep descent for 1 km
        GpxRoute r = Routes.polyline(new double[][]{{0, 0}, {0, 4000}},
                d -> d < 2000 ? 100 : d < 3000 ? 100 + (d - 2000) * 0.08 : 180 - (d - 3000) * 0.08);
        RoutePlayer p = new RoutePlayer(r, 5);
        assertEquals(RoutePlayer.Source.EMULATED, p.powerSource());
        p.seek(800);
        int flat = 0;
        for (int i = 0; i < 30; i++) flat = p.step(1, true).power;
        p.seek(2300);
        int climb = 0;
        for (int i = 0; i < 30; i++) climb = p.step(1, true).power;
        assertTrue("flat " + flat, flat > 40 && flat < 150);
        assertTrue("climb " + climb + " flat " + flat, climb > 1.5 * flat && climb < 500);
        p.seek(3500);
        assertEquals(0, p.step(0, true).power);                 // coasting down
        assertEquals(0, p.step(0, false).power);                // standing

        p.setEmulation(true, true, false);
        assertEquals(RoutePlayer.Source.NONE, p.powerSource());
        p.seek(800);
        assertEquals(-1, p.step(0, true).power);
    }

    @Test
    public void heightIsTheRouteElevation() {
        GpxRoute r = Routes.polyline(STRAIGHT_5K, d -> 100 + d * 0.01);
        RoutePlayer p = new RoutePlayer(r, 1);
        assertTrue(p.hasHeight());
        p.seek(2500);
        assertEquals(125, p.step(0, true).eleM, 1.0);
        RoutePlayer flat = new RoutePlayer(Routes.polyline(STRAIGHT_5K, null), 1);
        assertFalse(flat.hasHeight());
        assertTrue(Double.isNaN(flat.step(0, true).eleM));
    }
}
