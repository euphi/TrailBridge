package com.euphi.trailbridge;

/** One elevation-profile snapshot as it goes over the wire (see PROTOCOL.md). */
public final class ProfileFrame {

    /** Route distance to the destination at profile point 0, metres. */
    public final int startRemainingM;
    public final int stepM;
    /** Altitude of point 0 in decimetres. */
    public final int baseAltDm;
    /** Altitude change per step in decimetres (point k+1 minus point k). */
    public final byte[] deltasDm;
    /** Route distance from the start of the route to point 0 -- app-side only, not sent. */
    public final double startAlongM;

    public ProfileFrame(int startRemainingM, int stepM, int baseAltDm, byte[] deltasDm,
                        double startAlongM) {
        this.startRemainingM = startRemainingM;
        this.stepM = stepM;
        this.baseAltDm = baseAltDm;
        this.deltasDm = deltasDm;
        this.startAlongM = startAlongM;
    }

    /** Length covered by the profile, metres. */
    public int lengthM() {
        return deltasDm.length * stepM;
    }
}
