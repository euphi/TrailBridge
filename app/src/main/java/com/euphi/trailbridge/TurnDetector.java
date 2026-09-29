package com.euphi.trailbridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives turn manoeuvres from bare track geometry, for GPX files that carry
 * no routing information of their own. Deliberately simple: no street names,
 * no roundabouts, no keep-left/right forks -- the geometry doesn't say what
 * they are. It finds corners and bends, classifies them by how far the heading
 * changes, and leaves the rest of the track as "straight on".
 *
 * Works best on planned routes (BRouter, Komoot, ...). A raw recorded track
 * with GPS noise gets spurious turns; the resampling and the minimum angle
 * only filter part of that.
 */
public final class TurnDetector {

    private static final double SPACING_M = 10;
    /** Heading is measured over this many samples before/after a point (30 m). */
    private static final int WINDOW = 3;
    /** Heading changes below this are not a turn. */
    private static final double MIN_ANGLE = 30;
    private static final double TURN_ANGLE = 60;
    private static final double SHARP_ANGLE = 120;
    private static final double UTURN_ANGLE = 160;
    /** Two candidates closer than this are one manoeuvre (the sharper wins). */
    private static final double MIN_GAP_M = 25;
    /** Runs of same-direction candidates with a gap up to this many samples are one bend. */
    private static final int MAX_RUN_GAP = 3;

    private TurnDetector() {
    }

    public static List<GpxRoute.Step> detect(GpxRoute route) {
        List<GpxRoute.Step> steps = new ArrayList<>();
        if (route.pointCount() < 2 || route.totalM < 2 * WINDOW * SPACING_M) {
            return steps;
        }

        // 1. Resample at even spacing in a local planar frame.
        int n = (int) (route.totalM / SPACING_M) + 1;
        double[] x = new double[n];
        double[] y = new double[n];
        double lat0 = route.lat[0];
        double lon0 = route.lon[0];
        int seg = 0;
        for (int i = 0; i < n; i++) {
            double d = i * SPACING_M;
            while (seg < route.pointCount() - 2 && route.cum[seg + 1] < d) seg++;
            double span = route.cum[seg + 1] - route.cum[seg];
            double t = span <= 0 ? 0 : Math.max(0, Math.min(1, (d - route.cum[seg]) / span));
            double la = route.lat[seg] + t * (route.lat[seg + 1] - route.lat[seg]);
            double lo = route.lon[seg] + t * (route.lon[seg + 1] - route.lon[seg]);
            x[i] = Geo.eastM(lat0, lon0, la, lo);
            y[i] = Geo.northM(lat0, la);
        }

        // 2. Heading change at every sample (positive = right).
        double[] turn = new double[n];
        for (int i = WINDOW; i < n - WINDOW; i++) {
            double before = Geo.bearingDeg(x[i] - x[i - WINDOW], y[i] - y[i - WINDOW]);
            double after = Geo.bearingDeg(x[i + WINDOW] - x[i], y[i + WINDOW] - y[i]);
            turn[i] = Geo.angleDiffDeg(before, after);
        }

        // 3. Group candidates (|turn| >= MIN_ANGLE, same sign) into bends; the
        // bend's total angle is the heading change across its whole extent.
        List<double[]> bends = new ArrayList<>();   // {distM, signedTotalAngle}
        int i = WINDOW;
        while (i < n - WINDOW) {
            if (Math.abs(turn[i]) < MIN_ANGLE) {
                i++;
                continue;
            }
            int sign = turn[i] > 0 ? 1 : -1;
            int start = i;
            int end = i;
            int j = i + 1;
            while (j < n - WINDOW && j - end <= MAX_RUN_GAP) {
                if (Math.abs(turn[j]) >= MIN_ANGLE && (turn[j] > 0) == (sign > 0)) end = j;
                j++;
            }
            int peak = start;
            for (int k = start; k <= end; k++) {
                if (Math.abs(turn[k]) > Math.abs(turn[peak])) peak = k;
            }
            double before = Geo.bearingDeg(x[start] - x[start - WINDOW], y[start] - y[start - WINDOW]);
            double after = Geo.bearingDeg(x[end + WINDOW] - x[end], y[end + WINDOW] - y[end]);
            double total = Geo.angleDiffDeg(before, after);
            // A bend's position: the sharpest point for a corner, the middle for a long curve.
            double at = (end - start) * SPACING_M > 60 ? (start + end) / 2.0 * SPACING_M : peak * SPACING_M;
            if (Math.abs(total) >= MIN_ANGLE) {
                bends.add(new double[]{at, total});
            }
            i = end + 1;
        }

        // 4. Classify; merge candidates that are too close.
        double lastAt = -1e9;
        double lastAbs = 0;
        for (double[] b : bends) {
            double at = b[0];
            double abs = Math.abs(b[1]);
            if (at < WINDOW * SPACING_M || at > route.totalM - WINDOW * SPACING_M) continue;
            int m = classify(b[1]);
            GpxRoute.Step step = new GpxRoute.Step(at, m, 0, "");
            if (!steps.isEmpty() && at - lastAt < MIN_GAP_M) {
                if (abs > lastAbs) {
                    steps.set(steps.size() - 1, step);
                    lastAt = at;
                    lastAbs = abs;
                }
                continue;
            }
            steps.add(step);
            lastAt = at;
            lastAbs = abs;
        }
        return steps;
    }

    static int classify(double signedAngle) {
        boolean right = signedAngle > 0;
        double a = Math.abs(signedAngle);
        if (a >= UTURN_ANGLE) return right ? Maneuver.UTURN_RIGHT : Maneuver.UTURN_LEFT;
        if (a >= SHARP_ANGLE) return right ? Maneuver.TURN_SHARP_RIGHT : Maneuver.TURN_SHARP_LEFT;
        if (a >= TURN_ANGLE) return right ? Maneuver.TURN_RIGHT : Maneuver.TURN_LEFT;
        return right ? Maneuver.TURN_SLIGHT_RIGHT : Maneuver.TURN_SLIGHT_LEFT;
    }
}
