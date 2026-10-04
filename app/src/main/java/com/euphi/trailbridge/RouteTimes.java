package com.euphi.trailbridge;

/**
 * How long the route takes according to the time stamps of its GPX file: a
 * recorded ride, or a planned one whose router wrote its estimate into the
 * file (BRouter does with "showtime" in the profile). The same timeline the
 * playback rides (RoutePlayer), so a stop counts for RoutePlayer.MAX_PAUSE_S
 * at most.
 */
final class RouteTimes {

    private final double[] dAt;   // route distance at second k of the ride

    private RouteTimes(double[] dAt) {
        this.dAt = dAt;
    }

    /** @return null if the file has no (usable) time stamps */
    static RouteTimes of(GpxRoute r) {
        double[] d = r.totalM < 1 ? null : RoutePlayer.fromTimestamps(r);
        return d == null ? null : new RouteTimes(RoutePlayer.compressPauses(d, r.totalM));
    }

    /** Seconds from the start of the route to a distance along it. */
    double atS(double distM) {
        return RoutePlayer.timeForDistance(dAt, distM);
    }

    /** Seconds from a distance along the route to its end. */
    double remainingS(double distM) {
        return Math.max(0, dAt.length - 1 - atS(distM));
    }
}
