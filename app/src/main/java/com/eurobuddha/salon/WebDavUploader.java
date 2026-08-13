package com.eurobuddha.salon;

import android.util.Base64;

import java.net.HttpURLConnection;

/** WebDAV / plain HTTP-PUT transport. Write side = endpoint, read side =
 *  urlPrefix. MKCOL creates directories (405 = already exists, fine). */
final class WebDavUploader implements Hosting.Uploader {

    private final Hosting.Profile profile;

    WebDavUploader(Hosting.Profile p) { this.profile = p; }

    private void auth(HttpURLConnection con) {
        String user = profile.cfgStr("user");
        if (user.isEmpty()) return;
        String cred = user + ":" + profile.secret("password");
        con.setRequestProperty("Authorization", "Basic "
                + Base64.encodeToString(cred.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    @Override public String putFile(byte[] bytes, String relPath, String mime) throws Hosting.HostingException {
        String endpoint = Hosting.trimSlash(profile.cfgStr("endpoint"));
        try {
            // ensure intermediate collections exist
            String[] parts = relPath.split("/");
            StringBuilder dir = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                dir.append(parts[i]).append("/");
                HttpURLConnection mk = Hosting.open(endpoint + "/" + dir, "MKCOL");
                auth(mk);
                mk.getResponseCode();   // 201 created / 405 exists — both fine
                mk.disconnect();
            }
            HttpURLConnection con = Hosting.open(endpoint + "/" + relPath, "PUT");
            auth(con);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", mime == null ? "application/octet-stream" : mime);
            con.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream out = con.getOutputStream()) { out.write(bytes); }
            int code = con.getResponseCode();
            con.disconnect();
            if (code < 200 || code >= 300) {
                throw new Hosting.HostingException("WebDAV PUT failed (HTTP " + code + ") at " + relPath);
            }
            return Hosting.publicUrl(profile, relPath, false);
        } catch (Hosting.HostingException he) {
            throw he;
        } catch (Exception e) {
            throw new Hosting.HostingException("WebDAV upload failed at " + relPath + ": " + e.getClass().getSimpleName());
        }
    }

    @Override public boolean exists(String relPath) throws Hosting.HostingException {
        try {
            HttpURLConnection con = Hosting.open(Hosting.trimSlash(profile.cfgStr("endpoint")) + "/" + relPath, "HEAD");
            auth(con);
            int code = con.getResponseCode();
            con.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            throw new Hosting.HostingException("WebDAV reachability check failed: " + e.getClass().getSimpleName());
        }
    }
}
