package com.euphi.trailbridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The client against a real HTTP server on localhost that plays the BikeLog service. */
public class PlannedRouteClientTest {

    private ServerSocket server;
    private PlannedRouteClient client;
    private final List<String> requests = new CopyOnWriteArrayList<>();   // "METHOD path?query"
    private volatile String lastAuth;
    private volatile String lastIfNoneMatch;

    private volatile int status = 200;
    private volatile byte[] body = new byte[0];
    private final List<String[]> headers = new CopyOnWriteArrayList<>();

    @Before
    public void start() throws IOException {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::serve, "fake-bikelog");
        thread.setDaemon(true);
        thread.start();
        client = new PlannedRouteClient(base() + "/", " secret ");   // trailing slash and blanks are trimmed
    }

    @After
    public void stop() throws IOException {
        server.close();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /** One request after the other, each answered as set by the test and then closed. */
    private void serve() {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                handle(s);
            } catch (IOException e) {
                // the client hung up (or the test ended)
            }
        }
    }

    private void handle(Socket s) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
        String[] request = in.readLine().split(" ");
        String auth = null;
        String ifNoneMatch = null;
        int length = 0;
        for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
            int colon = line.indexOf(':');
            String name = line.substring(0, colon).toLowerCase();
            String value = line.substring(colon + 1).trim();
            if (name.equals("authorization")) auth = value;
            if (name.equals("if-none-match")) ifNoneMatch = value;
            if (name.equals("content-length")) length = Integer.parseInt(value);
        }
        for (int i = 0; i < length; i++) in.read();
        lastAuth = auth;
        lastIfNoneMatch = ifNoneMatch;
        requests.add(request[0] + " " + request[1]);

        StringBuilder head = new StringBuilder("HTTP/1.1 " + status + " X\r\nConnection: close\r\n");
        for (String[] h : headers) head.append(h[0]).append(": ").append(h[1]).append("\r\n");
        boolean empty = status == 204 || status == 304;
        if (!empty) head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!empty) out.write(body);
        out.flush();
    }

    private void reply(int status, String body) {
        this.status = status;
        this.body = body.getBytes(StandardCharsets.UTF_8);
    }

    private static String hex(byte[] data) throws Exception {
        StringBuilder b = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(data)) b.append(String.format("%02x", x));
        return b.toString();
    }

    private static String json(String revision, String sha, long size, String gpxUrl) {
        return "{\"id\":7,\"name\":\"Rund um den See\",\"kind\":\"plan\",\"revision\":" + revision
                + ",\"marked_at\":\"2026-10-23T08:00:00Z\",\"sha256\":\"" + sha + "\",\"size_bytes\":" + size
                + ",\"distance_km\":42.5,\"ascent_m\":600,\"verdict\":\"ok\",\"gpx_url\":\"" + gpxUrl + "\"}";
    }

    private PlannedRouteClient.FetchException expectFailure(Runnable0 call) {
        try {
            call.run();
        } catch (PlannedRouteClient.FetchException e) {
            return e;
        }
        fail("expected a FetchException");
        return null;
    }

    private interface Runnable0 {
        void run() throws PlannedRouteClient.FetchException;
    }

    // ---- check ----

    @Test
    public void checkSendsTokenAndLastRevision() throws Exception {
        reply(204, "");
        client.check("41");
        assertEquals("GET /api/v1/trailbridge/route", requests.get(0));
        assertEquals("Bearer secret", lastAuth);
        assertEquals("\"41\"", lastIfNoneMatch);

        client.check(null);
        assertNull(lastIfNoneMatch);
    }

    @Test
    public void withoutTokenNoAuthorizationHeaderIsSent() throws Exception {
        reply(204, "");
        new PlannedRouteClient(base(), "").check(null);
        assertNull(lastAuth);
    }

    @Test
    public void noContentMeansNothingMarked() throws Exception {
        reply(204, "");
        assertEquals(PlannedRouteClient.Kind.NONE, client.check(null).kind);
    }

    @Test
    public void notModifiedMeansUnchanged() throws Exception {
        reply(304, "");
        assertEquals(PlannedRouteClient.Kind.UNCHANGED, client.check("5").kind);
    }

    @Test
    public void okCarriesTheRouteInfo() throws Exception {
        reply(200, json("12", "ABCDEF", 1234, "/api/v1/trailbridge/route/file/7"));
        PlannedRouteClient.Check c = client.check(null);
        assertEquals(PlannedRouteClient.Kind.NEW, c.kind);
        assertEquals("12", c.info.revision);            // a JSON number
        assertEquals("Rund um den See", c.info.name);
        assertEquals("abcdef", c.info.sha256);          // lower case
        assertEquals(1234, c.info.sizeBytes);
        assertEquals("/api/v1/trailbridge/route/file/7", c.info.gpxUrl);
    }

    @Test
    public void revisionMayBeAString() throws Exception {
        reply(200, json("\"r-3\"", "", 0, "/f"));
        assertEquals("r-3", client.check(null).info.revision);
    }

    @Test
    public void unauthorizedIsReportedAsSuch() {
        reply(401, "{}");
        PlannedRouteClient.FetchException e = expectFailure(() -> client.check(null));
        assertEquals(PlannedRouteClient.Reason.UNAUTHORIZED, e.reason);
        assertEquals(401, e.status);
    }

    @Test
    public void otherStatusIsAServerError() {
        reply(500, "oops");
        PlannedRouteClient.FetchException e = expectFailure(() -> client.check(null));
        assertEquals(PlannedRouteClient.Reason.SERVER, e.reason);
        assertEquals(500, e.status);
    }

    @Test
    public void redirectIsNotFollowed() {
        // It would carry the token to wherever the Location points.
        headers.add(new String[]{"Location", "http://127.0.0.1:1/elsewhere"});
        reply(302, "");
        PlannedRouteClient.FetchException e = expectFailure(() -> client.check(null));
        assertEquals(PlannedRouteClient.Reason.SERVER, e.reason);
        assertEquals(1, requests.size());
    }

    @Test
    public void brokenJsonIsABadResponse() {
        reply(200, "<html>not json</html>");
        assertEquals(PlannedRouteClient.Reason.BAD_RESPONSE, expectFailure(() -> client.check(null)).reason);
    }

    @Test
    public void missingGpxUrlIsABadResponse() {
        reply(200, "{\"revision\":3}");
        assertEquals(PlannedRouteClient.Reason.BAD_RESPONSE, expectFailure(() -> client.check(null)).reason);
    }

    @Test
    public void unreachableServerIsANetworkError() {
        PlannedRouteClient gone = new PlannedRouteClient("http://127.0.0.1:1", "t");
        assertEquals(PlannedRouteClient.Reason.NETWORK, expectFailure(() -> gone.check(null)).reason);
    }

    @Test
    public void addressWithoutSchemeIsRejected() {
        PlannedRouteClient bad = new PlannedRouteClient("ia216:8080", "t");
        assertEquals(PlannedRouteClient.Reason.BAD_URL, expectFailure(() -> bad.check(null)).reason);
        PlannedRouteClient ftp = new PlannedRouteClient("ftp://ia216", "t");
        assertEquals(PlannedRouteClient.Reason.BAD_URL, expectFailure(() -> ftp.check(null)).reason);
    }

    @Test
    public void portOutOfRangeIsABadUrl() {
        PlannedRouteClient bad = new PlannedRouteClient("http://127.0.0.1:99999", "t");
        assertEquals(PlannedRouteClient.Reason.BAD_URL, expectFailure(() -> bad.check(null)).reason);
    }

    // ---- download ----

    private PlannedRouteClient.Info info(byte[] data, String gpxUrl) throws Exception {
        return new PlannedRouteClient.Info("3", "x", hex(data), data.length, gpxUrl);
    }

    @Test
    public void downloadReturnsTheFileUnchanged() throws Exception {
        byte[] gpx = "<gpx>äö</gpx>".getBytes(StandardCharsets.UTF_8);
        body = gpx;
        assertArrayEquals(gpx, client.download(info(gpx, "/files/route.gpx")));
        assertEquals("GET /files/route.gpx", requests.get(0));
        assertEquals("Bearer secret", lastAuth);
    }

    @Test
    public void downloadWithoutAnnouncedHashSkipsTheCheck() throws Exception {
        body = "<gpx/>".getBytes(StandardCharsets.UTF_8);
        PlannedRouteClient.Info noHash = new PlannedRouteClient.Info("3", "x", "", 0, "/f");
        assertArrayEquals(body, client.download(noHash));
    }

    @Test
    public void downloadWithWrongHashFails() throws Exception {
        body = "<gpx/>".getBytes(StandardCharsets.UTF_8);
        PlannedRouteClient.Info wrong = info("<gpx>other</gpx>".getBytes(StandardCharsets.UTF_8), "/f");
        assertEquals(PlannedRouteClient.Reason.CHECKSUM, expectFailure(() -> client.download(wrong)).reason);
    }

    @Test
    public void downloadBeyondTheLimitFails() throws Exception {
        PlannedRouteClient.Info big = new PlannedRouteClient.Info("3", "x", "", PlannedRouteClient.MAX_BYTES + 1L, "/f");
        assertEquals(PlannedRouteClient.Reason.TOO_LARGE, expectFailure(() -> client.download(big)).reason);
        assertTrue("announced as too large: not even asked for", requests.isEmpty());

        // announced small, but sends more than the limit
        body = new byte[PlannedRouteClient.MAX_BYTES + 1];
        PlannedRouteClient.Info liar = new PlannedRouteClient.Info("3", "x", "", 10, "/f");
        assertEquals(PlannedRouteClient.Reason.TOO_LARGE, expectFailure(() -> client.download(liar)).reason);
    }

    @Test
    public void downloadNeverLeavesTheService() throws Exception {
        // The token must only go to the configured service, whatever the JSON says.
        for (String url : new String[]{"http://evil.example/x.gpx", "//evil.example/x.gpx", "x.gpx",
                "/a/http://b"}) {
            PlannedRouteClient.Info i = new PlannedRouteClient.Info("3", "x", "", 0, url);
            assertEquals(url, PlannedRouteClient.Reason.BAD_RESPONSE, expectFailure(() -> client.download(i)).reason);
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    public void downloadUnauthorized() throws Exception {
        reply(401, "no");
        PlannedRouteClient.Info i = new PlannedRouteClient.Info("3", "x", "", 0, "/f");
        assertEquals(PlannedRouteClient.Reason.UNAUTHORIZED, expectFailure(() -> client.download(i)).reason);
    }

    // ---- confirm ----

    @Test
    public void confirmPostsTheRevision() throws Exception {
        reply(200, "{}");
        assertTrue(client.confirm("12"));
        assertEquals("POST /api/v1/trailbridge/route/fetched?revision=12", requests.get(0));
        assertEquals("Bearer secret", lastAuth);
    }

    @Test
    public void confirmEncodesTheRevision() throws Exception {
        reply(200, "{}");
        client.confirm("a b&c");
        assertEquals("POST /api/v1/trailbridge/route/fetched?revision=a+b%26c", requests.get(0));
    }

    @Test
    public void conflictMeansANewerRevisionExists() throws Exception {
        reply(409, "{}");
        assertFalse(client.confirm("12"));
    }

    @Test
    public void confirmFailureIsReported() {
        reply(401, "{}");
        assertEquals(PlannedRouteClient.Reason.UNAUTHORIZED, expectFailure(() -> client.confirm("1")).reason);
        reply(500, "{}");
        assertEquals(PlannedRouteClient.Reason.SERVER, expectFailure(() -> client.confirm("1")).reason);
    }
}
