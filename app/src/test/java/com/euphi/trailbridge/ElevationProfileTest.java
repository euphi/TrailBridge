package com.euphi.trailbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ElevationProfileTest {

    private static final double[][] NORTH_2KM = {{0, 0}, {0, 2000}};

    /** Average grade (rise/run) over the 200 m from a distance on. */
    private static double grade(ElevationProfile p, double fromM) {
        return (p.altitudeAt(fromM + 200) - p.altitudeAt(fromM)) / 200;
    }

    @Test
    public void noElevationNoProfile() {
        assertNull(ElevationProfile.of(Routes.polyline(NORTH_2KM, null)));
    }

    @Test
    public void rampGrade() {
        ElevationProfile p = ElevationProfile.of(Routes.polyline(NORTH_2KM, d -> d * 0.05));
        assertNotNull(p);
        assertEquals(0.05, grade(p, 500), 0.002);
        assertEquals(0.05, grade(p, 0), 0.005);
        assertEquals(100, p.totalAscentM(), 3);
    }

    @Test
    public void flatIsFlat() {
        ElevationProfile p = ElevationProfile.of(Routes.polyline(NORTH_2KM, d -> 100));
        assertEquals(0, grade(p, 500), 1e-6);
        assertEquals(0, p.totalAscentM());
    }

    @Test
    public void noiseIsSmoothed() {
        // +-3 m zig-zag on a flat road must not look like a climb
        int[] i = {0};
        ElevationProfile p = ElevationProfile.of(Routes.polyline(NORTH_2KM, d -> 100 + ((i[0]++ % 2) * 6 - 3)));
        for (double s = 100; s < 1800; s += 50) {
            assertEquals(0, grade(p, s), 0.03);
        }
    }

    @Test
    public void sliceOfARamp() {
        GpxRoute r = Routes.polyline(NORTH_2KM, d -> 100 + d * 0.05);
        ElevationProfile p = ElevationProfile.of(r);
        ProfileFrame f = p.slice(500, 1500, 40, 8, 0);
        assertNotNull(f);
        assertEquals(40, f.deltasDm.length);
        assertEquals(25, f.stepM);
        // anchor: remaining distance at the first sample (500 m along)
        assertEquals(r.totalM - 500, f.startRemainingM, 1);
        assertEquals((100 + 500 * 0.05) * 10, f.baseAltDm, 3);
        int sum = 0;
        for (byte b : f.deltasDm) {
            sum += b;
            assertEquals(12.5, b, 1.0);   // 5 % of 25 m = 1.25 m = 12.5 dm
        }
        assertEquals(40 * 25 * 0.05 * 10, sum, 3);
    }

    @Test
    public void sliceStopsAtRouteEndOrIsNull() {
        ElevationProfile p = ElevationProfile.of(Routes.polyline(NORTH_2KM, d -> d * 0.05));
        ProfileFrame f = p.slice(1900, 2000, 200, 8, 0);
        assertNull(f);   // only ~4 samples left, below the minimum
        ProfileFrame g = p.slice(1500, 2000, 200, 8, 0);
        assertEquals(20, g.deltasDm.length);
    }

    // ---- climbs ----

    /** Altitude over distance from (distance m, altitude m) corner points, linear in between. */
    private static java.util.function.DoubleUnaryOperator through(double[][] pts) {
        return d -> {
            for (int i = 1; i < pts.length; i++) {
                if (d <= pts[i][0]) {
                    double t = (d - pts[i - 1][0]) / (pts[i][0] - pts[i - 1][0]);
                    return pts[i - 1][1] + t * (pts[i][1] - pts[i - 1][1]);
                }
            }
            return pts[pts.length - 1][1];
        };
    }

    private static ElevationProfile profile(double[][] pts) {
        double length = pts[pts.length - 1][0];
        return ElevationProfile.of(Routes.polyline(new double[][]{{0, 0}, {0, length}}, through(pts)));
    }

    /** Galibier from the north: Télégraphe, almost 5 km gently down to Valloire, then the Galibier. */
    private static final double[][] GALIBIER_NORTH = {
            {0, 710}, {2000, 710}, {14000, 1566}, {18800, 1400}, {36800, 2642}, {42000, 2280}};

    /**
     * Galibier from the south (Briançon, Lautaret): 36 km, starting gently, with false flats, a
     * level kilometre and a short dip on the way.
     */
    private static final double[][] GALIBIER_SOUTH = {
            {0, 1200}, {2000, 1200}, {4000, 1260}, {10000, 1400}, {16000, 1475}, {17000, 1475},
            {22000, 1700}, {22400, 1685}, {29500, 2058}, {38000, 2642}, {43000, 2300}};

    @Test
    public void flatAndNoiseHaveNoClimbs() {
        assertTrue(profile(new double[][]{{0, 100}, {5000, 100}}).climbs().isEmpty());
        int[] i = {0};
        ElevationProfile p = ElevationProfile.of(Routes.polyline(new double[][]{{0, 0}, {0, 5000}},
                d -> 100 + ((i[0]++ % 2) * 6 - 3)));
        assertTrue(p.climbs().isEmpty());
    }

    @Test
    public void gentleStartBelongsToTheClimb() {
        // 3 % for 500 m before it gets steep: the foot is where the 3 % begin
        ElevationProfile p = profile(new double[][]{{0, 100}, {1000, 100}, {1500, 115}, {2500, 185}, {4000, 100}});
        List<ElevationProfile.Climb> c = p.climbs();
        assertEquals(1, c.size());
        assertEquals(1000, c.get(0).footM, 75);
        assertEquals(2500, c.get(0).summitM, 50);
        assertEquals(85, c.get(0).gainM(), 4);
    }

    @Test
    public void shortDipAndFlatStayInOneClimb() {
        // 20 m down in the middle, later 1.5 km level: neither is a summit
        ElevationProfile p = profile(new double[][]{{0, 100}, {500, 100}, {2500, 220}, {2900, 200},
                {4900, 320}, {6400, 320}, {8400, 440}, {10000, 300}});
        List<ElevationProfile.Climb> c = p.climbs();
        assertEquals(1, c.size());
        assertEquals(500, c.get(0).footM, 75);
        assertEquals(8400, c.get(0).summitM, 50);
    }

    @Test
    public void descentOrLongFlatIsASummit() {
        // 40 m down after the first top, 3 km level after the second
        ElevationProfile p = profile(new double[][]{{0, 100}, {500, 100}, {2500, 220}, {3300, 180},
                {5300, 300}, {8300, 300}, {10300, 420}, {11000, 420}});
        List<ElevationProfile.Climb> c = p.climbs();
        assertEquals(3, c.size());
        assertEquals(2500, c.get(0).summitM, 50);
        assertEquals(3300, c.get(1).footM, 75);
        assertEquals(5300, c.get(1).summitM, 75);
        assertEquals(8300, c.get(2).footM, 75);
        assertEquals(10300, c.get(2).summitM, 75);
    }

    @Test
    public void galibierNorthIsTwoClimbs() {
        List<ElevationProfile.Climb> c = profile(GALIBIER_NORTH).climbs();
        assertEquals(2, c.size());
        assertEquals(2000, c.get(0).footM, 75);
        assertEquals(14000, c.get(0).summitM, 50);     // Col du Télégraphe
        assertEquals(18800, c.get(1).footM, 75);       // Valloire
        assertEquals(36800, c.get(1).summitM, 50);
    }

    @Test
    public void galibierSouthIsOneClimb() {
        List<ElevationProfile.Climb> c = profile(GALIBIER_SOUTH).climbs();
        assertEquals(1, c.size());
        assertEquals(2000, c.get(0).footM, 75);
        assertEquals(38000, c.get(0).summitM, 50);
        assertEquals(1442, c.get(0).gainM(), 5);
    }

    @Test
    public void longClimbFitsOneFrameOnACoarserRaster() {
        ElevationProfile p = profile(GALIBIER_SOUTH);
        ElevationProfile.Climb climb = p.climbs().get(0);
        ProfileFrame f = p.slice(1510, climb.summitM, 200, 8, 0);
        assertNotNull(f);
        assertEquals(200, f.stepM);                    // 36.5 km in at most 200 steps
        assertTrue(f.deltasDm.length <= 200);
        assertTrue(f.deltaScaleDm > 1);                // 6.9 % of 200 m does not fit a byte of dm
        // the last point is the summit, the first at most one step behind the rider
        assertEquals(climb.summitM, f.startAlongM + f.lengthM(), 0.01);
        assertTrue(f.startAlongM <= 1510 && f.startAlongM > 1510 - f.stepM);
        float[] alt = f.altitudesM();
        for (int k = 0; k < alt.length; k++) {
            assertEquals(p.altitudeAt(f.startAlongM + k * f.stepM), alt[k], 0.05 * f.deltaScaleDm + 0.06);
        }
        assertEquals(climb.summitAltM, alt[alt.length - 1], 0.2);
    }

    @Test
    public void tooLongForTheCoarsestRasterIsCutOffAndKeepsItsRaster() {
        ElevationProfile p = profile(GALIBIER_SOUTH);
        double summitM = p.climbs().get(0).summitM;
        ProfileFrame a = p.slice(2110, summitM, 40, 8, 0);     // 40 steps of 250 m: 10 km of 36
        ProfileFrame b = p.slice(7630, summitM, 40, 8, a.stepM);
        ProfileFrame c = p.slice(33010, summitM, 40, 8, a.stepM);   // the rest would fit a finer raster
        assertEquals(250, a.stepM);
        assertEquals(40, a.deltasDm.length);
        assertTrue(a.startAlongM <= 2110 && a.startAlongM > 2110 - 250);
        assertEquals(0, (a.startRemainingM - b.startRemainingM) % 250);
        assertEquals(250, c.stepM);
        assertEquals(0, (a.startRemainingM - c.startRemainingM) % 250);
        assertEquals(summitM, c.startAlongM + c.lengthM(), 0.01);
    }
}
