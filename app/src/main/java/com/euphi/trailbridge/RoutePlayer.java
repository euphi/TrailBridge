package com.euphi.trailbridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Plays a {@link GpxRoute} back as if it were being ridden: for every moment of
 * the simulated ride it says where the rider is and what the sensors report
 * (speed, heart rate, cadence). Test aid -- the playback feeds these values to
 * the BikeComputer in place of the real GPS fix and real sensors.
 *
 * Timeline (distance along the route for every whole second of the ride):
 *  - the file's own time stamps if it has them (a recorded track), long
 *    stops cut down to {@link #MAX_PAUSE_S} so a lunch break doesn't stall the test,
 *  - otherwise a made-up but plausible ride: cruising speed, slower uphill,
 *    faster downhill, slowing for sharp corners, with realistic acceleration
 *    and braking and a stop at the start and the end.
 *
 * Sensors: heart rate, cadence and power come from the file where it has
 * them (TrackPointExtension, power extensions); where it doesn't they can be
 * emulated -- heart rate follows the effort (speed, climbing) with a lag and
 * some random wobble, cadence drops on steep climbs, is 0 while coasting
 * downhill or standing and wobbles a little, power is what riding that speed
 * on that grade takes (rolling, air, climbing, accelerating) and 0 while
 * coasting. Without either, that sensor is simply "not there". The height
 * (the smoothed route elevation, a barometer stand-in) is always there if the
 * route has elevation.
 *
 * No Android dependencies; not thread-safe (TrailBridgeService uses it from
 * the main thread only).
 */
public final class RoutePlayer {

    /** Where a sensor value comes from. */
    public enum Source {
        /** Recorded in the GPX file. */
        GPX,
        /** Made up by the player. */
        EMULATED,
        /** Not available (no such sensor in the file, emulation off). */
        NONE
    }

    /** The state of the simulated ride at one moment. */
    public static final class Sample {
        public final double distM;
        public final double lat;
        public final double lon;
        /** Smoothed route elevation, NaN without elevation data. */
        public final double eleM;
        public final double speedMs;
        public final double bearingDeg;
        /** bpm, -1 = no heart rate sensor. */
        public final int hr;
        /** rpm (0 = coasting), -1 = no cadence sensor. */
        public final int cad;
        /** watts (0 = coasting), -1 = no power meter. */
        public final int power;

        Sample(double distM, double lat, double lon, double eleM, double speedMs,
               double bearingDeg, int hr, int cad, int power) {
            this.distM = distM;
            this.lat = lat;
            this.lon = lon;
            this.eleM = eleM;
            this.speedMs = speedMs;
            this.bearingDeg = bearingDeg;
            this.hr = hr;
            this.cad = cad;
            this.power = power;
        }
    }

    /** A standstill longer than this (seconds) is cut down to it. */
    static final int MAX_PAUSE_S = 30;
    /** Cruising speed on the flat when the file has no time stamps. */
    static final double CRUISE_KMH = 20;

    private static final double RASTER_M = 5;
    private static final double ACCEL_MS2 = 0.8;
    private static final double BRAKE_MS2 = 1.2;
    private static final double MAX_SPEED_MS = 45 / 3.6;
    private static final double CORNER_SPEED_MS = 2.8;
    private static final double STANDING_MS = 0.5;

    // Power model: rider + bike, an upright position on tarmac.
    private static final double MASS_KG = 85;
    private static final double CRR = 0.008;
    private static final double CDA = 0.45;
    private static final double AIR_DENSITY = 1.2;
    private static final double DRIVETRAIN_EFF = 0.97;
    private static final double MIN_PEDALLING_W = 30;

    private final GpxRoute route;
    private final ElevationProfile elevation;   // null without elevation data
    private final boolean speedFromFile;
    private final double[] dAt;                 // route distance at second k of the ride
    private final double[] vAt;                 // speed (m/s) at second k, smoothed
    private final double[] fileHr;              // per route point, gaps filled; null = not in file
    private final double[] fileCad;
    private final double[] filePower;
    private final Random rnd;

    private boolean emulateHr = true;
    private boolean emulateCad = true;
    private boolean emulatePower = true;

    private double timeS = 0;
    // emulation state
    private double hrState = Double.NaN;
    private double cadNoise = 0;
    private double powerState = Double.NaN;

    public RoutePlayer(GpxRoute route) {
        this(route, System.nanoTime());
    }

    /** @param seed for the random wobble of emulated heart rate / cadence (tests pin it). */
    public RoutePlayer(GpxRoute route, long seed) {
        this.route = route;
        this.rnd = new Random(seed);
        this.elevation = ElevationProfile.of(route);
        this.fileHr = route.hasHeartRate() ? fill(route, route.hr) : null;
        this.fileCad = route.hasCadence() ? fill(route, route.cad) : null;
        this.filePower = route.hasPower() ? fill(route, route.power) : null;

        double[] d = route.totalM < 1 ? null : fromTimestamps(route);
        this.speedFromFile = d != null;
        if (d == null) d = route.totalM < 1 ? new double[]{0, 0} : synthesize();
        d = compressPauses(d, route.totalM);
        this.dAt = d;
        this.vAt = new double[d.length];
        for (int k = 0; k < d.length; k++) {
            // +-2 s; the window shrinks towards the ends, so a ride that starts and
            // ends standing reads 0 there.
            int h = Math.min(2, Math.min(k, d.length - 1 - k));
            int lo = k - h;
            int hi = k + h;
            vAt[k] = hi == lo ? 0 : (d[hi] - d[lo]) / (hi - lo);
        }
    }

    // ---- what the file provides, and what is emulated ----

    public Source speedSource() {
        return speedFromFile ? Source.GPX : Source.EMULATED;
    }

    public Source heartRateSource() {
        return fileHr != null ? Source.GPX : emulateHr ? Source.EMULATED : Source.NONE;
    }

    public Source cadenceSource() {
        return fileCad != null ? Source.GPX : emulateCad ? Source.EMULATED : Source.NONE;
    }

    public Source powerSource() {
        return filePower != null ? Source.GPX : emulatePower ? Source.EMULATED : Source.NONE;
    }

    /** True if the route has elevation, i.e. every sample carries a height. */
    public boolean hasHeight() {
        return elevation != null;
    }

    /** Emulate heart rate / cadence / power where the file has none (ignored where it has). */
    public void setEmulation(boolean heartRate, boolean cadence, boolean power) {
        this.emulateHr = heartRate;
        this.emulateCad = cadence;
        this.emulatePower = power;
    }

    // ---- clock ----

    public GpxRoute route() {
        return route;
    }

    /** Length of the whole ride in seconds. */
    public double durationS() {
        return dAt.length - 1;
    }

    public double timeS() {
        return timeS;
    }

    public double distanceM() {
        return distanceAt(timeS);
    }

    public boolean finished() {
        return timeS >= durationS();
    }

    /** Jumps to a distance along the route (clamped); emulated sensors restart from the new situation. */
    public void seek(double distM) {
        double d = Math.max(0, Math.min(distM, route.totalM));
        timeS = timeForDistance(d);
        hrState = Double.NaN;
        cadNoise = 0;
        powerState = Double.NaN;
    }

    /**
     * Moves the ride on and returns the state afterwards.
     *
     * @param dtS     seconds since the last call (0 = just look, e.g. after a seek)
     * @param playing false = the rider stands still (paused): the clock stops,
     *                speed and cadence read 0, the heart rate settles down
     */
    public Sample step(double dtS, boolean playing) {
        if (playing && dtS > 0) {
            timeS = Math.min(durationS(), timeS + dtS);
        }
        double d = distanceAt(timeS);
        boolean moving = playing && !finished();
        double v = moving ? speedAt(timeS) : 0;
        double grade = gradeAt(d);
        int hr = heartRate(d, v, grade, dtS);
        int cad = cadence(d, v, grade, moving);
        double accel = moving ? speedAt(timeS + 0.5) - speedAt(Math.max(0, timeS - 0.5)) : 0;
        int power = power(d, v, grade, accel, cad, moving, dtS);
        double[] pos = positionAt(d);
        return new Sample(d, pos[0], pos[1],
                elevation == null ? Double.NaN : elevation.altitudeAt(d),
                v, bearingAt(d), hr, cad, power);
    }

    // ---- sensors ----

    private int heartRate(double d, double v, double grade, double dtS) {
        if (fileHr != null) {
            return (int) Math.round(valueAt(fileHr, d));
        }
        if (!emulateHr) return -1;
        double effort = Math.max(0, Math.min(1, 0.012 * v * 3.6 + 5 * Math.max(0, grade)));
        double target = v < STANDING_MS ? 88 : 95 + 85 * effort;
        if (Double.isNaN(hrState)) {
            hrState = target;
        } else if (dtS > 0) {
            // First-order lag (about 20 s) towards the effort, plus a random walk.
            hrState += (target - hrState) * (1 - Math.exp(-dtS / 20)) + rnd.nextGaussian() * 0.7;
        }
        return (int) Math.round(Math.max(55, Math.min(200, hrState)));
    }

    private int cadence(double d, double v, double grade, boolean moving) {
        if (fileCad != null) {
            return moving ? (int) Math.round(valueAt(fileCad, d)) : 0;
        }
        if (!emulateCad) return -1;
        if (!moving || v < STANDING_MS || grade < -0.04) {
            return 0;       // standing or coasting down a steep descent
        }
        double target = grade < -0.02 ? 70 : Math.max(50, Math.min(95, 88 - 160 * Math.max(0, grade)));
        cadNoise = 0.8 * cadNoise + (rnd.nextDouble() - 0.5) * 5;
        return (int) Math.round(Math.max(1, target + cadNoise));
    }

    private int power(double d, double v, double grade, double accel, int cad, boolean moving, double dtS) {
        if (filePower != null) {
            return moving ? (int) Math.round(valueAt(filePower, d)) : 0;
        }
        if (!emulatePower) return -1;
        // Coasting: standing, no pedalling (known from the cadence if there is one), or a steep descent.
        boolean coasting = !moving || v < STANDING_MS || (cad >= 0 ? cad == 0 : grade < -0.04);
        if (coasting) {
            powerState = 0;
            return 0;
        }
        double force = MASS_KG * 9.81 * (CRR + grade)
                + 0.5 * AIR_DENSITY * CDA * v * v
                + MASS_KG * Math.max(0, Math.min(3, accel));
        double target = Math.max(MIN_PEDALLING_W, force * v / DRIVETRAIN_EFF) * (1 + 0.06 * rnd.nextGaussian());
        if (Double.isNaN(powerState) || powerState == 0) {
            powerState = target;
        } else if (dtS > 0) {
            // Power meters are jumpy; riders' legs are smoother: about 3 s of lag.
            powerState += (target - powerState) * (1 - Math.exp(-dtS / 3));
        }
        return (int) Math.round(Math.max(0, Math.min(1500, powerState)));
    }

    /** Per route point: the file's values with the gaps filled in linearly (ends: nearest value). */
    private static double[] fill(GpxRoute r, short[] values) {
        int n = values.length;
        double[] out = new double[n];
        int prev = -1;
        for (int i = 0; i < n; i++) {
            if (values[i] == GpxRoute.NO_VALUE) continue;
            if (prev < 0) {
                for (int j = 0; j < i; j++) out[j] = values[i];
            } else {
                for (int j = prev + 1; j < i; j++) {
                    double span = r.cum[i] - r.cum[prev];
                    double t = span <= 0 ? 0 : (r.cum[j] - r.cum[prev]) / span;
                    out[j] = values[prev] + t * (values[i] - values[prev]);
                }
            }
            out[i] = values[i];
            prev = i;
        }
        for (int j = prev + 1; j < n; j++) out[j] = values[prev];
        return out;
    }

    private double valueAt(double[] perPoint, double d) {
        int i = segmentAt(d);
        double span = route.cum[i + 1] - route.cum[i];
        double t = span <= 0 ? 0 : (d - route.cum[i]) / span;
        return perPoint[i] + Math.max(0, Math.min(1, t)) * (perPoint[i + 1] - perPoint[i]);
    }

    // ---- timeline ----

    private double distanceAt(double t) {
        int k = (int) Math.floor(t);
        if (k >= dAt.length - 1) return dAt[dAt.length - 1];
        return dAt[k] + (t - k) * (dAt[k + 1] - dAt[k]);
    }

    private double speedAt(double t) {
        int k = (int) Math.floor(t);
        if (k >= vAt.length - 1) return 0;
        return vAt[k] + (t - k) * (vAt[k + 1] - vAt[k]);
    }

    private double timeForDistance(double d) {
        return timeForDistance(dAt, d);
    }

    /** @param dAt route distance at every whole second of the ride */
    static double timeForDistance(double[] dAt, double d) {
        // first second at which the rider has got at least this far
        int lo = 0;
        int hi = dAt.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (dAt[mid] >= d) hi = mid; else lo = mid + 1;
        }
        if (lo == 0) return 0;
        double span = dAt[lo] - dAt[lo - 1];
        return span <= 0 ? lo : lo - 1 + (d - dAt[lo - 1]) / span;
    }

    /**
     * Distance for each whole second from the file's time stamps, or null if it
     * has too few to say anything.
     */
    static double[] fromTimestamps(GpxRoute r) {
        if (!r.hasTimes()) return null;
        List<double[]> pts = new ArrayList<>();   // {seconds, distance}, strictly increasing in seconds
        long t0 = 0;
        for (int i = 0; i < r.pointCount(); i++) {
            if (r.timeMs[i] == GpxRoute.NO_TIME) continue;
            if (pts.isEmpty()) {
                t0 = r.timeMs[i];
            }
            double s = (r.timeMs[i] - t0) / 1000.0;
            if (!pts.isEmpty()) {
                double[] last = pts.get(pts.size() - 1);
                if (s < last[0]) continue;                      // clock went backwards: ignore the point
                if (s == last[0]) {                              // several points in one instant
                    last[1] = Math.max(last[1], r.cum[i]);
                    continue;
                }
            }
            pts.add(new double[]{s, r.cum[i]});
        }
        if (pts.size() < 2) return null;
        // Points at the start/end without a time: assume a walking-pace lead-in/out.
        double[] first = pts.get(0);
        if (first[1] > 0) {
            pts.add(0, new double[]{first[0] - first[1] / 5.0, 0});
        }
        double[] last = pts.get(pts.size() - 1);
        if (last[1] < r.totalM) {
            pts.add(new double[]{last[0] + (r.totalM - last[1]) / 5.0, r.totalM});
        }
        double[] t = new double[pts.size()];
        double[] d = new double[pts.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = pts.get(i)[0] - pts.get(0)[0];
            d[i] = pts.get(i)[1];
        }
        return resample(t, d);
    }

    /** The made-up ride: a speed limit along the route, then acceleration/braking limits. */
    private double[] synthesize() {
        int n = (int) Math.ceil(route.totalM / RASTER_M) + 1;
        double[] dist = new double[n];
        double[] v = new double[n];
        double cruise = CRUISE_KMH / 3.6;
        for (int j = 0; j < n; j++) {
            double d = Math.min(j * RASTER_M, route.totalM);
            dist[j] = d;
            double g = gradeAt(d);
            double f = g >= 0 ? Math.max(0.35, 1 - 8 * g) : Math.min(1.9, 1 + 6 * -g);
            double lim = Math.min(MAX_SPEED_MS, cruise * f);
            // Turn angle over +-20 m; not at the very ends, where one side has no route.
            double turn = d < 20 || d > route.totalM - 20 ? 0
                    : Math.abs(Geo.angleDiffDeg(bearingBetween(d - 20, d), bearingBetween(d, d + 20)));
            if (turn > 25) {
                double t = Math.min(1, (turn - 25) / 75);
                lim = Math.min(lim, lim + (CORNER_SPEED_MS - lim) * t);
            }
            v[j] = Math.max(1.0, lim);
        }
        v[0] = 0;
        v[n - 1] = 0;
        for (int j = 1; j < n; j++) {
            v[j] = Math.min(v[j], Math.sqrt(v[j - 1] * v[j - 1] + 2 * ACCEL_MS2 * (dist[j] - dist[j - 1])));
        }
        for (int j = n - 2; j >= 0; j--) {
            v[j] = Math.min(v[j], Math.sqrt(v[j + 1] * v[j + 1] + 2 * BRAKE_MS2 * (dist[j + 1] - dist[j])));
        }
        double[] t = new double[n];
        for (int j = 1; j < n; j++) {
            double ds = dist[j] - dist[j - 1];
            t[j] = t[j - 1] + (ds <= 0 ? 0 : 2 * ds / Math.max(0.1, v[j - 1] + v[j]));
        }
        return resample(t, dist);
    }

    /** (t, d) with t increasing -> distance at every whole second, ending exactly at the last d. */
    private static double[] resample(double[] t, double[] d) {
        int seconds = (int) Math.ceil(t[t.length - 1]);
        double[] out = new double[seconds + 1];
        int j = 0;
        for (int k = 0; k < seconds; k++) {
            while (j < t.length - 2 && t[j + 1] < k) j++;
            double span = t[j + 1] - t[j];
            double f = span <= 0 ? 0 : Math.max(0, Math.min(1, (k - t[j]) / span));
            out[k] = d[j] + f * (d[j + 1] - d[j]);
        }
        out[seconds] = d[d.length - 1];
        return out;
    }

    /** Cuts every standstill longer than MAX_PAUSE_S down to that; the ride still ends on the route's end. */
    static double[] compressPauses(double[] d, double totalM) {
        double[] out = new double[d.length];
        int n = 0;
        int still = 0;
        for (int k = 0; k < d.length; k++) {
            if (k > 0 && d[k] - d[k - 1] < STANDING_MS * 0.6) {
                still++;
                if (still > MAX_PAUSE_S) continue;
            } else {
                still = 0;
            }
            out[n++] = d[k];
        }
        if (out[n - 1] < totalM - 0.01) {
            if (n == out.length) out = Arrays.copyOf(out, n + 1);
            out[n++] = totalM;
        }
        return Arrays.copyOf(out, n);
    }

    // ---- geometry ----

    /** Average grade (rise/run) over +-25 m around d, 0 without elevation data. */
    private double gradeAt(double d) {
        if (elevation == null) return 0;
        double lo = Math.max(0, d - 25);
        double hi = Math.min(route.totalM, d + 25);
        if (hi - lo < 10) return 0;
        return (elevation.altitudeAt(hi) - elevation.altitudeAt(lo)) / (hi - lo);
    }

    /** Index i of the route segment [i, i+1] containing distance d. */
    private int segmentAt(double d) {
        int lo = 0;
        int hi = route.pointCount() - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (route.cum[mid] <= d) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    private double[] positionAt(double d) {
        d = Math.max(0, Math.min(d, route.totalM));
        int i = segmentAt(d);
        double span = route.cum[i + 1] - route.cum[i];
        double t = span <= 0 ? 0 : Math.max(0, Math.min(1, (d - route.cum[i]) / span));
        return new double[]{
                route.lat[i] + t * (route.lat[i + 1] - route.lat[i]),
                route.lon[i] + t * (route.lon[i + 1] - route.lon[i])};
    }

    /** Direction of travel at d, from the route 10 m before to 10 m after. */
    private double bearingAt(double d) {
        return bearingBetween(d - 10, d + 10);
    }

    private double bearingBetween(double fromM, double toM) {
        double[] a = positionAt(Math.max(0, fromM));
        double[] b = positionAt(Math.min(route.totalM, toM));
        double dx = Geo.eastM(a[0], a[1], b[0], b[1]);
        double dy = Geo.northM(a[0], b[0]);
        return dx == 0 && dy == 0 ? 0 : Geo.bearingDeg(dx, dy);
    }
}
