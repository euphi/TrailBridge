package com.euphi.trailbridge;

import androidx.annotation.Nullable;

/**
 * The "Moduswahl" of the main screen: where the navigation comes from. It decides which
 * cards the main screen shows -- nothing else; the navi mode (NaviActivity) shows the same in every mode.
 * Pure logic, no Android dependencies.
 */
enum UiMode {
    /** Navigation from OsmAnd. */
    OSMAND("osmand"),
    /** TrailBridge navigates a GPX route itself, with the real GPS position. */
    GPX_NAV("gpx_nav"),
    /** The loaded GPX route is played as a test ride (fake position). */
    SIMULATION("simulation"),
    /** Everything on one screen. */
    ALL("all");

    /** What the main screen offers for the test ride panel in a mode. */
    enum TestPanel {
        /** Not at all. */
        NONE,
        /** Behind the "Testfahrt" button. */
        TOGGLE,
        /** Always open (once a route is loaded). */
        ALWAYS
    }

    final String key;

    UiMode(String key) {
        this.key = key;
    }

    static UiMode fromKey(@Nullable String key) {
        for (UiMode m : values()) {
            if (m.key.equals(key)) return m;
        }
        return ALL;   // the screen as it was before there were modes
    }

    /**
     * Whether the OsmAnd row of the connection card is shown. In the GPX modes OsmAnd only
     * matters when it happens to be connected -- otherwise it is left out completely.
     */
    boolean showsOsmand(boolean osmandConnected) {
        return this == OSMAND || this == ALL || osmandConnected;
    }

    /** The route card (GPX loading). */
    boolean showsRoute() {
        return this != OSMAND;
    }

    /** "Route starten": a ride with the real GPS. The simulation starts its own navigation. */
    boolean showsRouteStart() {
        return this == GPX_NAV || this == ALL;
    }

    TestPanel testPanel() {
        switch (this) {
            case SIMULATION: return TestPanel.ALWAYS;
            case ALL: return TestPanel.TOGGLE;
            default: return TestPanel.NONE;
        }
    }

    /** The raw-data card (debug output) -- only on the everything screen. */
    boolean showsRaw() {
        return this == ALL;
    }

    /** A running test ride survives a switch to this mode. */
    boolean keepsPlayback() {
        return this == SIMULATION || this == ALL;
    }

    /**
     * An active route survives a switch to this mode. Not stopping it would leave
     * it navigating (and overriding OsmAnd) with its controls hidden.
     *
     * @param playbackActive a test ride is running (it navigates the route by itself)
     */
    boolean keepsRoute(boolean playbackActive) {
        switch (this) {
            case OSMAND: return false;
            case SIMULATION: return playbackActive;
            default: return true;
        }
    }
}
