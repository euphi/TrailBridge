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
    public void trackWithBareWayPointsGetsTurnsFromGeometry() throws Exception {
        // bikerouter.de: the track is the route, the rtept are only start / via / destination
        GpxRoute base = Routes.polyline(L, d -> 100);
        StringBuilder trk = new StringBuilder();
        for (int i = 0; i < base.pointCount(); i++) {
            trk.append("<trkpt lat=\"").append(base.lat[i]).append("\" lon=\"").append(base.lon[i]).append("\"/>");
        }
        String gpx = "<gpx><rte><name>Tour</name>"
                + rtept(0, 0, "<name>Start</name>")
                + rtept(0, 300, "<name>Via</name>")
                + rtept(300, 300, "<name>Ziel</name>")
                + "</rte><trk><trkseg>" + trk + "</trkseg></trk></gpx>";
        GpxRoute r = GpxParser.parse(Routes.stream(gpx), "x");
        assertFalse(r.hasRoutingInfo);                        // the hints are from the geometry
        assertEquals(3, r.steps.size());                      // DEPART, turn, ARRIVE
        assertEquals(Maneuver.TURN_RIGHT, r.steps.get(1).maneuver);
        assertEquals(300, r.steps.get(1).distM, 12);
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

    private static String wpt(double east, double north, String body) {
        return "<wpt lat=\"" + Routes.lat(north) + "\" lon=\"" + Routes.lon(east) + "\">" + body + "</wpt>";
    }

    @Test
    public void waypointsOnTheRouteInRidingOrder() throws Exception {
        String gpx = Routes.trkGpx(L).replace("<trk>",
                wpt(200, 305, "<name>Bäcker</name><sym>Food</sym>")       // on the second leg, 5 m beside it
                        + wpt(3, 100, "")                                      // no name
                        + wpt(150, 150, "<name>Abseits</name>")                // 150 m from either leg
                        + "<trk>");
        GpxRoute r = GpxParser.parse(Routes.stream(gpx), "x");
        assertEquals("T", r.name);                            // a wpt's name is not the route's
        assertEquals(3, r.steps.size());                      // and waypoints are no manoeuvres
        assertEquals(2, r.waypoints.size());
        assertEquals("", r.waypoints.get(0).name);
        assertEquals(100, r.waypoints.get(0).distM, 2);
        assertEquals("Bäcker", r.waypoints.get(1).name);
        assertEquals(500, r.waypoints.get(1).distM, 2);
    }

    @Test
    public void waypointOnARouteThatDoublesBackCountsTheFirstTime() throws Exception {
        String gpx = Routes.trkGpx(new double[][]{{0, 0}, {0, 1000}, {0, 0}})
                .replace("<trk>", wpt(0, 400, "<name>Brunnen</name>") + "<trk>");
        GpxRoute r = GpxParser.parse(Routes.stream(gpx), "x");
        assertEquals(400, r.waypoints.get(0).distM, 2);
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

    private static String timedTrk(String extra) {
        return "<gpx xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\"><trk><name>Fahrt</name><trkseg>"
                + "<trkpt lat=\"52.5\" lon=\"13.4\"><ele>40</ele><time>2026-09-30T08:00:00Z</time>" + extra.replace("%HR%", "120").replace("%CAD%", "80") + "</trkpt>"
                + "<trkpt lat=\"52.501\" lon=\"13.4\"><ele>41</ele><time>2026-09-30T08:00:20.500Z</time>" + extra.replace("%HR%", "125").replace("%CAD%", "0") + "</trkpt>"
                + "<trkpt lat=\"52.502\" lon=\"13.4\"><ele>43</ele><time>2026-09-30T10:00:40+02:00</time>" + extra.replace("%HR%", "130").replace("%CAD%", "85") + "</trkpt>"
                + "</trkseg></trk></gpx>";
    }

    @Test
    public void timeHeartRateAndCadenceFromTrackPointExtension() throws Exception {
        String ext = "<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>%HR%</gpxtpx:hr>"
                + "<gpxtpx:cad>%CAD%</gpxtpx:cad></gpxtpx:TrackPointExtension></extensions>";
        GpxRoute r = GpxParser.parse(Routes.stream(timedTrk(ext)), "x");
        assertTrue(r.hasTimes());
        assertTrue(r.hasHeartRate());
        assertTrue(r.hasCadence());
        assertEquals(20500, r.timeMs[1] - r.timeMs[0]);
        assertEquals(19500, r.timeMs[2] - r.timeMs[1]);          // +02:00 offset honoured
        assertEquals(120, r.hr[0]);
        assertEquals(130, r.hr[2]);
        assertEquals(0, r.cad[1]);                               // coasting is a value, not "missing"
        assertEquals(85, r.cad[2]);
    }

    @Test
    public void routePointsCarryTimesToo() throws Exception {
        // a planned route with the router's time estimate; BRouter's seconds-to-the-next-hint
        // in the extensions are not a time stamp
        String gpx = "<gpx><rte>"
                + rtept(0, 0, "<time>1970-01-01T00:00:00.000Z</time><extensions><time>3</time></extensions>")
                + rtept(0, 300, "<time>1970-01-01T00:01:00.000Z</time>")
                + rtept(300, 300, "<time>1970-01-01T00:02:30.500Z</time>") + "</rte></gpx>";
        GpxRoute r = GpxParser.parse(Routes.stream(gpx), "x");
        assertTrue(r.hasTimes());
        assertEquals(0, r.timeMs[0]);
        assertEquals(150500, r.timeMs[2]);
    }

    @Test
    public void powerFromExtensionVariants() throws Exception {
        for (String ext : new String[]{
                "<extensions><power>%P%</power></extensions>",
                "<extensions><gpxpx:PowerExtension><gpxpx:PowerInWatts>%P%</gpxpx:PowerInWatts></gpxpx:PowerExtension></extensions>"}) {
            String gpx = "<gpx><trk><trkseg>"
                    + "<trkpt lat=\"52.5\" lon=\"13.4\">" + ext.replace("%P%", "210") + "</trkpt>"
                    + "<trkpt lat=\"52.501\" lon=\"13.4\">" + ext.replace("%P%", "0") + "</trkpt>"
                    + "<trkpt lat=\"52.502\" lon=\"13.4\">" + ext.replace("%P%", "180") + "</trkpt>"
                    + "</trkseg></trk></gpx>";
            GpxRoute r = GpxParser.parse(Routes.stream(gpx), "x");
            assertTrue(ext, r.hasPower());
            assertEquals(210, r.power[0]);
            assertEquals(0, r.power[1]);                           // coasting is a value
        }
    }

    @Test
    public void plainTrackHasNoRecordedSensors() throws Exception {
        GpxRoute r = GpxParser.parse(Routes.stream(Routes.trkGpx(L)), "x");
        assertFalse(r.hasTimes());
        assertFalse(r.hasHeartRate());
        assertFalse(r.hasCadence());
        assertFalse(r.hasPower());
    }

    @Test
    public void implausibleSensorValuesCountAsMissing() throws Exception {
        // 0 bpm / 255 are what straps write while they have no contact
        String ext = "<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>0</gpxtpx:hr>"
                + "<gpxtpx:cad>255</gpxtpx:cad></gpxtpx:TrackPointExtension></extensions>";
        GpxRoute r = GpxParser.parse(Routes.stream(timedTrk(ext)), "x");
        assertFalse(r.hasHeartRate());
        assertFalse(r.hasCadence());
        assertTrue(r.hasTimes());
    }
}
