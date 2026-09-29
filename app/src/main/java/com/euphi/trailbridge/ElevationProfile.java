package com.euphi.trailbridge;

/**
 * The route's elevation resampled onto a fixed raster (STEP_M) and smoothed --
 * GPX elevations are noisy (GPS/barometer/DEM steps), and neither grade
 * detection nor the profile on the small display wants that noise.
 */
public final class ElevationProfile {

    public static final int STEP_M = 25;
    /** Moving-average window in raster samples (odd): 5 * 25 m = 125 m. */
    private static final int SMOOTH_WINDOW = 5;

    private final float[] alt;   // metres, one sample per STEP_M along the route
    private final double totalM;

    private ElevationProfile(float[] alt, double totalM) {
        this.alt = alt;
        this.totalM = totalM;
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

    /**
     * Average grade (rise/run, 0.05 = 5 %) over the next lookM metres. The
     * window is cut at the end of the route; below 50 m of run it counts as 0.
     */
    public double gradeAhead(double fromM, double lookM) {
        double end = Math.min(fromM + lookM, (alt.length - 1) * (double) STEP_M);
        double run = end - fromM;
        if (run < 50) return 0;
        return (altitudeAt(end) - altitudeAt(fromM)) / run;
    }

    public int totalAscentM() {
        double up = 0;
        for (int i = 1; i < alt.length; i++) {
            if (alt[i] > alt[i - 1]) up += alt[i] - alt[i - 1];
        }
        return (int) Math.round(up);
    }

    /**
     * Cuts a profile frame starting at the raster sample nearest to fromM,
     * with at most maxSteps deltas. The anchor is the route's remaining
     * distance at that first sample -- the firmware combines it with the
     * REMAINING_DISTANCE_M of the nav frames to know where the rider is, no
     * odometer needed.
     *
     * @return null if fewer than minSteps samples remain ahead.
     */
    public ProfileFrame slice(double fromM, int maxSteps, int minSteps) {
        int i0 = (int) Math.round(fromM / STEP_M);
        i0 = Math.max(0, Math.min(i0, alt.length - 1));
        int steps = Math.min(maxSteps, alt.length - 1 - i0);
        if (steps < minSteps || steps <= 0) {
            return null;
        }
        // Absolute rounding of each sample (not of the differences) so the
        // deltas add up to the true altitude without accumulating error.
        int prev = Math.round(alt[i0] * 10f);
        byte[] deltas = new byte[steps];
        for (int k = 0; k < steps; k++) {
            int cur = Math.round(alt[i0 + k + 1] * 10f);
            int dlt = Math.max(-127, Math.min(127, cur - prev));
            deltas[k] = (byte) dlt;
            prev += dlt;
        }
        int remaining = (int) Math.round(totalM - i0 * (double) STEP_M);
        int baseDm = Math.max(-32768, Math.min(32767, Math.round(alt[i0] * 10f)));
        return new ProfileFrame(Math.max(0, remaining), STEP_M, baseDm, deltas, i0 * (double) STEP_M);
    }
}
