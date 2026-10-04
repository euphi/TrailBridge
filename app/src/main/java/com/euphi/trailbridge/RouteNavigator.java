package com.euphi.trailbridge;

import java.util.Collections;
import java.util.List;

/**
 * Follows an imported {@link GpxRoute} with GPS fixes: matches the position
 * onto the route, and produces the NavState for the BikeComputer plus the elevation
 * profile of the road ahead to send (always; a climb in it is announced), and
 * keeps the overview of what lies ahead.
 *
 * No Android dependencies; feed it fixes, act on the {@link Result}. Not
 * thread-safe (TrailBridgeService calls it from the main thread only).
 */
public final class RouteNavigator {

    /** Further from the route than this counts as "off route". */
    static final double OFF_ROUTE_M = 50;
    /** How far ahead of the last match the next fix is searched (normally). */
    private static final double SEARCH_AHEAD_M = 400;
    /** ...and how far behind (GPS jitter, standing still, a short back-track). */
    private static final double SEARCH_BEHIND_M = 50;

    /** A climb's profile goes out once the rider is this close to its foot. */
    static final double APPROACH_M = 500;
    /** The summit counts as reached at its raster sample (half a step before it). */
    static final double SUMMIT_REACHED_M = ElevationProfile.STEP_M / 2.0;
    /** A climb just finished only counts again this far below its summit (GPS jitter at the top). */
    static final double REENTER_M = 100;

    private static final double DEFAULT_SPEED_MS = 15 / 3.6;
    private static final double MIN_SPEED_MS = 1.5;

    /** What one fix led to. */
    public static final class Result {
        public final NavState nav;
        /** Non-null: send this profile now. */
        public final ProfileFrame profile;
        /** True: tell the BikeComputer there is no (more) profile. */
        public final boolean clearProfile;
        public final boolean offRoute;
        /** True: what lies ahead changed (or is new), publish {@link #overview()} again. */
        public final boolean overviewChanged;

        Result(NavState nav, ProfileFrame profile, boolean clearProfile, boolean offRoute,
               boolean overviewChanged) {
            this.nav = nav;
            this.profile = profile;
            this.clearProfile = clearProfile;
            this.offRoute = offRoute;
            this.overviewChanged = overviewChanged;
        }
    }

    private final GpxRoute route;
    private final ElevationProfile elevation;   // null without elevation data
    private final RouteTimes times;             // null if the file has no time stamps

    private double progressM = 0;
    private boolean matched = false;
    private double speedMs = DEFAULT_SPEED_MS;

    private final List<ElevationProfile.Climb> climbs;
    private ProfileFrame sentProfile = null;
    private int sentClimb = -1;     // index of the climb sentProfile belongs to
    private int passedClimb = -1;   // the climb whose summit the rider reached last

    private final RouteOverview overview;
    private boolean overviewDirty = true;   // nothing published yet

    public RouteNavigator(GpxRoute route) {
        this.route = route;
        this.elevation = ElevationProfile.of(route);
        this.climbs = elevation == null ? Collections.<ElevationProfile.Climb>emptyList() : elevation.climbs();
        this.times = RouteTimes.of(route);
        this.overview = new RouteOverview(route, climbs, times);
    }

    public GpxRoute route() {
        return route;
    }

    public double progressM() {
        return progressM;
    }

    /** What still lies ahead, as of the last fix. */
    public RouteOverview overview() {
        return overview;
    }

    /**
     * Puts the rider at a given distance along the route without matching a
     * position (the GPX playback's "fast forward"). A matched search window
     * would not find a far jump -- or, on a route that doubles back, find the
     * wrong stretch. The profile state restarts too; the caller tells the
     * BikeComputer to drop the old profile.
     */
    public void seekTo(double progressM) {
        this.progressM = Math.max(0, Math.min(progressM, route.totalM));
        matched = true;
        sentProfile = null;
        sentClimb = -1;
        passedClimb = -1;
        overviewDirty |= overview.update(this.progressM);
    }

    /**
     * @param speedMs        speed over ground, negative if unknown
     * @param maxPayload     largest indicate payload the connected BikeComputer
     *                       takes (ATT_MTU - 3), bounds the profile length
     */
    public Result onFix(double lat, double lon, double speedMs, int maxPayload) {
        if (speedMs >= 0.5) {
            this.speedMs = 0.8 * this.speedMs + 0.2 * speedMs;
        }

        double[] match = match(lat, lon);   // {progress, distance to route}
        boolean offRoute = match[1] > OFF_ROUTE_M;
        if (!offRoute) {
            progressM = match[0];
            matched = true;
            overviewDirty |= overview.update(progressM);
        }
        boolean overviewChanged = overviewDirty;
        overviewDirty = false;
        if (offRoute) {
            // Progress stays where it was; the profile of the last known
            // stretch stays meaningless while we're elsewhere -> clear it.
            boolean clear = sentProfile != null;
            sentProfile = null;
            sentClimb = -1;
            return new Result(offRouteState((int) Math.round(match[1])), null, clear, true, overviewChanged);
        }

        NavState nav = navState();
        ProfileFrame send = null;
        if (elevation != null) {
            // Rolling frames carry the flag and the climb tags too: they take their share of the payload
            int maxSteps = ProfileFrameEncoder.maxSteps(maxPayload - ProfileFrameEncoder.ROLLING_EXTRA);
            if (maxSteps > 0) send = nextProfile(maxSteps);
        }
        return new Result(nav, send, false, false, overviewChanged);
    }

    /**
     * The profile is sent all the time (flat, downhill and uphill alike -- it is always of
     * interest), as a window of the road ahead; only a climb it contains is announced as such,
     * with its whole extent. A new frame goes out
     * <ul>
     * <li>at the start, and after the profile was taken back (off the route),</li>
     * <li>when the climb to announce changes: its foot comes within APPROACH_M, or its summit is
     *     passed -- the window then runs from the rider to the summit,</li>
     * <li>when the rider has passed the middle of a frame that does not reach as far as it
     *     should (the window moves on; a climb longer than one frame even on the coarsest raster).</li>
     * </ul>
     * Nothing is sent in between, and nothing is taken back at a summit: the road beyond it is
     * on the profile, and the BikeComputer sees by the missing announcement that the climb is
     * over.
     *
     * @return null if there is nothing new (or fewer than MIN_STEPS samples left to the route's end)
     */
    private ProfileFrame nextProfile(int maxSteps) {
        int k = climbAhead();
        double toM = k >= 0 ? climbs.get(k).summitM
                : Math.min(route.totalM, progressM + maxSteps * (double) ElevationProfile.STEP_M);
        boolean fresh = sentProfile == null || k != sentClimb;
        if (!fresh) {
            double sentEndM = sentProfile.startAlongM + sentProfile.lengthM();
            boolean endShort = sentEndM < toM - SUMMIT_REACHED_M;
            boolean pastMiddle = progressM > sentProfile.startAlongM + sentProfile.lengthM() / 2.0;
            if (!(endShort && pastMiddle)) return null;
        }
        // Another stretch of the same climb keeps the raster, so the BikeComputer can join the two
        int stepM = (!fresh && k >= 0) ? sentProfile.stepM : 0;
        ProfileFrame f = elevation.slice(progressM, toM, maxSteps, ProfileFrameEncoder.MIN_STEPS, stepM);
        if (f == null) return null;
        sentClimb = k;
        ProfileFrame.ClimbInfo info = null;
        if (k >= 0) {
            ElevationProfile.Climb c = climbs.get(k);
            info = new ProfileFrame.ClimbInfo(
                    (int) Math.round(route.totalM - c.footM), (int) Math.round(route.totalM - c.summitM),
                    Math.round(c.footAltM * 10f), Math.round(c.summitAltM * 10f));
        }
        sentProfile = f.asRolling(info);
        return sentProfile;
    }

    /**
     * The climb the rider is on or approaching: the next summit ahead, if its
     * foot is at most APPROACH_M away.
     *
     * @return index into climbs, -1 if none
     */
    private int climbAhead() {
        if (sentClimb >= 0 && progressM >= climbs.get(sentClimb).summitM - SUMMIT_REACHED_M) {
            passedClimb = sentClimb;
        }
        double p = progressM;
        if (passedClimb >= 0) {
            double summitM = climbs.get(passedClimb).summitM;
            if (p < summitM - REENTER_M) {
                passedClimb = -1;
            } else {
                p = Math.max(p, summitM);
            }
        }
        for (int k = 0; k < climbs.size(); k++) {
            ElevationProfile.Climb c = climbs.get(k);
            if (p < c.summitM - SUMMIT_REACHED_M) {
                return p >= c.footM - APPROACH_M ? k : -1;
            }
        }
        return -1;
    }

    // ---- matching ----

    /** @return {progress along the route, distance from the route} of the best match. */
    private double[] match(double lat, double lon) {
        double lo = 0;
        double hi = route.totalM;
        if (matched) {
            lo = Math.max(0, progressM - SEARCH_BEHIND_M);
            hi = progressM + SEARCH_AHEAD_M;
        }
        double[] best = search(lat, lon, lo, hi);
        if (matched && best[1] > OFF_ROUTE_M) {
            // Nothing near the expected stretch: look at the whole route, so
            // rejoining elsewhere (a shortcut, a detour) is noticed.
            double[] whole = search(lat, lon, 0, route.totalM);
            if (whole[1] < best[1]) best = whole;
        }
        return best;
    }

    private double[] search(double lat, double lon, double fromM, double toM) {
        double bestDist = Double.MAX_VALUE;
        double bestAlong = progressM;
        for (int i = 0; i < route.pointCount() - 1; i++) {
            if (route.cum[i + 1] < fromM) continue;
            if (route.cum[i] > toM) break;
            double ax = Geo.eastM(lat, lon, route.lat[i], route.lon[i]);
            double ay = Geo.northM(lat, route.lat[i]);
            double bx = Geo.eastM(lat, lon, route.lat[i + 1], route.lon[i + 1]);
            double by = Geo.northM(lat, route.lat[i + 1]);
            double dx = bx - ax;
            double dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double px = ax + t * dx;
            double py = ay + t * dy;
            double d = Math.sqrt(px * px + py * py);
            if (d < bestDist) {
                bestDist = d;
                bestAlong = route.cum[i] + t * (route.cum[i + 1] - route.cum[i]);
            }
        }
        return new double[]{bestAlong, bestDist};
    }

    // ---- NavState ----

    private NavState navState() {
        List<GpxRoute.Step> steps = route.steps;
        // First step that is still ahead (a step we're within 3 m of counts as passed,
        // except DEPART, which shows until the rider has actually moved off).
        int k = 0;
        while (k < steps.size() - 1 && steps.get(k).distM < progressM - 3) k++;
        GpxRoute.Step next = steps.get(k);
        GpxRoute.Step after = k + 1 < steps.size() ? steps.get(k + 1) : null;

        int remaining = (int) Math.round(Math.max(0, route.totalM - progressM));
        int timeS = remainingTimeS(remaining);
        return new NavState(true,
                next.maneuver, (int) Math.round(Math.max(0, next.distM - progressM)),
                next.roundaboutExit, next.name, Collections.<Lane>emptyList(), 0,
                after == null ? Maneuver.NONE : after.maneuver,
                after == null ? 0 : (int) Math.round(after.distM - next.distM),
                after == null ? "" : after.name, Collections.<Lane>emptyList(), 0,
                remaining, timeS);
    }

    private NavState offRouteState(int distToRouteM) {
        int remaining = (int) Math.round(Math.max(0, route.totalM - progressM));
        return new NavState(true, Maneuver.UNKNOWN, distToRouteM, 0, "Abseits der Route",
                Collections.<Lane>emptyList(), 0, Maneuver.NONE, 0, "",
                Collections.<Lane>emptyList(), 0, remaining, remainingTimeS(remaining));
    }

    /**
     * Time to the destination: what the file's time stamps say for the rest of
     * the route, if it has them (they know the climbs ahead); else the rest at
     * the current speed.
     */
    private int remainingTimeS(int remainingM) {
        if (times != null) {
            return (int) Math.round(times.remainingS(progressM));
        }
        return (int) Math.round(remainingM / Math.max(speedMs, MIN_SPEED_MS));
    }
}
