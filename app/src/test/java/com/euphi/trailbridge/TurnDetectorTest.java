package com.euphi.trailbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class TurnDetectorTest {

    private static List<GpxRoute.Step> detect(double[]... corners) {
        return TurnDetector.detect(Routes.polyline(corners, null));
    }

    @Test
    public void straightHasNoTurns() {
        assertTrue(detect(new double[]{0, 0}, new double[]{0, 1000}).isEmpty());
    }

    @Test
    public void rightAngleRight() {
        // north 300 m, then east 300 m
        List<GpxRoute.Step> s = detect(new double[]{0, 0}, new double[]{0, 300}, new double[]{300, 300});
        assertEquals(1, s.size());
        assertEquals(Maneuver.TURN_RIGHT, s.get(0).maneuver);
        assertEquals(300, s.get(0).distM, 15);
    }

    @Test
    public void rightAngleLeft() {
        List<GpxRoute.Step> s = detect(new double[]{0, 0}, new double[]{0, 300}, new double[]{-300, 300});
        assertEquals(1, s.size());
        assertEquals(Maneuver.TURN_LEFT, s.get(0).maneuver);
    }

    @Test
    public void gentleBendIsSlight() {
        // ~45 degree bend to the right
        List<GpxRoute.Step> s = detect(new double[]{0, 0}, new double[]{0, 300}, new double[]{212, 512});
        assertEquals(1, s.size());
        assertEquals(Maneuver.TURN_SLIGHT_RIGHT, s.get(0).maneuver);
    }

    @Test
    public void sharpAndUTurn() {
        List<GpxRoute.Step> sharp = detect(new double[]{0, 0}, new double[]{0, 300}, new double[]{200, 100 + 0});
        assertEquals(1, sharp.size());
        assertEquals(Maneuver.TURN_SHARP_RIGHT, sharp.get(0).maneuver);

        List<GpxRoute.Step> u = detect(new double[]{0, 0}, new double[]{0, 300}, new double[]{15, 0});
        assertEquals(1, u.size());
        assertEquals(Maneuver.UTURN_RIGHT, u.get(0).maneuver);
    }

    @Test
    public void twoTurnsInARow() {
        List<GpxRoute.Step> s = detect(new double[]{0, 0}, new double[]{0, 200}, new double[]{200, 200},
                new double[]{200, 400});
        assertEquals(2, s.size());
        assertEquals(Maneuver.TURN_RIGHT, s.get(0).maneuver);
        assertEquals(Maneuver.TURN_LEFT, s.get(1).maneuver);
        assertEquals(200, s.get(0).distM, 15);
        assertEquals(400, s.get(1).distM, 15);
    }
}
