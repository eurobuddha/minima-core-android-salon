package com.eurobuddha.salon;

import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.List;

/** Pinata pinning-service transport — the serverless path: paste a free-tier
 *  JWT, art pins to IPFS, links serve from a public gateway. Directory pins
 *  use N parts sharing a filepath prefix; IpfsHash is the directory CID. */
final class PinataUploader implements Hosting.Uploader, Hosting.DirUploader {

    private static final String API = "https://api.pinata.cloud/pinning/pinFileToIPFS";
    private static final String DIR = "dir";

    private final Hosting.Profile profile;

    PinataUploader(Hosting.Profile p) { this.profile = p; }

    @Override public String putFile(byte[] bytes, String relPath, String mime) throws Hosting.HostingException {
        String name = relPath.contains("/") ? relPath.substring(relPath.lastIndexOf('/') + 1) : relPath;
        String cid = pin(new Hosting.Entry[]{ new Hosting.Entry(bytes, name, mime) }, false);
        return Hosting.publicUrl(profile, cid, false);
    }

    @Override public String putDirectory(List<Hosting.Entry> files, Hosting.ProgressCb cb) throws Hosting.HostingException {
        if (cb != null) cb.onProgress(0, files.size(), "pinning directory to Pinata");
        Hosting.Entry[] parts = new Hosting.Entry[files.size()];
        for (int i = 0; i < files.size(); i++) {
            Hosting.Entry e = files.get(i);
            String name = e.relPath.contains("/") ? e.relPath.substring(e.relPath.lastIndexOf('/') + 1) : e.relPath;
            parts[i] = new Hosting.Entry(e.bytes, DIR + "/" + name, e.mime);
        }
        String cid = pin(parts, true);
        if (cb != null) cb.onProgress(files.size(), files.size(), "pinned " + cid);
        return Hosting.publicUrl(profile, cid, true);
    }

    @Override public boolean exists(String relPath) {
        return false;   // content-addressed
    }

    private String pin(Hosting.Entry[] parts, boolean dir) throws Hosting.HostingException {
        String jwt = profile.secret("jwt");
        if (jwt.isEmpty()) throw new Hosting.HostingException("No Pinata JWT in the profile");
        String boundary = "----atelier" + System.currentTimeMillis();
        try {
            HttpURLConnection con = Hosting.open(API, "POST");
            con.setDoOutput(true);
            con.setChunkedStreamingMode(0);
            con.setRequestProperty("Authorization", "Bearer " + jwt);
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
                throw new Hosting.HostingException("Pinata pin failed (HTTP " + code + ")"
                        + (code == 401 ? " — check the JWT" : ""));
            }
            String cid = new JSONObject(body).optString("IpfsHash");
            if (cid.isEmpty()) throw new Hosting.HostingException("Pinata returned no CID");
            return cid;
        } catch (Hosting.HostingException he) {
            throw he;
        } catch (Exception e) {
            throw new Hosting.HostingException("Pinata not reachable — " + e.getClass().getSimpleName());
        }
    }
}
