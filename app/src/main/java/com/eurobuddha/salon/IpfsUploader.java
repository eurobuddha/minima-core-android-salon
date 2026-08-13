package com.eurobuddha.salon;

import java.net.HttpURLConnection;
import java.util.List;

/** Self-hosted kubo transport — /api/v0/add, hand-rolled multipart. A
 *  url-mode collection is ONE add of N parts sharing a "dir/" prefix so the
 *  whole set gets a single directory CID (base = gateway/ipfs/CID/).
 *  Content-addressed: overwriting is impossible by construction. */
final class IpfsUploader implements Hosting.Uploader, Hosting.DirUploader {

    private static final String DIR = "dir";

    private final Hosting.Profile profile;

    IpfsUploader(Hosting.Profile p) { this.profile = p; }

    private String addUrl() {
        boolean pin = profile.cfg().optBoolean("pin", true);
        return cleanApiUrl()
                + "/api/v0/add?pin=" + pin + "&cid-version=1&progress=false";
    }

    /** The API URL with any {@code user:pass@} userinfo stripped — Android's HTTP
     *  stack ignores userinfo for auth, so we must connect to the plain host and
     *  carry the credentials in an Authorization header instead. */
    private String cleanApiUrl() {
        String u = Hosting.trimSlash(profile.cfgStr("apiUrl"));
        try {
            java.net.URI uri = new java.net.URI(u);
            if (uri.getUserInfo() != null) {
                java.net.URI clean = new java.net.URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                        uri.getPath(), uri.getQuery(), uri.getFragment());
                return Hosting.trimSlash(clean.toString());
            }
        } catch (Exception ignore) {}
        return u;
    }

    /** HTTP Basic header from explicit user/password config fields, or (fallback)
     *  from {@code user:pass@} embedded in the apiUrl. Null when no credentials. */
    private String basicAuthHeader() {
        String user = profile.cfg().optString("user", "");
        String pass = Crypt.decrypt(profile.cfg().optString("password", ""));
        if (user.isEmpty()) {
            try {
                String ui = new java.net.URI(Hosting.trimSlash(profile.cfgStr("apiUrl"))).getUserInfo();
                if (ui != null) {
                    int c = ui.indexOf(':');
                    if (c >= 0) { user = ui.substring(0, c); pass = ui.substring(c + 1); }
                    else { user = ui; pass = ""; }
                }
            } catch (Exception ignore) {}
        }
        if (user.isEmpty()) return null;
        byte[] raw = (user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return "Basic " + android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
    }

    @Override public String putFile(byte[] bytes, String relPath, String mime) throws Hosting.HostingException {
        String name = relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
        String cid = add(new Hosting.Entry[]{ new Hosting.Entry(bytes, name, mime) }, "");
        if (cid.isEmpty()) throw new Hosting.HostingException("kubo add returned no CID");
        return Hosting.publicUrl(profile, cid, false);
    }

    @Override public String putDirectory(List<Hosting.Entry> files, Hosting.ProgressCb cb) throws Hosting.HostingException {
        if (cb != null) cb.onProgress(0, files.size(), "adding directory to IPFS");
        Hosting.Entry[] parts = new Hosting.Entry[files.size()];
        for (int i = 0; i < files.size(); i++) {
            Hosting.Entry e = files.get(i);
            String name = e.relPath.contains("/") ? e.relPath.substring(e.relPath.lastIndexOf('/') + 1) : e.relPath;
            parts[i] = new Hosting.Entry(e.bytes, DIR + "/" + name, e.mime);
        }
        String cid = add(parts, DIR);
        if (cid.isEmpty()) throw new Hosting.HostingException("kubo add returned no directory CID");
        if (cb != null) cb.onProgress(files.size(), files.size(), "pinned " + cid);
        return Hosting.publicUrl(profile, cid, true);
    }

    @Override public boolean exists(String relPath) {
        return false;   // content-addressed — same bytes, same CID, no conflict
    }

    private String add(Hosting.Entry[] parts, String dirName) throws Hosting.HostingException {
        String boundary = "----atelier" + System.currentTimeMillis();
        try {
            HttpURLConnection con = Hosting.open(addUrl(), "POST");
            con.setDoOutput(true);
            con.setChunkedStreamingMode(0);
            String auth = basicAuthHeader();
            if (auth != null) con.setRequestProperty("Authorization", auth);
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            try (java.io.OutputStream out = con.getOutputStream()) {
                for (Hosting.Entry e : parts) {
                    out.write(("--" + boundary + "\r\n"
                            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + e.relPath + "\"\r\n"
                            + "Content-Type: " + (e.mime == null ? "application/octet-stream" : e.mime) + "\r\n\r\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.write(e.bytes);
                    out.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                out.write(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            String body = Hosting.readBody(con);
            con.disconnect();
            if (code < 200 || code >= 300) {
                throw new Hosting.HostingException("kubo add failed (HTTP " + code + ")"
                        + (body.isEmpty() ? "" : " — " + body.substring(0, Math.min(120, body.length()))));
            }
            return Hosting.parseKuboAddResponse(body, dirName);
        } catch (Hosting.HostingException he) {
            throw he;
        } catch (Exception e) {
            throw new Hosting.HostingException("kubo not reachable at " + cleanApiUrl()
                    + " — " + e.getClass().getSimpleName());
        }
    }
}
