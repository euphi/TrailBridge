package com.euphi.trailbridge;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * Reads a GPX file into a {@link GpxRoute}.
 *
 * Geometry: the track (trk/trkseg/trkpt) if there is one, else the route
 * points (rte/rtept).
 *
 * Manoeuvres, in this order of preference:
 *  1. OsmAnd's own route segments (trk/extensions/route/segment, attributes
 *     turnType + startTrkptIdx -- verified against OsmAnd's GpxUtilities.kt,
 *     turnType uses the same codes as the AIDL turn info, see
 *     Maneuver.fromOsmAndXml).
 *  2. rtept elements: an OsmAnd/BRouter-style extension "turn", else
 *     free text from type/sym/desc/name (Maneuver.fromText).
 *  3. Nothing in the file -> TurnDetector derives them from the geometry.
 * As soon as the file carries routing info (1 or 2 -- any rtept counts),
 * step 3 is skipped, even if no manoeuvre could be read from it.
 */
public final class GpxParser {

    public static class GpxException extends Exception {
        public GpxException(String message, Throwable cause) {
            super(message, cause);
        }

        public GpxException(String message) {
            super(message);
        }
    }

    private static final Pattern EXIT_AFTER = Pattern.compile("(?i)(?:exit|ausfahrt)\\D{0,8}(\\d+)");
    private static final Pattern EXIT_BEFORE = Pattern.compile("(?i)(\\d+)\\.?\\s*(?:st|nd|rd|th)?\\s*(?:exit|ausfahrt)");
    private static final Pattern RNDB_CODE = Pattern.compile("^RN[DL]B(\\d+)$");

    private GpxParser() {
    }

    public static GpxRoute parse(InputStream in, String fallbackName) throws GpxException {
        Handler h = new Handler();
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            try {
                // GPX from share intents is untrusted input.
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // Android's parser doesn't know this feature; it doesn't
                // resolve external entities by default either.
            }
            f.newSAXParser().parse(new InputSource(in), h);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new GpxException("Keine gültige GPX-Datei: " + e.getMessage(), e);
        }
        return h.build(fallbackName);
    }

    // ------------------------------------------------------------------

    private static final class Pt {
        double lat, lon;
        float ele = Float.NaN;
        String name, desc, type, sym, turn;
    }

    private static final class OsmAndSeg {
        int index;
        String turnType;
    }

    private static final class Handler extends DefaultHandler {
        private final List<String> stack = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        private final List<Pt> trk = new ArrayList<>();
        private final List<Pt> rte = new ArrayList<>();
        private final List<OsmAndSeg> osmandSegs = new ArrayList<>();
        private Pt current;
        private int trkSegBase;   // trk.size() at the start of the current trkseg
        private String routeName;
        private String trackName;

        private static String local(String qName) {
            int c = qName.indexOf(':');
            return (c >= 0 ? qName.substring(c + 1) : qName).toLowerCase(java.util.Locale.ROOT);
        }

        private boolean inside(String name) {
            return stack.contains(name);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes a) {
            String n = local(qName);
            stack.add(n);
            text.setLength(0);
            switch (n) {
                case "trkseg":
                    trkSegBase = trk.size();
                    break;
                case "trkpt":
                case "rtept":
                    current = new Pt();
                    current.lat = parseDouble(a.getValue("lat"));
                    current.lon = parseDouble(a.getValue("lon"));
                    break;
                case "segment":
                    if (inside("route") && inside("extensions") && inside("trk")) {
                        String idx = a.getValue("startTrkptIdx");
                        String type = a.getValue("turnType");
                        String skip = a.getValue("skipTurn");
                        if (idx != null && type != null && !"true".equalsIgnoreCase(skip)) {
                            OsmAndSeg s = new OsmAndSeg();
                            try {
                                s.index = trkSegBase + Integer.parseInt(idx.trim());
                            } catch (NumberFormatException e) {
                                break;
                            }
                            s.turnType = type.trim();
                            osmandSegs.add(s);
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String n = local(qName);
            String value = text.toString().trim();
            stack.remove(stack.size() - 1);
            String parent = stack.isEmpty() ? "" : stack.get(stack.size() - 1);

            if (current != null && (parent.equals("trkpt") || parent.equals("rtept")
                    || (inside("rtept") && parent.equals("extensions")))) {
                switch (n) {
                    case "ele":
                        try {
                            current.ele = Float.parseFloat(value);
                        } catch (NumberFormatException ignored) {
                        }
                        break;
                    case "name":
                        current.name = value;
                        break;
                    case "desc":
                    case "cmt":
                        if (current.desc == null || current.desc.isEmpty()) current.desc = value;
                        break;
                    case "type":
                        current.type = value;
                        break;
                    case "sym":
                        current.sym = value;
                        break;
                    case "turn":
                    case "turntype":
                        current.turn = value;
                        break;
                    default:
                        break;
                }
            } else if (n.equals("name")) {
                if (parent.equals("rte")) routeName = value;
                else if (parent.equals("trk")) trackName = value;
            }

            if (n.equals("trkpt") && current != null) {
                trk.add(current);
                current = null;
            } else if (n.equals("rtept") && current != null) {
                rte.add(current);
                current = null;
            }
            text.setLength(0);
        }

        GpxRoute build(String fallbackName) throws GpxException {
            List<Pt> geometry = trk.size() >= 2 ? trk : rte;
            if (geometry.size() < 2) {
                throw new GpxException("Die GPX-Datei enthält keine Route oder keinen Track (mind. 2 Punkte).");
            }
            int n = geometry.size();
            double[] lat = new double[n];
            double[] lon = new double[n];
            float[] ele = new float[n];
            for (int i = 0; i < n; i++) {
                Pt p = geometry.get(i);
                lat[i] = p.lat;
                lon[i] = p.lon;
                ele[i] = p.ele;
            }
            String name = routeName != null && !routeName.isEmpty() ? routeName
                    : trackName != null && !trackName.isEmpty() ? trackName : fallbackName;

            GpxRoute geo = new GpxRoute(name, lat, lon, ele, new ArrayList<GpxRoute.Step>(), false);
            List<GpxRoute.Step> steps = new ArrayList<>();
            boolean hasInfo = false;

            if (geometry == trk && !osmandSegs.isEmpty()) {
                hasInfo = true;
                for (OsmAndSeg s : osmandSegs) {
                    if (s.index < 0 || s.index >= n) continue;
                    int m = Maneuver.fromOsmAndXml(s.turnType);
                    if (m == Maneuver.NONE || m == Maneuver.STRAIGHT) continue;
                    Matcher rb = RNDB_CODE.matcher(s.turnType);
                    steps.add(new GpxRoute.Step(geo.cum[s.index], m,
                            rb.matches() ? Integer.parseInt(rb.group(1)) : 0, ""));
                }
            } else if (!rte.isEmpty()) {
                hasInfo = true;
                double from = 0;
                for (int i = 0; i < rte.size(); i++) {
                    Pt p = rte.get(i);
                    int[] m = maneuverOf(p);
                    if (m[0] == Maneuver.NONE) continue;
                    double at;
                    if (geometry == rte) {
                        at = geo.cum[i];
                    } else {
                        at = alongTrack(geo, p.lat, p.lon, from);
                        from = at;
                    }
                    steps.add(new GpxRoute.Step(at, m[0], m[1], p.name));
                }
            }

            if (!hasInfo) {
                steps = TurnDetector.detect(geo);
            }
            return new GpxRoute(name, lat, lon, ele, steps, hasInfo);
        }

        /** {maneuver, roundaboutExit}; maneuver NONE if the point says nothing usable. */
        private static int[] maneuverOf(Pt p) {
            int m = Maneuver.NONE;
            int exit = 0;
            if (p.turn != null) {
                m = Maneuver.fromOsmAndXml(p.turn);
                Matcher rb = RNDB_CODE.matcher(p.turn);
                if (rb.matches()) exit = Integer.parseInt(rb.group(1));
            }
            if (m == Maneuver.NONE) {
                String joined = join(p.type, p.sym, p.desc, p.name);
                m = Maneuver.fromText(joined);
                if (m == Maneuver.ROUNDABOUT) {
                    Matcher a = EXIT_AFTER.matcher(joined);
                    Matcher b = EXIT_BEFORE.matcher(joined);
                    if (a.find()) exit = Integer.parseInt(a.group(1));
                    else if (b.find()) exit = Integer.parseInt(b.group(1));
                }
            }
            return new int[]{m, exit};
        }

        private static String join(String... parts) {
            StringBuilder b = new StringBuilder();
            for (String s : parts) {
                if (s != null) b.append(s).append(' ');
            }
            return b.toString();
        }

        /** Distance along the route of the point on it closest to (lat, lon), searching forward from `from`. */
        private static double alongTrack(GpxRoute r, double lat, double lon, double from) {
            double best = Double.MAX_VALUE;
            double bestAlong = from;
            for (int i = 0; i < r.pointCount() - 1; i++) {
                if (r.cum[i + 1] < from) continue;
                double ax = Geo.eastM(lat, lon, r.lat[i], r.lon[i]);
                double ay = Geo.northM(lat, r.lat[i]);
                double bx = Geo.eastM(lat, lon, r.lat[i + 1], r.lon[i + 1]);
                double by = Geo.northM(lat, r.lat[i + 1]);
                double dx = bx - ax, dy = by - ay;
                double len2 = dx * dx + dy * dy;
                double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
                double px = ax + t * dx, py = ay + t * dy;
                double d = Math.sqrt(px * px + py * py);
                if (d < best) {
                    best = d;
                    bestAlong = Math.max(from, r.cum[i] + t * (r.cum[i + 1] - r.cum[i]));
                }
            }
            return bestAlong;
        }

        private static double parseDouble(String s) {
            try {
                return s == null ? 0 : Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
