package com.euphi.trailbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GpxParserTest {

    private static final double[][] L = {{0, 0}, {0, 300}, {300, 300}};

    private static String rtept(double east, double north, String body) {
        return "<rtept lat=\"" + Routes.lat(north) + "\" lon=\"" + Routes.lon(east) + "\">" + body + "</rtept>";
    }

    @Test
    public void trackOnlyGetsTurnsFromGeometry() throws Exception {
        GpxRoute r = GpxParser.parse(Routes.stream(Routes.trkGpx(L)), "x");
        assertEquals("T", r.name);
        assertFalse(r.hasRoutingInfo);
        assertTrue(r.hasElevation());
        assertEquals(600, r.totalM, 5);
        assertEquals(3, r.steps.size());                      // DEPART, turn, ARRIVE
        assertEquals(Maneuver.DEPART, r.steps.get(0).maneuver);
        assertEquals(Maneuver.TURN_RIGHT, r.steps.get(1).maneuver);
        assertEquals(Maneuver.ARRIVE, r.steps.get(2).maneuver);
    }

    @Test
    public void rteptsSuppressGeometryTurns() throws Exception {
        // geometry says "right" at the corner; the file says "left" -> the file wins, nothing is derived
        GpxRoute base = Routes.polyline(L, d -> 100);
        StringBuilder trk = new StringBuilder();
        for (int i = 0; i < base.pointCount(); i++) {
            trk.append("<trkpt lat=\"").append(base.lat[i]).append("\" lon=\"").append(base.lon[i]).append("\"/>");
        }
        String gpx = "<gpx><rte><name>Tour</name>"
                + rtept(0, 0, "<name>Start</name>")
                + rtept(0, 300, "<name>Hauptstraße</name><desc>Turn left onto Hauptstraße</desc>")
                + "</rte><trk><trkseg>" + trk + "</trkseg></trk></gpx>";
        GpxRoute r = GpxParser.parse(Routes.stream(gpx), "x");
        assertTrue(r.hasRoutingInfo);
        assertEquals("Tour", r.name);
        assertEquals(3, r.steps.size());
        assertEquals(Maneuver.TURN_LEFT, r.steps.get(1).maneuver);
        assertEquals(300, r.steps.get(1).distM, 12);
        assertEquals("Hauptstraße", r.steps.get(1).name);
    }

    @Test
    public void rteptsWithoutInfoGiveNoHints() throws Exception {
        String gpx = "<gpx><rte>" + rtept(0, 0, "") + rtept(0, 300, "") + rtept(300, 300, "") + "</rte></gpx>";
        GpxRoute r = GpxParser.parse(Routes.stream(gpx), "fallback");
        assertTrue(r.hasRoutingInfo);
        assertEquals("fallback", r.name);
        assertEquals(2, r.steps.size());                      // just DEPART + ARRIVE, no geometry turns
    }

    @Test
    public void turnExtensionAndRoundabout() throws Exception {
        String gpx = "<gpx><rte>" + rtept(0, 0, "")
                + rtept(0, 200, "<extensions><turn>TSLR</turn></extensions>")
                + rtept(0, 400, "<extensions><turn>RNDB2</turn></extensions>")
                + rtept(0, 600, "<desc>Roundabout, take the 3rd exit</desc>")
                + rtept(0, 800, "") + "</rte></gpx>";
        GpxRoute r = GpxParser.parse(Routes.stream(gpx), "x");
        assertEquals(Maneuver.TURN_SLIGHT_RIGHT, r.steps.get(1).maneuver);
        assertEquals(Maneuver.ROUNDABOUT, r.steps.get(2).maneuver);
        assertEquals(2, r.steps.get(2).roundaboutExit);
        assertEquals(Maneuver.ROUNDABOUT, r.steps.get(3).maneuver);
        assertEquals(3, r.steps.get(3).roundaboutExit);
    }

    @Test
    public void osmAndRouteSegments() throws Exception {
        GpxRoute base = Routes.polyline(L, d -> 100);
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < base.pointCount(); i++) {
            pts.append("<trkpt lat=\"").append(base.lat[i]).append("\" lon=\"").append(base.lon[i]).append("\"/>");
        }
        String gpx = "<gpx xmlns:osmand=\"https://osmand.net\"><trk><trkseg>" + pts
                + "<extensions><osmand:route>"
                + "<osmand:segment id=\"1\" length=\"300\" startTrkptIdx=\"0\" turnType=\"C\"/>"
                + "<osmand:segment id=\"2\" length=\"300\" startTrkptIdx=\"30\" turnType=\"TL\"/>"
                + "</osmand:route></extensions></trkseg></trk></gpx>";
        GpxRoute r = GpxParser.parse(Routes.stream(gpx), "x");
        assertTrue(r.hasRoutingInfo);
        assertEquals(3, r.steps.size());
        assertEquals(Maneuver.TURN_LEFT, r.steps.get(1).maneuver);   // not the geometry's "right"
        assertEquals(300, r.steps.get(1).distM, 12);
    }

    @Test(expected = GpxParser.GpxException.class)
    public void garbageIsRejected() throws Exception {
        GpxParser.parse(Routes.stream("not xml at all"), "x");
    }

    @Test(expected = GpxParser.GpxException.class)
    public void emptyGpxIsRejected() throws Exception {
        GpxParser.parse(Routes.stream("<gpx><wpt lat=\"1\" lon=\"2\"/></gpx>"), "x");
    }

    @Test
    public void maneuverText() {
        assertEquals(Maneuver.TURN_LEFT, Maneuver.fromText("Turn left"));
        assertEquals(Maneuver.TURN_SHARP_RIGHT, Maneuver.fromText("Sharp right onto X"));
        assertEquals(Maneuver.KEEP_LEFT, Maneuver.fromText("Keep left"));
        assertEquals(Maneuver.TURN_SLIGHT_LEFT, Maneuver.fromText("leicht links abbiegen"));
        assertEquals(Maneuver.KEEP_RIGHT, Maneuver.fromText("rechts halten"));
        assertEquals(Maneuver.STRAIGHT, Maneuver.fromText("Continue straight"));
        assertEquals(Maneuver.NONE, Maneuver.fromText("Wegpunkt 3"));
        assertEquals(Maneuver.NONE, Maneuver.fromText(null));
    }
}
