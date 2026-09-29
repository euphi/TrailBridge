package com.euphi.trailbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ElevationProfileTest {

    private static final double[][] NORTH_2KM = {{0, 0}, {0, 2000}};

    @Test
    public void noElevationNoProfile() {
        assertNull(ElevationProfile.of(Routes.polyline(NORTH_2KM, null)));
    }

    @Test
    public void rampGrade() {
        ElevationProfile p = ElevationProfile.of(Routes.polyline(NORTH_2KM, d -> d * 0.05));
        assertNotNull(p);
        assertEquals(0.05, p.gradeAhead(500, 200), 0.002);
        assertEquals(0.05, p.gradeAhead(0, 200), 0.005);
        assertEquals(100, p.totalAscentM(), 3);
    }

    @Test
    public void flatIsFlat() {
        ElevationProfile p = ElevationProfile.of(Routes.polyline(NORTH_2KM, d -> 100));
        assertEquals(0, p.gradeAhead(500, 200), 1e-6);
        assertEquals(0, p.totalAscentM());
    }

    @Test
    public void noiseIsSmoothed() {
        // +-3 m zig-zag on a flat road must not look like a climb
        int[] i = {0};
        ElevationProfile p = ElevationProfile.of(Routes.polyline(NORTH_2KM, d -> 100 + ((i[0]++ % 2) * 6 - 3)));
        for (double s = 100; s < 1800; s += 50) {
            assertEquals(0, p.gradeAhead(s, 200), 0.03);
        }
    }

    @Test
    public void sliceOfARamp() {
        GpxRoute r = Routes.polyline(NORTH_2KM, d -> 100 + d * 0.05);
        ElevationProfile p = ElevationProfile.of(r);
        ProfileFrame f = p.slice(500, 40, 8);
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
        ProfileFrame f = p.slice(1900, 200, 8);
        assertNull(f);   // only ~4 samples left, below the minimum
        ProfileFrame g = p.slice(1500, 200, 8);
        assertEquals(20, g.deltasDm.length);
    }
}
