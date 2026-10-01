package com.euphi.trailbridge;

import java.util.Collections;
import java.util.List;

/**
 * Follows an imported {@link GpxRoute} with GPS fixes: matches the position
 * onto the route, and produces the NavState for the BikeComputer plus -- while
 * a climb is ahead -- the elevation profile to send.
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

    /** Grade ahead that starts / ends a "climb" (with hysteresis). */
    static final double CLIMB_ON = 0.04;
    static final double CLIMB_OFF = 0.02;
    private static final double LOOK_AHEAD_M = 200;

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

        Result(NavState nav, ProfileFrame profile, boolean clearProfile, boolean offRoute) {
            this.nav = nav;
            this.profile = profile;
            this.clearProfile = clearProfile;
            this.offRoute = offRoute;
        }
    }

    private final GpxRoute route;
    private final ElevationProfile elevation;   // null without elevation data

    private double progressM = 0;
    private boolean matched = false;
    private double speedMs = DEFAULT_SPEED_MS;

    private boolean climbing = false;
    private ProfileFrame sentProfile = null;

    public RouteNavigator(GpxRoute route) {
        this.route = route;
        this.elevation = ElevationProfile.of(route);
    }

    public GpxRoute route() {
        return route;
    }

    public double progressM() {
        return progressM;
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
        climbing = false;
        sentProfile = null;
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
        }
        if (offRoute) {
            // Progress stays where it was; the profile of the last known
            // stretch stays meaningless while we're elsewhere -> clear it.
            boolean clear = sentProfile != null;
            climbing = false;
            sentProfile = null;
            return new Result(offRouteState((int) Math.round(match[1])), null, clear, true);
        }

        NavState nav = navState();
        ProfileFrame send = null;
        boolean clear = false;
        if (elevation != null) {
            double grade = elevation.gradeAhead(progressM, LOOK_AHEAD_M);
            int maxSteps = ProfileFrameEncoder.maxSteps(maxPayload);
            if (!climbing && grade >= CLIMB_ON && maxSteps > 0) {
                climbing = true;
                send = cut(maxSteps);
            } else if (climbing && grade < CLIMB_OFF) {
                climbing = false;
                clear = sentProfile != null;
                sentProfile = null;
            } else if (climbing && sentProfile != null && maxSteps > 0
                    && progressM > sentProfile.startAlongM + sentProfile.lengthM() / 2.0
                    && sentProfile.startAlongM + sentProfile.lengthM() < route.totalM - ElevationProfile.STEP_M) {
                // Rider is past the middle of what the display has: send the next stretch.
                send = cut(maxSteps);
            }
        }
        return new Result(nav, send, clear, false);
    }

    private ProfileFrame cut(int maxSteps) {
        ProfileFrame f = elevation.slice(progressM, maxSteps, ProfileFrameEncoder.MIN_STEPS);
        if (f != null) sentProfile = f;
        return f;
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
        int timeS = (int) Math.round(remaining / Math.max(speedMs, MIN_SPEED_MS));
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
                Collections.<Lane>emptyList(), 0, remaining,
                (int) Math.round(remaining / Math.max(speedMs, MIN_SPEED_MS)));
    }
}
