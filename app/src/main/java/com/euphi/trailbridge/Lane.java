package com.euphi.trailbridge;

import java.util.Objects;

/**
 * One lane of an OsmAnd "turn_lanes" entry, already translated into our own
 * Maneuver codes (see PROTOCOL.md "Fahrspur-Informationen (LANES /
 * NEXT_LANES)"). secondary/tertiary are Maneuver.NONE when that lane only
 * has one or two possible directions.
 */
public final class Lane {

    public final int primary;
    public final int secondary;
    public final int tertiary;
    public final boolean active;

    public Lane(int primary, int secondary, int tertiary, boolean active) {
        this.primary = primary;
        this.secondary = secondary;
        this.tertiary = tertiary;
        this.active = active;
    }

    /**
     * Decodes one of OsmAnd's bit-packed per-lane ints: bit 0 = active/
     * recommended, bits 1-4 = primary turn, bits 5-8 = secondary turn,
     * bits 10-13 = tertiary turn. Verified against
     * net.osmand.router.TurnType (getPrimaryTurn/getSecondaryTurn/
     * getTertiaryTurn/lanesToString(), OsmAnd-java module, Stand
     * 2026-09-18). An unset primary (0) is mapped to STRAIGHT here,
     * mirroring TurnType.lanesToString()'s own fallback -- see PROTOCOL.md.
     */
    public static Lane fromOsmAndLaneValue(int packed) {
        boolean active = (packed & 1) == 1;
        int primaryRaw = (packed >> 1) & 0xF;
        int secondaryRaw = (packed >> 5) & 0xF;
        int tertiaryRaw = (packed >> 10) & 0xF;
        int primary = primaryRaw == 0 ? Maneuver.STRAIGHT : Maneuver.fromOsmAndLaneTurnType(primaryRaw);
        int secondary = Maneuver.fromOsmAndLaneTurnType(secondaryRaw);
        int tertiary = Maneuver.fromOsmAndLaneTurnType(tertiaryRaw);
        return new Lane(primary, secondary, tertiary, active);
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder(Maneuver.name(primary));
        if (secondary != Maneuver.NONE) s.append("+").append(Maneuver.name(secondary));
        if (tertiary != Maneuver.NONE) s.append("+").append(Maneuver.name(tertiary));
        if (active) s.append("*");
        return s.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Lane)) return false;
        Lane l = (Lane) o;
        return primary == l.primary && secondary == l.secondary
                && tertiary == l.tertiary && active == l.active;
    }

    @Override
    public int hashCode() {
        return Objects.hash(primary, secondary, tertiary, active);
    }
}
