package com.eurobuddha.salon;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.security.MessageDigest;

/** Blossom (nostr blob storage) transport — https://github.com/hzrd149/blossom.
 *  PUT /upload with a signed kind-24242 auth event; the blob then lives at
 *  <server>/<sha256>. Content addressing replaces the path contract: relPath
 *  only feeds the human-readable event content, identical bytes re-PUT
 *  idempotently, and nothing can ever be overwritten. */
final class BlossomUploader implements Hosting.Uploader {

    // Live-tested 2026-08-16: primal accepts any content type (profile.json AND media);
    // blossom.band / blossom.nostr.build are media-only on the free tier (415 on JSON),
    // so they cannot host the profile itself.
    static final String DEFAULT_SERVER = "https://blossom.primal.net";

    private final Hosting.Profile profile;

    BlossomUploader(Hosting.Profile p) { this.profile = p; }

    @Override public String putFile(byte[] bytes, String relPath, String mime) throws Hosting.HostingException {
        String endpoint = Hosting.trimSlash(profile.cfgStr("endpoint"));
        if (endpoint.isEmpty()) endpoint = DEFAULT_SERVER;
        try {
            String sha = Hex.to(MessageDigest.getInstance("SHA-256").digest(bytes));
            long now = System.currentTimeMillis() / 1000L;
            String base = relPath == null ? "" : relPath.substring(relPath.lastIndexOf('/') + 1);
            // created_at backdated 60 s, expiration 10 min out — a modestly wrong
            // device clock still lands inside the server's validity window.
            String event = NostrEvent.signedJson(NostrKeys.secKey(), 24242, new String[][]{
                    { "t", "upload" }, { "x", sha }, { "expiration", String.valueOf(now + 600) } },
                    "Upload " + base, now - 60);
            HttpURLConnection con = Hosting.open(endpoint + "/upload", "PUT");
            // HttpURLConnection won't replay a PUT body across a 3xx and drops the
            // Authorization header — surface the redirect instead of following it.
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("Authorization", NostrEvent.authHeader(event));
            con.setRequestProperty("Content-Type", mime == null ? "application/octet-stream" : mime);
            con.setRequestProperty("X-SHA-256", sha);
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream out = con.getOutputStream()) { out.write(bytes); }
            int code = con.getResponseCode();
            if (code == 409) {
                // Content-addressed conflict = the identical blob is already there
                // (nostr.download does this instead of a 200). Confirm with a HEAD
                // before trusting it; a transient HEAD failure keeps the old trust.
                con.disconnect();
                String url = endpoint + "/" + sha + extFor(mime);
                try {
                    HttpURLConnection head = Hosting.open(endpoint + "/" + sha, "HEAD");
                    int hc = head.getResponseCode();
                    head.disconnect();
                    if (hc >= 400) throw new Hosting.HostingException(
                            "Blossom server answered 409 (already exists) for " + relPath
                            + " but HEAD " + endpoint + "/" + sha + " returned HTTP " + hc + " — try another server");
                } catch (Hosting.HostingException he) {
                    throw he;
                } catch (Exception ignored) { }
                return url;
            }
            if (code == 200 || code == 201) {
                String body = Hosting.readBody(con);
                con.disconnect();
                JSONObject d = body.trim().startsWith("{") ? new JSONObject(body) : new JSONObject();
                String got = d.optString("sha256", "");
                if (!got.isEmpty() && !got.equalsIgnoreCase(sha)) {
                    throw new Hosting.HostingException("Blossom server returned a different hash for "
                            + relPath + " — sent " + sha + ", got " + got);
                }
                String url = d.optString("url", "");
                return url.isEmpty() ? endpoint + "/" + sha + extFor(mime) : url;
            }
            String reason = con.getHeaderField("X-Reason");
            String location = code >= 300 && code < 400 ? con.getHeaderField("Location") : null;
            con.disconnect();
            StringBuilder msg = new StringBuilder("Blossom upload failed (HTTP ").append(code).append(")");
            if (reason != null && !reason.isEmpty()) msg.append(" — ").append(reason);
            if (location != null && !location.isEmpty()) msg.append(" — server redirected to ").append(location);
            msg.append(" at ").append(relPath);
            if (code == 413) msg.append(" — file exceeds this server's size limit. Host large video/audio on SFTP/IPFS/GitHub.");
            if (code == 415) msg.append(" — this server doesn't accept this file type (media-only free tiers like blossom.band can't host the profile itself — use " + DEFAULT_SERVER + ").");
            if (code == 401) msg.append(" — auth rejected; check the device clock is set correctly.");
            throw new Hosting.HostingException(msg.toString());
        } catch (Hosting.HostingException he) {
            throw he;
        } catch (Exception e) {
            throw new Hosting.HostingException("Blossom upload failed at " + relPath + ": " + e.getClass().getSimpleName());
        }
    }

    /** Content-addressed store: same bytes → same URL, so "exists" as an
     *  overwrite guard is meaningless here (mirrors PinataUploader). */
    @Override public boolean exists(String relPath) { return false; }

    /** Servers key blobs by hash; the extension is cosmetic but required in
     *  descriptor URLs (BUD-02) and helps browsers render the blob. */
    static String extFor(String mime) {
        if (mime == null) return "";
        switch (mime) {
            case "image/jpeg":      return ".jpg";
            case "image/png":       return ".png";
            case "image/webp":      return ".webp";
            case "image/gif":       return ".gif";
            case "image/svg+xml":   return ".svg";
            case "video/mp4":       return ".mp4";
            case "audio/mpeg":      return ".mp3";
            case "application/json":return ".json";
            case "text/html":       return ".html";
            case "text/plain":      return ".txt";
            default:                return "";
        }
    }
}
