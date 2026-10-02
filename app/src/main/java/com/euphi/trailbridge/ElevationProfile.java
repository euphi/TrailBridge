package com.euphi.trailbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The route's elevation resampled onto a fixed raster (STEP_M) and smoothed --
 * GPX elevations are noisy (GPS/barometer/DEM steps), and neither grade
 * detection nor the profile on the small display wants that noise.
 *
 * Also finds the route's climbs, foot to summit (see {@link #climbs()}). The
 * criteria are the ones of the firmware's Climb::Tracker (ClimbProfile.cpp in
 * the TRGB-BikeComputer repo) -- keep the two in sync, so that the BikeComputer
 * sees the same climb in the profile it is sent.
 */
public final class ElevationProfile {

    public static final int STEP_M = 25;
    /** Moving-average window in raster samples (odd): 5 * 25 m = 125 m. */
    private static final int SMOOTH_WINDOW = 5;

    /** Foot: the first point from which the next START_WINDOW_M rise by this much (3 m in 100 m). */
    static final double START_RISE_M = 3;
    static final int START_WINDOW_M = 100;
    /**
     * The climb goes on to a later point if the route rises by this much on
     * average from the highest point so far (0.5 %) ...
     */
    static final double CONT_GRADE = 0.005;
    /** ... within this distance: a flat stretch longer than this ends the climb ... */
    static final double SUMMIT_FLAT_M = 2000;
    /** ... and so does a descent of more than this. A shorter dip or flat is part of the climb. */
    static final double SUMMIT_DIP_M = 30;
    /** Less height than this between foot and summit is no climb. */
    static final double MIN_GAIN_M = 10;

    /** Coarsest raster of a profile frame, in STEP_M (STEP_M of the protocol is one byte: 250 m). */
    private static final int MAX_STEP_FACTOR = 10;

    /** One climb, foot to summit, in metres along the route. */
    public static final class Climb {
        public final double footM;
        public final double summitM;
        public final float footAltM;
        public final float summitAltM;

        Climb(double footM, double summitM, float footAltM, float summitAltM) {
            this.footM = footM;
            this.summitM = summitM;
            this.footAltM = footAltM;
            this.summitAltM = summitAltM;
        }

        public double lengthM() {
            return summitM - footM;
        }

        public double gainM() {
            return summitAltM - footAltM;
        }
    }

    private final float[] alt;   // metres, one sample per STEP_M along the route
    private final double totalM;
    private final List<Climb> climbs;

    private ElevationProfile(float[] alt, double totalM) {
        this.alt = alt;
        this.totalM = totalM;
        this.climbs = Collections.unmodifiableList(findClimbs(alt));
    }

    /** @return null if the route has no (usable) elevation data. */
    public static ElevationProfile of(GpxRoute r) {
        if (!r.hasElevation() || r.totalM < STEP_M) {
            return null;
        }
        // Only points that actually have an elevation take part.
        int n = 0;
        double[] d = new double[r.pointCount()];
        float[] e = new float[r.pointCount()];
        for (int i = 0; i < r.pointCount(); i++) {
            if (!Float.isNaN(r.ele[i])) {
                d[n] = r.cum[i];
                e[n] = r.ele[i];
                n++;
            }
        }
        int samples = (int) (r.totalM / STEP_M) + 1;
        float[] raw = new float[samples];
        int j = 0;
        for (int i = 0; i < samples; i++) {
            double at = i * (double) STEP_M;
            while (j < n - 2 && d[j + 1] < at) j++;
            double span = d[j + 1] - d[j];
            double t = span <= 0 ? 0 : (at - d[j]) / span;
            t = Math.max(0, Math.min(1, t));
            raw[i] = (float) (e[j] + t * (e[j + 1] - e[j]));
        }
        float[] smooth = new float[samples];
        int half = SMOOTH_WINDOW / 2;
        for (int i = 0; i < samples; i++) {
            // Shrink the window symmetrically at the ends, otherwise the first/last
            // samples get pulled towards their neighbours and bias the grade there.
            int h = Math.min(half, Math.min(i, samples - 1 - i));
            int lo = i - h;
            int hi = i + h;
            float sum = 0;
            for (int k = lo; k <= hi; k++) sum += raw[k];
            smooth[i] = sum / (hi - lo + 1);
        }
        return new ElevationProfile(smooth, r.totalM);
    }

    /** Number of raster samples. */
    public int size() {
        return alt.length;
    }

    /** Smoothed altitude at a route distance, linearly interpolated. */
    public double altitudeAt(double distM) {
        double x = Math.max(0, Math.min(distM, (alt.length - 1) * (double) STEP_M)) / STEP_M;
        int i = (int) Math.floor(x);
        if (i >= alt.length - 1) return alt[alt.length - 1];
        double t = x - i;
        return alt[i] + t * (alt[i + 1] - alt[i]);
    }

    public int totalAscentM() {
        double up = 0;
        for (int i = 1; i < alt.length; i++) {
            if (alt[i] > alt[i - 1]) up += alt[i] - alt[i - 1];
        }
        return (int) Math.round(up);
    }

    /** The route's climbs in riding order; they don't overlap. */
    public List<Climb> climbs() {
        return climbs;
    }

    /**
     * A climb starts where the route begins to rise by START_RISE_M within
     * START_WINDOW_M, and its summit is the highest point before the route
     * either drops by more than SUMMIT_DIP_M or stays flat (below CONT_GRADE
     * on average) for more than SUMMIT_FLAT_M -- or ends.
     */
    private static List<Climb> findClimbs(float[] alt) {
        List<Climb> out = new ArrayList<>();
        int last = alt.length - 1;
        int window = START_WINDOW_M / STEP_M;
        int i = 0;
        while (i + 2 <= last) {
            int j = Math.min(i + window, last);
            if (alt[j] - alt[i] < START_RISE_M * (j - i) / window) {
                i++;
                continue;
            }
            // The window may start on the flat before the rise
            int foot = i;
            while (foot + 1 < j && alt[foot + 1] - alt[foot] < CONT_GRADE * STEP_M) foot++;
            int summit = foot;
            for (int k = foot + 1; k <= last; k++) {
                double distM = (k - summit) * (double) STEP_M;
                double riseM = alt[k] - alt[summit];
                if (riseM > 0 && riseM >= CONT_GRADE * distM) {
                    summit = k;
                } else if (-riseM > SUMMIT_DIP_M || distM > SUMMIT_FLAT_M) {
                    break;
                }
            }
            if (alt[summit] - alt[foot] >= MIN_GAIN_M) {
                out.add(new Climb(foot * (double) STEP_M, summit * (double) STEP_M, alt[foot], alt[summit]));
            }
            i = Math.max(summit, i) + 1;
        }
        return out;
    }

    /**
     * Cuts the profile frame for the stretch from the rider (fromM) to a summit
     * (toM). The raster is as fine as maxSteps deltas allow for the whole
     * stretch -- STEP_M up to 5 km at 200 deltas, 250 m at most -- and laid out
     * from the summit backwards, so the last point is the summit and the first
     * one lies at or (by less than a step) behind the rider. Only if the stretch
     * does not fit even on the coarsest raster the frame is cut off after
     * maxSteps; the frames that follow keep its raster (stepM), so the firmware
     * can join them.
     *
     * The anchor is the route's remaining distance at the first sample -- the
     * firmware combines it with the REMAINING_DISTANCE_M of the nav frames to
     * know where the rider is, no odometer needed.
     *
     * @param stepM raster to keep for a further frame of the same climb, 0 to choose it
     * @return null if fewer than minSteps samples remain ahead.
     */
    public ProfileFrame slice(double fromM, double toM, int maxSteps, int minSteps, int stepM) {
        int last = alt.length - 1;
        int iFrom = Math.max(0, Math.min((int) Math.round(fromM / STEP_M), last));
        int iTo = Math.max(0, Math.min((int) Math.round(toM / STEP_M), last));
        if (maxSteps <= 0 || iTo <= iFrom) {
            return null;
        }
        int factor = stepM > 0 ? stepM / STEP_M
                : Math.min(MAX_STEP_FACTOR, (iTo - iFrom + maxSteps - 1) / maxSteps);
        int steps = (iTo - iFrom + factor - 1) / factor;
        int i0 = iTo - steps * factor;
        if (i0 < 0) {        // right at the start of the route: begin one step ahead of the rider
            i0 += factor;
            steps--;
        }
        steps = Math.min(steps, maxSteps);
        if (steps < minSteps || steps <= 0) {
            return null;
        }
        int baseDm = Math.max(-32768, Math.min(32767, Math.round(alt[i0] * 10f)));
        // Deltas are a byte each: on a coarse raster a steep step does not fit in
        // decimetres, so the unit grows until every one does.
        for (int scale = 1; ; scale++) {
            byte[] deltas = deltas(i0, factor, steps, baseDm, scale);
            if (deltas != null) {
                int remaining = (int) Math.round(totalM - i0 * (double) STEP_M);
                return new ProfileFrame(Math.max(0, remaining), STEP_M * factor, baseDm, deltas, scale,
                        i0 * (double) STEP_M);
            }
        }
    }

    /** @return null if a delta does not fit into a byte at this scale. */
    private byte[] deltas(int i0, int factor, int steps, int baseDm, int scale) {
        // Absolute rounding of each sample (not of the differences) so the
        // deltas add up to the true altitude without accumulating error.
        int prev = 0;
        byte[] deltas = new byte[steps];
        for (int k = 0; k < steps; k++) {
            int cur = Math.round((alt[i0 + (k + 1) * factor] * 10f - baseDm) / scale);
            int dlt = cur - prev;
            if (dlt < -127 || dlt > 127) {
                return null;
            }
            deltas[k] = (byte) dlt;
            prev = cur;
        }
        return deltas;
    }
}
