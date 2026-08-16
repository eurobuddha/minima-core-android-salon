package com.eurobuddha.salon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Hosting — upload chosen photos to the USER'S OWN storage and hand back the
 *  public URLs the mint seals on-chain. One profile schema, many backends
 *  (SFTP / WebDAV / kubo IPFS / Pinata / GitHub / Maxima mesh / Blossom),
 *  one law: what the mint
 *  writes must be a URL that provably serves, and no remote file is ever
 *  overwritten. Pure helpers are static and mirrored byte-for-byte in the
 *  MiniDapp's hosting.js — parity pinned by shared fixtures under
 *  test/fixtures/hosting/. */
final class Hosting {

    private Hosting() {}

    /* ==================== profile ==================== */

    static final String TYPE_SFTP = "sftp";
    static final String TYPE_WEBDAV = "webdav";
    static final String TYPE_KUBO = "kubo";
    static final String TYPE_PINATA = "pinata";
    static final String TYPE_GITHUB = "github";
    static final String TYPE_RELAY = "relay";
    static final String TYPE_MAXIMA = "maxima";   // self-hosted mesh (you are the server)
    static final String TYPE_BLOSSOM = "blossom"; // nostr blob storage (content-addressed)

    static class Profile {
        JSONObject j;

        Profile(JSONObject j) { this.j = j; }

        static Profile fresh(String type) {
            JSONObject o = new JSONObject();
            put(o, "v", 1);
            put(o, "id", "hp_" + System.currentTimeMillis());
            put(o, "name", "");
            put(o, "type", type);
            put(o, "def", false);
            put(o, "pathTemplate", "atelier/{collection}/{name}-{ts}{ext}");
            put(o, "dirTemplate", "atelier/{collection}-{ts}");
            put(o, type, new JSONObject());
            return new Profile(o);
        }

        String id() { return j.optString("id"); }
        String name() { return j.optString("name"); }
        String type() { return j.optString("type"); }
        boolean isDefault() { return j.optBoolean("def"); }
        JSONObject cfg() {
            JSONObject c = j.optJSONObject(type());
            return c == null ? new JSONObject() : c;
        }
        String cfgStr(String key) { return cfg().optString(key, ""); }
        String secret(String key) { return Crypt.decrypt(cfgStr(key)); }
        String pathTemplate() { return j.optString("pathTemplate", "atelier/{collection}/{name}-{ts}{ext}"); }
        String dirTemplate() { return j.optString("dirTemplate", "atelier/{collection}-{ts}"); }

        /** Hosts the user explicitly configured — allowed even on RFC1918. */
        java.util.HashSet<String> ownHosts() {
            java.util.HashSet<String> hs = new java.util.HashSet<>();
            for (String k : new String[]{ "host", "endpoint", "apiUrl", "gateway", "urlPrefix", "pagesPrefix" }) {
                String v = cfgStr(k);
                if (v.isEmpty()) continue;
                try {
                    hs.add(v.contains("://") ? new URL(v).getHost() : v);
                } catch (Exception ignored) { hs.add(v); }
            }
            return hs;
        }

        @Override public String toString() {   // redacted — never leaks secrets
            return "Profile{" + name() + " · " + type() + "}";
        }
    }

    /* ==================== pure helpers (parity with hosting.js) ============ */

    /** Slug: lowercase, [a-z0-9-], collapsed dashes, max 40, never empty. */
    static String slug(String s) {
        if (s == null) s = "";
        String out = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (out.length() > 40) out = out.substring(0, 40).replaceAll("-$", "");
        return out.isEmpty() ? "x" : out;
    }

    /** {collection} {name} {idx} {ext} {ts} {rand} — idx UNPADDED (the chain
     *  contract is base + i + ext with bare ints). Unknown tokens are left
     *  verbatim so mistakes are visible, not silent. */
    static String fillTemplate(String tpl, Map<String, String> tokens) {
        String out = tpl == null ? "" : tpl;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    static String ts36() {
        return Long.toString(System.currentTimeMillis() / 1000, 36);
    }

    static String rand4() {
        return Integer.toHexString((int) (Math.random() * 0xFFFF) | 0x10000).substring(1);
    }

    /** Public URL for an uploaded path (or CID for the IPFS backends).
     *  isDir=true returns a base ending in "/" ready for base+idx+ext. */
    static String publicUrl(Profile p, String relPathOrCid, boolean isDir) {
        String type = p.type();
        // Blossom is content-addressed: putFile already returns the absolute
        // descriptor URL, so there is nothing to derive here.
        if (TYPE_BLOSSOM.equals(type)) return relPathOrCid;
        if (TYPE_KUBO.equals(type) || TYPE_PINATA.equals(type)) {
            String gw = trimSlash(p.cfgStr("gateway"));
            if (gw.isEmpty() && TYPE_PINATA.equals(type)) gw = "https://gateway.pinata.cloud";
            return gw + "/ipfs/" + relPathOrCid + (isDir ? "/" : "");
        }
        if (TYPE_GITHUB.equals(type)) {
            if ("pages".equals(p.cfgStr("serve"))) {
                return trimSlash(p.cfgStr("pagesPrefix")) + "/" + relPathOrCid + (isDir ? "/" : "");
            }
            return "https://raw.githubusercontent.com/" + p.cfgStr("owner") + "/" + p.cfgStr("repo")
                    + "/" + (p.cfgStr("branch").isEmpty() ? "main" : p.cfgStr("branch"))
                    + "/" + relPathOrCid + (isDir ? "/" : "");
        }
        // sftp/webdav: read-side prefix + path relative to the write root
        return trimSlash(p.cfgStr("urlPrefix")) + "/" + relPathOrCid + (isDir ? "/" : "");
    }

    static String trimSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** kubo /api/v0/add NDJSON: one line per entry; the DIRECTORY entry's Name
     *  equals the shared prefix. Returns its Hash, or "" when absent. */
    static String parseKuboAddResponse(String ndjson, String dirName) {
        if (ndjson == null) return "";
        String last = "";
        for (String line : ndjson.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                JSONObject o = new JSONObject(line);
                String name = o.optString("Name");
                if (name.equals(dirName)) return o.optString("Hash");
                last = o.optString("Hash", last);
            } catch (Exception ignored) { }
        }
        return dirName == null || dirName.isEmpty() ? last : "";
    }

    /** GitHub contents-API body. Never carries a sha — that is the
     *  overwrite-refusal: GitHub 422s when the path already exists. */
    static String githubContentsBody(byte[] bytes, String message, String branch) {
        JSONObject o = new JSONObject();
        put(o, "message", message == null || message.isEmpty() ? "atelier upload" : message);
        put(o, "content", java.util.Base64.getEncoder().encodeToString(bytes));
        put(o, "branch", branch == null || branch.isEmpty() ? "main" : branch);
        return o.toString();
    }

    static String githubRawUrl(String owner, String repo, String branch, String path) {
        return "https://raw.githubusercontent.com/" + owner + "/" + repo + "/"
                + (branch == null || branch.isEmpty() ? "main" : branch) + "/" + path;
    }

    /* ==================== uploader surface ==================== */

    static class HostingException extends Exception {
        HostingException(String msg) { super(msg); }
    }

    interface ProgressCb { void onProgress(int done, int total, String msg); }

    static class Entry {
        final byte[] bytes; final String relPath; final String mime;
        Entry(byte[] bytes, String relPath, String mime) {
            this.bytes = bytes; this.relPath = relPath; this.mime = mime;
        }
    }

    /** Single-file transport. putFile returns the PUBLIC URL. AutoCloseable so a stateful
     *  transport (SFTP holds an SSH session) is released via try-with-resources; the
     *  stateless HTTP uploaders inherit the no-op default. */
    interface Uploader extends AutoCloseable {
        String putFile(byte[] bytes, String relPath, String mime) throws HostingException;
        boolean exists(String relPath) throws HostingException;
        @Override default void close() {}
    }

    /** Directory transport (IPFS backends): one call, one CID, base URL back. */
    interface DirUploader {
        String putDirectory(List<Entry> files, ProgressCb cb) throws HostingException;
    }

    static Uploader forProfile(Profile p) throws HostingException {
        switch (p.type()) {
            case TYPE_SFTP:   return new SftpUploader(p);
            case TYPE_WEBDAV: return new WebDavUploader(p);
            case TYPE_KUBO:   return new IpfsUploader(p);
            case TYPE_PINATA: return new PinataUploader(p);
            case TYPE_GITHUB: return new GithubUploader(p);
            // RETIRED (MaxLite sunset) — uncomment to restore encrypted-relay publishing:
            // case TYPE_RELAY:  return new RelayUploader(p);
            case TYPE_RELAY:  throw new HostingException("Encrypted relay hosting is retired — choose Maxima mesh or a server host (SFTP/IPFS/GitHub).");
            case TYPE_MAXIMA: return new MaximaHostUploader(p);
            case TYPE_BLOSSOM: return new BlossomUploader(p);
            default: throw new HostingException("Unknown destination type: " + p.type());
        }
    }

    /** Sequential N-file upload for backends without directory semantics.
     *  Existence-checks EVERY target first (a renamed plate mid-batch would
     *  break the base+idx+ext contract), then uploads in order. Returns the
     *  common base URL (dir part) — caller derives base+idx+ext. */
    static String putSequence(Uploader u, Profile p, String dirRel, List<Entry> files, ProgressCb cb)
            throws HostingException {
        for (Entry e : files) {
            if (u.exists(e.relPath)) {
                throw new HostingException("Remote file already exists: " + e.relPath
                        + " — nothing was uploaded (files are never overwritten)");
            }
        }
        String lastUrl = "";
        int i = 0;
        for (Entry e : files) {
            if (cb != null) cb.onProgress(i, files.size(), e.relPath);
            lastUrl = u.putFile(e.bytes, e.relPath, e.mime);
            i++;
        }
        if (cb != null) cb.onProgress(files.size(), files.size(), "done");
        // base = last URL minus its filename
        int cut = lastUrl.lastIndexOf('/');
        return cut < 0 ? lastUrl : lastUrl.substring(0, cut + 1);
    }

    /* ==================== verification ==================== */

    /** The URL the mint will seal must serve — Range-GET so plates aren't
     *  pulled whole. SSRF policy: the standard blocklist applies EXCEPT for
     *  hosts the user explicitly configured in the profile (a LAN kubo
     *  gateway is legitimate for its owner). */
    static void verifyUrl(String url, Profile p) throws HostingException {
        // A relay1:/mx1: ref isn.t an http URL — verify by fetching+decrypting it back.
        if (RelayResolver.isMediaRef(url)) {
            try { RelayResolver.resolveBytes(url); return; }
            catch (Exception e) { throw new HostingException("relay content not readable back: " + e.getMessage()); }
        }
        int attempts = p != null && TYPE_GITHUB.equals(p.type()) ? 3 : 1;
        HostingException last = null;
        for (int a = 0; a < attempts; a++) {
            try {
                verifyOnce(url, p);
                return;
            } catch (HostingException e) {
                last = e;
                if (a < attempts - 1) {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
                }
            }
        }
        throw last;
    }

    private static void verifyOnce(String url, Profile p) throws HostingException {
        try {
            URL u = new URL(url);
            boolean own = p != null && p.ownHosts().contains(u.getHost());
            if (!own && ImageLoader.isBlockedHost(u.getHost())) {
                throw new HostingException("URL host is not publicly reachable: " + u.getHost());
            }
            HttpURLConnection con = (HttpURLConnection) u.openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);
            con.setInstanceFollowRedirects(false);   // don't follow a 3xx to an unchecked (possibly local) host; a redirect still counts as "serves"
            con.setRequestProperty("Range", "bytes=0-1");
            int code = con.getResponseCode();
            con.disconnect();
            if (code < 200 || code >= 400) {
                throw new HostingException("URL does not serve (HTTP " + code + "): " + url);
            }
        } catch (HostingException he) {
            throw he;
        } catch (Exception e) {
            throw new HostingException("URL not reachable: " + url + " — " + e.getClass().getSimpleName());
        }
    }

    /* ==================== HTTP plumbing shared by uploaders ============== */

    static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(30000);
        con.setRequestMethod(method);
        return con;
    }

    static String readBody(HttpURLConnection con) {
        try (java.io.InputStream in = con.getResponseCode() < 400
                ? con.getInputStream() : con.getErrorStream()) {
            if (in == null) return "";
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) { }
    }

    static void putArr(JSONArray a, Object v) {
        a.put(v);
    }
}
