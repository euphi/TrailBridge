package com.euphi.trailbridge;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Client of the BikeLog service for the auto-download of planned routes (TRGB-BikeComputer,
 * doc/LOGSERVICE.md, "Routes for TrailBridge"): the route marked on the service's /routes page
 * is fetched as a GPX file and, once TrailBridge has it, reported back as fetched.
 * <pre>
 *   GET  {base}/api/v1/trailbridge/route                      200 JSON | 204 none marked | 304 unchanged | 401
 *   GET  {base}{gpx_url}                                      the GPX file, unchanged
 *   POST {base}/api/v1/trailbridge/route/fetched?revision=N   200 | 409 a newer revision exists
 * </pre>
 * The calls carry {@code Authorization: Bearer <token>} only if a token is given (the service wants none
 * unless it is run with BIKELOG_REQUIRE_AUTH). They block: call them off the main thread.
 * Pure Java, no Android dependencies besides {@code org.json}.
 */
final class PlannedRouteClient {

    /** Same limit as for a GPX that is opened by hand. */
    static final int MAX_BYTES = 20 * 1024 * 1024;

    private static final String ROUTE_PATH = "/api/v1/trailbridge/route";
    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /** What the service said about the marked route. */
    static final class Info {
        /** Changes with a new file and with every marking of the route, even of the same one. */
        final String revision;
        final String name;
        /** Lower-case hex, "" if the service did not send one. */
        final String sha256;
        final long sizeBytes;
        /** Path relative to the base URL. */
        final String gpxUrl;

        Info(String revision, String name, String sha256, long sizeBytes, String gpxUrl) {
            this.revision = revision;
            this.name = name;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.gpxUrl = gpxUrl;
        }
    }

    enum Kind {
        /** 204: no route is marked. */
        NONE,
        /** 304: the marked route is the one we asked with. */
        UNCHANGED,
        /** 200: {@link Check#info} says which. */
        NEW
    }

    static final class Check {
        final Kind kind;
        @Nullable final Info info;

        Check(Kind kind, @Nullable Info info) {
            this.kind = kind;
            this.info = info;
        }
    }

    enum Reason {
        /** The base URL is not an http(s) URL. */
        BAD_URL,
        /** 401/403: the service wants a token (BIKELOG_REQUIRE_AUTH). */
        UNAUTHORIZED,
        /** Unreachable, timeout, connection dropped. */
        NETWORK,
        /** Any other HTTP status (also a redirect: it would carry the token elsewhere). */
        SERVER,
        /** Not the JSON/file the contract describes. */
        BAD_RESPONSE,
        TOO_LARGE,
        /** The file does not match the sha256 the service announced. */
        CHECKSUM
    }

    static final class FetchException extends IOException {
        final Reason reason;
        /** The HTTP status for {@link Reason#SERVER}, else 0. */
        final int status;

        FetchException(Reason reason, int status, String message) {
            super(message);
            this.reason = reason;
            this.status = status;
        }

        FetchException(Reason reason, String message) {
            this(reason, 0, message);
        }
    }

    private final String baseUrl;
    private final String token;

    /** @param token "" for none: no Authorization header is sent */
    PlannedRouteClient(String baseUrl, String token) {
        String b = baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.baseUrl = b;
        this.token = token.trim();
    }

    /**
     * @param lastRevision the revision loaded last, or null if none (then the service always answers 200
     *                     when a route is marked)
     */
    Check check(@Nullable String lastRevision) throws FetchException {
        HttpURLConnection c = open("GET", baseUrl + ROUTE_PATH);
        try {
            if (lastRevision != null) c.setRequestProperty("If-None-Match", "\"" + lastRevision + "\"");
            int status = c.getResponseCode();
            if (status == 204) return new Check(Kind.NONE, null);
            if (status == 304) return new Check(Kind.UNCHANGED, null);
            if (status != 200) throw failure(status);
            return new Check(Kind.NEW, parseInfo(readAll(c.getInputStream(), MAX_JSON_BYTES)));
        } catch (IOException e) {
            throw network(e);
        } catch (RuntimeException e) {
            throw badAddress(e);
        } finally {
            c.disconnect();
        }
    }

    /** Fetches the GPX file of {@code info}, checked against its size limit and its sha256. */
    byte[] download(Info info) throws FetchException {
        // The path comes from the service and goes behind the base URL we send the token to --
        // a full URL there would send the token wherever it points.
        if (!info.gpxUrl.startsWith("/") || info.gpxUrl.startsWith("//") || info.gpxUrl.contains("://")) {
            throw new FetchException(Reason.BAD_RESPONSE, "gpx_url is not a path: " + info.gpxUrl);
        }
        if (info.sizeBytes > MAX_BYTES) throw new FetchException(Reason.TOO_LARGE, "size_bytes " + info.sizeBytes);
        HttpURLConnection c = open("GET", baseUrl + info.gpxUrl);
        try {
            int status = c.getResponseCode();
            if (status != 200) throw failure(status);
            if (c.getContentLengthLong() > MAX_BYTES) {
                throw new FetchException(Reason.TOO_LARGE, "Content-Length " + c.getContentLengthLong());
            }
            byte[] data = readAll(c.getInputStream(), MAX_BYTES);
            if (!info.sha256.isEmpty() && !info.sha256.equals(sha256(data))) {
                throw new FetchException(Reason.CHECKSUM, "sha256 differs from " + info.sha256);
            }
            return data;
        } catch (IOException e) {
            throw network(e);
        } catch (RuntimeException e) {
            throw badAddress(e);
        } finally {
            c.disconnect();
        }
    }

    /**
     * Reports the revision as fetched.
     *
     * @return false if the service has a newer revision by now (409) -- nothing to retry, the next
     * check brings the new one
     */
    boolean confirm(String revision) throws FetchException {
        HttpURLConnection c = open("POST", baseUrl + ROUTE_PATH + "/fetched?revision=" + encode(revision));
        try {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            int status = c.getResponseCode();
            if (status == 200) return true;
            if (status == 409) return false;
            throw failure(status);
        } catch (IOException e) {
            throw network(e);
        } catch (RuntimeException e) {
            throw badAddress(e);
        } finally {
            c.disconnect();
        }
    }

    // ---- internals ----

    private HttpURLConnection open(String method, String url) throws FetchException {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new FetchException(Reason.BAD_URL, url);
        }
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            // A redirect would carry the token to wherever it points.
            c.setInstanceFollowRedirects(false);
            if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            return c;
        } catch (MalformedURLException e) {
            throw new FetchException(Reason.BAD_URL, url);
        } catch (IOException e) {
            throw network(e);
        }
    }

    private static FetchException failure(int status) {
        if (status == 401 || status == 403) return new FetchException(Reason.UNAUTHORIZED, status, "HTTP " + status);
        return new FetchException(Reason.SERVER, status, "HTTP " + status);
    }

    /**
     * E.g. a port out of range: only the connection attempt notices, with an IllegalArgumentException
     * (wrapped in a RuntimeException by some implementations). Anything else is not about the address
     * and is thrown on.
     */
    private static FetchException badAddress(RuntimeException e) {
        if (!(e instanceof IllegalArgumentException) && !(e.getCause() instanceof IllegalArgumentException)) {
            throw e;
        }
        return new FetchException(Reason.BAD_URL, String.valueOf(e.getMessage()));
    }

    private static FetchException network(IOException e) {
        if (e instanceof FetchException) return (FetchException) e;
        String m = e.getMessage();
        return new FetchException(Reason.NETWORK, m != null ? m : e.getClass().getSimpleName());
    }

    private static Info parseInfo(byte[] body) throws FetchException {
        try {
            JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
            // The revision may be a number or a string; the service quotes it as ETag either way.
            String revision = o.isNull("revision") ? "" : String.valueOf(o.get("revision"));
            String gpxUrl = o.optString("gpx_url", "");
            if (revision.isEmpty() || gpxUrl.isEmpty()) {
                throw new FetchException(Reason.BAD_RESPONSE, "revision or gpx_url missing");
            }
            return new Info(revision, o.optString("name", "").trim(),
                    o.optString("sha256", "").trim().toLowerCase(Locale.ROOT),
                    o.optLong("size_bytes", 0), gpxUrl);
        } catch (JSONException e) {
            throw new FetchException(Reason.BAD_RESPONSE, e.getMessage() != null ? e.getMessage() : "invalid JSON");
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) throw new FetchException(Reason.TOO_LARGE, "more than " + limit + " bytes");
            }
            return out.toByteArray();
        }
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every Java platform has SHA-256
        }
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
