package com.euphi.trailbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What still lies ahead on an imported route: the destination, the waypoints
 * and the climbs (see PROTOCOL.md "Streckenübersicht-Service"). Follows the
 * rider's progress; something that is passed drops out.
 *
 * No Android dependencies. Not thread-safe (RouteNavigator calls it from the
 * main thread only).
 */
public final class RouteOverview {

    static final String DESTINATION_NAME = "Ziel";
    /** A waypoint the file gives no name is called this plus its number among the route's waypoints. */
    static final String UNNAMED_PREFIX = "Wegpunkt ";

    /** A place with its position as the route's remaining distance there. */
    public static final class Waypoint {
        public final String name;
        /** Route distance from here to the destination, metres -- the anchor the BikeComputer counts from. */
        public final int remainingAtM;
        /** Time from here to the destination by the file's time stamps, seconds; -1 if it has none. */
        public final int remainingTimeAtS;
        public final boolean destination;
        final double distM;

        Waypoint(String name, double distM, GpxRoute route, RouteTimes times, boolean destination) {
            this.name = name;
            this.distM = distM;
            this.remainingAtM = (int) Math.round(Math.max(0, route.totalM - distM));
            this.remainingTimeAtS = times == null ? -1 : (int) Math.round(times.remainingS(distM));
            this.destination = destination;
        }
    }

    /** One climb of the route, foot to summit. */
    public static final class Climb {
        /** 1-based, in riding order over the whole route. */
        public final int number;
        public final int gainM;
        public final int lengthM;
        /** Route distance from the foot to the destination, metres. */
        public final int footRemainingAtM;
        final double footM;
        final double summitM;

        Climb(int number, ElevationProfile.Climb c, double totalM) {
            this.number = number;
            this.gainM = (int) Math.round(c.gainM());
            this.lengthM = (int) Math.round(c.lengthM());
            this.footRemainingAtM = (int) Math.round(Math.max(0, totalM - c.footM));
            this.footM = c.footM;
            this.summitM = c.summitM;
        }
    }

    private final Waypoint destination;
    private final List<Waypoint> waypoints = new ArrayList<>();   // without the destination
    private final List<Climb> climbs = new ArrayList<>();
    // Index of the first one still ahead.
    private int waypointCursor = 0;
    private int climbCursor = 0;

    /**
     * @param routeClimbs the route's climbs in riding order (empty without elevation data)
     * @param times       the file's timeline, null if it has no time stamps
     */
    RouteOverview(GpxRoute route, List<ElevationProfile.Climb> routeClimbs, RouteTimes times) {
        destination = new Waypoint(DESTINATION_NAME, route.totalM, route, times, true);
        for (int i = 0; i < route.waypoints.size(); i++) {
            GpxRoute.Waypoint w = route.waypoints.get(i);
            String name = w.name.isEmpty() ? UNNAMED_PREFIX + (i + 1) : w.name;
            waypoints.add(new Waypoint(name, w.distM, route, times, false));
        }
        for (int i = 0; i < routeClimbs.size(); i++) {
            climbs.add(new Climb(i + 1, routeClimbs.get(i), route.totalM));
        }
    }

    /**
     * Moves on to the rider's position. A waypoint is passed when the rider
     * reaches it, a climb at its summit; either only comes back once the
     * rider is well before it again (GPS jitter, a jump back in the playback).
     *
     * @return true if what lies ahead changed
     */
    public boolean update(double progressM) {
        int wp = waypointCursor;
        while (wp < waypoints.size() && progressM >= waypoints.get(wp).distM) wp++;
        while (wp > 0 && progressM < waypoints.get(wp - 1).distM - RouteNavigator.REENTER_M) wp--;

        int cl = climbCursor;
        while (cl < climbs.size() && progressM >= climbs.get(cl).summitM - RouteNavigator.SUMMIT_REACHED_M) cl++;
        while (cl > 0 && progressM < climbs.get(cl - 1).summitM - RouteNavigator.REENTER_M) cl--;

        boolean changed = wp != waypointCursor || cl != climbCursor;
        waypointCursor = wp;
        climbCursor = cl;
        return changed;
    }

    public Waypoint destination() {
        return destination;
    }

    /** The waypoints still ahead, nearest first, without the destination. */
    public List<Waypoint> waypointsAhead() {
        return Collections.unmodifiableList(waypoints.subList(waypointCursor, waypoints.size()));
    }

    /** The climbs whose summit is still ahead, nearest first -- the first may be the one the rider is on. */
    public List<Climb> climbsAhead() {
        return Collections.unmodifiableList(climbs.subList(climbCursor, climbs.size()));
    }

    /** Number of climbs on the whole route. */
    public int climbTotal() {
        return climbs.size();
    }
}
