package com.eurobuddha.salon;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Synchronous client for the relay's content-addressed blob store
 * (POST /blob -> {chunkid}, GET /blob/&lt;chunkid&gt; -> raw ciphertext). The relay
 * only ever holds opaque ciphertext. Runs inline on the caller's thread — callers
 * are always on a background executor — so no latch/main-thread bridge is needed.
 * (Same wire shape as support/freezepeach/.../comms/MailboxHttp, but blocking.)
 */
final class RelayBlob {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;

    static String trimSlash(String s) {
        String b = s == null ? "" : s.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    /** POST a ciphertext chunk; returns its sha256 chunkid. */
    static String putChunk(String relayBase, byte[] body) throws Exception {
        byte[] resp = doRaw(trimSlash(relayBase) + "/blob", "POST", body);
        JSONObject j = new JSONObject(new String(resp, StandardCharsets.UTF_8));
        if (!j.optBoolean("ok", false)) throw new Exception("blob store rejected chunk");
        String cid = j.optString("chunkid", "");
        if (cid.isEmpty()) throw new Exception("blob store returned no chunkid");
        return cid;
    }

    /** GET a ciphertext chunk by id (also refreshes its 7-day TTL server-side). */
    static byte[] getChunk(String relayBase, String chunkid) throws Exception {
        return doRaw(trimSlash(relayBase) + "/blob/" + chunkid, "GET", null);
    }

    /** A best-effort GET purely to refresh a chunk's TTL. */
    static boolean touch(String relayBase, String chunkid) {
        try { getChunk(relayBase, chunkid); return true; } catch (Exception e) { return false; }
    }

    private static byte[] doRaw(String url, String method, byte[] body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = conn.getOutputStream()) { os.write(body); }
            }
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            byte[] data = readAll(in);
            if (code < 200 || code >= 400)
                throw new Exception("HTTP " + code + (data.length > 0 ? ": " + new String(data, StandardCharsets.UTF_8) : ""));
            return data;
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n, total = 0;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > MAX_RESPONSE) throw new Exception("response too large");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private RelayBlob() {}
}
