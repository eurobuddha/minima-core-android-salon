package com.eurobuddha.salon;

import java.net.HttpURLConnection;

/** GitHub contents-API transport. Serves via raw.githubusercontent.com
 *  (~5 min cache) or a GitHub Pages prefix. Never sends a sha, so GitHub
 *  itself refuses overwrites with 422 — surfaced honestly. */
final class GithubUploader implements Hosting.Uploader {

    private final Hosting.Profile profile;

    GithubUploader(Hosting.Profile p) { this.profile = p; }

    private String apiUrl(String path) {
        return "https://api.github.com/repos/" + profile.cfgStr("owner") + "/"
                + profile.cfgStr("repo") + "/contents/" + path;
    }

    private void headers(HttpURLConnection con) {
        con.setRequestProperty("Authorization", "Bearer " + profile.secret("token"));
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
    }

    @Override public String putFile(byte[] bytes, String relPath, String mime) throws Hosting.HostingException {
        try {
            HttpURLConnection con = Hosting.open(apiUrl(relPath), "PUT");
            headers(con);
            con.setDoOutput(true);
            byte[] body = Hosting.githubContentsBody(bytes, "atelier: " + relPath,
                    profile.cfgStr("branch")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            con.setRequestProperty("Content-Type", "application/json");
            con.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream out = con.getOutputStream()) { out.write(body); }
            int code = con.getResponseCode();
            con.disconnect();
            if (code == 422) {
                throw new Hosting.HostingException("GitHub refused: " + relPath
                        + " already exists (files are never overwritten)");
            }
            if (code == 401 || code == 403) {
                throw new Hosting.HostingException("GitHub auth failed (HTTP " + code
                        + ") — check the token and its repo permissions");
            }
            if (code < 200 || code >= 300) {
                throw new Hosting.HostingException("GitHub upload failed (HTTP " + code + ") at " + relPath);
            }
            return Hosting.publicUrl(profile, relPath, false);
        } catch (Hosting.HostingException he) {
            throw he;
        } catch (Exception e) {
            throw new Hosting.HostingException("GitHub not reachable — " + e.getClass().getSimpleName());
        }
    }

    @Override public boolean exists(String relPath) throws Hosting.HostingException {
        try {
            HttpURLConnection con = Hosting.open(apiUrl(relPath)
                    + "?ref=" + (profile.cfgStr("branch").isEmpty() ? "main" : profile.cfgStr("branch")), "GET");
            headers(con);
            int code = con.getResponseCode();
            con.disconnect();
            if (code == 200) return true;
            if (code == 404) return false;
            throw new Hosting.HostingException("GitHub check failed (HTTP " + code + ") for " + relPath);
        } catch (Hosting.HostingException he) {
            throw he;
        } catch (Exception e) {
            throw new Hosting.HostingException("GitHub not reachable — " + e.getClass().getSimpleName());
        }
    }
}
