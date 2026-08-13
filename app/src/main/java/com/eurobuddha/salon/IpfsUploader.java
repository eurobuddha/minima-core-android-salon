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
        return Hosting.trimSlash(profile.cfgStr("apiUrl"))
                + "/api/v0/add?pin=" + pin + "&cid-version=1&progress=false";
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
            throw new Hosting.HostingException("kubo not reachable at " + profile.cfgStr("apiUrl")
                    + " — " + e.getClass().getSimpleName());
        }
    }
}
