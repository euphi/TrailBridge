package com.euphi.trailbridge;

/** One elevation-profile snapshot as it goes over the wire (see PROTOCOL.md). */
public final class ProfileFrame {

    /** Route distance to the destination at profile point 0, metres. */
    public final int startRemainingM;
    public final int stepM;
    /** Altitude of point 0 in decimetres. */
    public final int baseAltDm;
    /** Altitude change per step (point k+1 minus point k), in units of deltaScaleDm decimetres. */
    public final byte[] deltasDm;
    /** Unit of the deltas in decimetres; 1 unless the raster is too coarse for a byte of decimetres. */
    public final int deltaScaleDm;
    /** Route distance from the start of the route to point 0 -- app-side only, not sent. */
    public final double startAlongM;

    public ProfileFrame(int startRemainingM, int stepM, int baseAltDm, byte[] deltasDm,
                        double startAlongM) {
        this(startRemainingM, stepM, baseAltDm, deltasDm, 1, startAlongM);
    }

    public ProfileFrame(int startRemainingM, int stepM, int baseAltDm, byte[] deltasDm,
                        int deltaScaleDm, double startAlongM) {
        this.startRemainingM = startRemainingM;
        this.stepM = stepM;
        this.baseAltDm = baseAltDm;
        this.deltasDm = deltasDm;
        this.deltaScaleDm = deltaScaleDm;
        this.startAlongM = startAlongM;
    }

    /** Length covered by the profile, metres. */
    public int lengthM() {
        return deltasDm.length * stepM;
    }

    /** Altitudes of the N+1 profile points in metres (BASE_ALT_DM plus the running sum of the deltas). */
    public float[] altitudesM() {
        float[] alt = new float[deltasDm.length + 1];
        int dm = baseAltDm;
        alt[0] = dm / 10f;
        for (int k = 0; k < deltasDm.length; k++) {
            dm += deltasDm[k] * deltaScaleDm;
            alt[k + 1] = dm / 10f;
        }
        return alt;
    }

    /** Altitude at a distance from the first point, linearly interpolated, clamped to the profile. */
    public double altitudeAtM(double offsetM) {
        float[] alt = altitudesM();
        double x = Math.max(0, Math.min(offsetM, lengthM())) / stepM;
        int i = (int) Math.floor(x);
        if (i >= alt.length - 1) return alt[alt.length - 1];
        return alt[i] + (x - i) * (alt[i + 1] - alt[i]);
    }

    /**
     * Where the rider is in the profile, in metres from its first point -- the same
     * way the BikeComputer works it out (PROTOCOL.md): START_REMAINING_DISTANCE_M minus
     * the REMAINING_DISTANCE_M of the nav frame.
     *
     * The profile's first point is the raster sample nearest to the rider or the one behind, so
     * the rider can be a little "before" it; one step of slack either way is clamped to the ends.
     *
     * @return NaN if the rider is further outside than that: the profile is stale or not reached yet
     */
    public double riderOffsetM(int remainingDistanceM) {
        double offset = startRemainingM - remainingDistanceM;
        if (offset < -stepM || offset > lengthM() + stepM) return Double.NaN;
        return Math.max(0, Math.min(offset, lengthM()));
    }
}
