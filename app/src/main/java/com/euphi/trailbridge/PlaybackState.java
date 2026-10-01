package com.euphi.trailbridge;

/**
 * Snapshot of the GPX test playback for the UI (see RoutePlayer and
 * TrailBridgeService). Sent about once a second while a playback runs, and on
 * every change of the settings.
 */
public final class PlaybackState {

    public final GpxRoute route;

    /** A playback session is running (playing or paused): the BikeComputer gets the simulated position. */
    public final boolean active;
    public final boolean playing;
    /** The end of the route was reached. */
    public final boolean finished;

    public final double distM;
    public final double totalM;
    public final double timeS;
    public final double durationS;
    public final double speedMs;
    /** bpm / rpm / W of the last sample, -1 = no such sensor (or no sample yet). */
    public final int hr;
    public final int cad;
    /** watts, -1 = no power meter. */
    public final int power;
    /** Height of the last sample in metres, NaN = none. */
    public final double eleM;

    public final RoutePlayer.Source speedSource;
    public final RoutePlayer.Source hrSource;
    public final RoutePlayer.Source cadSource;
    public final RoutePlayer.Source powerSource;
    /** The route has elevation, so every sample carries a height. */
    public final boolean hasHeight;

    public PlaybackState(GpxRoute route, boolean active, boolean playing, boolean finished,
                         double distM, double timeS, double durationS, double speedMs,
                         int hr, int cad, int power, double eleM,
                         RoutePlayer.Source speedSource, RoutePlayer.Source hrSource,
                         RoutePlayer.Source cadSource, RoutePlayer.Source powerSource,
                         boolean hasHeight) {
        this.route = route;
        this.active = active;
        this.playing = playing;
        this.finished = finished;
        this.distM = distM;
        this.totalM = route.totalM;
        this.timeS = timeS;
        this.durationS = durationS;
        this.speedMs = speedMs;
        this.hr = hr;
        this.cad = cad;
        this.power = power;
        this.eleM = eleM;
        this.speedSource = speedSource;
        this.hrSource = hrSource;
        this.cadSource = cadSource;
        this.powerSource = powerSource;
        this.hasHeight = hasHeight;
    }
}
