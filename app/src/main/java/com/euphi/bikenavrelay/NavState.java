package com.euphi.bikenavrelay;

import java.util.Objects;

/**
 * Everything we forward to the BikeComputer for one navigation snapshot.
 *
 * Built from OsmAnd's AppInfoParams.getTurnInfo() bundle (see OsmAndLink) plus
 * the top-level leftDistance/leftTime fields. Kept as a plain, comparable
 * value object so callers can cheaply decide "did anything worth sending
 * actually change" before triggering a BLE indicate (Meilenstein 2).
 */
public final class NavState {

    public final boolean navigating;

    public final int maneuver;              // Maneuver.* constant
    public final int maneuverDistanceM;      // distance to that maneuver, metres
    public final int roundaboutExit;         // only meaningful if maneuver == ROUNDABOUT
    public final String streetName;          // street/road for that maneuver

    public final int nextManeuver;           // the maneuver after this one
    public final int nextManeuverDistanceM;  // distance from THAT maneuver to the one after
    public final String nextStreetName;

    public final int remainingDistanceM;     // to destination
    public final int remainingTimeS;         // to destination

    public static final NavState NONE = new NavState(
            false, Maneuver.NONE, 0, 0, "", Maneuver.NONE, 0, "", 0, 0);

    public NavState(boolean navigating, int maneuver, int maneuverDistanceM, int roundaboutExit,
                     String streetName, int nextManeuver, int nextManeuverDistanceM,
                     String nextStreetName, int remainingDistanceM, int remainingTimeS) {
        this.navigating = navigating;
        this.maneuver = maneuver;
        this.maneuverDistanceM = maneuverDistanceM;
        this.roundaboutExit = roundaboutExit;
        this.streetName = streetName == null ? "" : streetName;
        this.nextManeuver = nextManeuver;
        this.nextManeuverDistanceM = nextManeuverDistanceM;
        this.nextStreetName = nextStreetName == null ? "" : nextStreetName;
        this.remainingDistanceM = remainingDistanceM;
        this.remainingTimeS = remainingTimeS;
    }

    /**
     * True if this differs from other in a way the BikeComputer display would
     * actually need to react to. Distance ticking down by a metre or ETA
     * ticking down by a second is deliberately NOT "changed" here -- that's
     * what the heartbeat resend is for (Meilenstein 2); this is for deciding
     * when to send an out-of-band update immediately.
     */
    public boolean differsMeaningfullyFrom(NavState other) {
        if (other == null) return true;
        return navigating != other.navigating
                || maneuver != other.maneuver
                || roundaboutExit != other.roundaboutExit
                || !streetName.equals(other.streetName)
                || nextManeuver != other.nextManeuver
                || !nextStreetName.equals(other.nextStreetName)
                // a jump of >15m in one poll tick means a route recalculation,
                // not just us getting closer -- worth an immediate resend.
                || Math.abs(maneuverDistanceM - other.maneuverDistanceM) > 15;
    }

    @Override
    public String toString() {
        if (!navigating) return "NavState{no active route}";
        String s = "NavState{" + Maneuver.name(maneuver) + " in " + maneuverDistanceM + "m";
        if (maneuver == Maneuver.ROUNDABOUT) {
            s += " (exit " + roundaboutExit + ")";
        }
        s += " on \"" + streetName + "\", then " + Maneuver.name(nextManeuver)
                + " in " + nextManeuverDistanceM + "m on \"" + nextStreetName + "\""
                + ", " + remainingDistanceM + "m / " + remainingTimeS + "s to destination}";
        return s;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NavState)) return false;
        NavState n = (NavState) o;
        return navigating == n.navigating && maneuver == n.maneuver
                && maneuverDistanceM == n.maneuverDistanceM && roundaboutExit == n.roundaboutExit
                && streetName.equals(n.streetName) && nextManeuver == n.nextManeuver
                && nextManeuverDistanceM == n.nextManeuverDistanceM
                && nextStreetName.equals(n.nextStreetName)
                && remainingDistanceM == n.remainingDistanceM && remainingTimeS == n.remainingTimeS;
    }

    @Override
    public int hashCode() {
        return Objects.hash(navigating, maneuver, maneuverDistanceM, roundaboutExit, streetName,
                nextManeuver, nextManeuverDistanceM, nextStreetName, remainingDistanceM, remainingTimeS);
    }
}
