package com.eurobuddha.salon;

import android.app.Activity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web-validation check (matches the official Minima wallet and NFTwallet's WebValidate): a
 * StateNFT token is web-validated when the file at its {@code token.webvalidate} URL contains the
 * token's id — proving whoever set it controls that domain. Result is cached per tokenid and
 * fetched once on a worker thread. Client-side by design (no dependence on a node tokenvalidate
 * command, which some nodes don't expose).
 */
public final class WebValidate {

    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> INFLIGHT = Collections.synchronizedSet(new HashSet<>());
    private static final java.util.concurrent.ExecutorService EXEC =
            java.util.concurrent.Executors.newFixedThreadPool(2);

    private WebValidate() {}

    /** TRUE validated, FALSE not / failed, null not checked yet. */
    public static Boolean status(String tokenid) { return CACHE.get(norm(tokenid)); }

    /** Kick off a check if we haven't already; runs onDone on the UI thread when a fresh result lands. */
    public static void ensure(final Activity act, final String tokenid,
                              final String webvalidateUrl, final Runnable onDone) {
        final String k = norm(tokenid);
        if (CACHE.containsKey(k)) return;
        if (webvalidateUrl == null || webvalidateUrl.trim().isEmpty()) { CACHE.put(k, Boolean.FALSE); return; }
        if (!INFLIGHT.add(k)) return;
        EXEC.execute(() -> {
            boolean ok = fetchContains(webvalidateUrl.trim(), tokenid);
            CACHE.put(k, ok);
            INFLIGHT.remove(k);
            if (onDone != null) act.runOnUiThread(() -> { if (!act.isDestroyed()) onDone.run(); });
        });
    }

    private static boolean fetchContains(String url, String tokenid) {
        HttpURLConnection c = null;
        try {
            if (!url.startsWith("http")) return false;
            if (isBlockedHost(new URL(url).getHost())) return false;   // no loopback/LAN webvalidate targets
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Atelier");
            if (c.getResponseCode() != 200) return false;
            String body;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n; int total = 0;
                while ((n = in.read(buf)) > 0 && total < 262144) { bos.write(buf, 0, n); total += n; }  // cap 256KB
                body = bos.toString("UTF-8").toLowerCase(Locale.US);
            }
            String tid = tokenid.toLowerCase(Locale.US);
            String bare = tid.startsWith("0x") ? tid.substring(2) : tid;
            return body.contains(tid) || body.contains(bare);
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Reject loopback / private-range hosts so a validation URL can't point at the LAN. */
    private static boolean isBlockedHost(String host) {
        if (host == null || host.isEmpty()) return true;
        String h = host.toLowerCase(Locale.US);
        if (h.equals("localhost") || h.endsWith(".local")) return true;
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")
                || h.startsWith("169.254.") || h.equals("0.0.0.0") || h.equals("::1")) return true;
        if (h.startsWith("172.")) {
            try {
                int second = Integer.parseInt(h.split("\\.")[1]);
                if (second >= 16 && second <= 31) return true;    // 172.16.0.0/12
            } catch (Exception ignore) {}
        }
        return false;
    }

    private static String norm(String t) {
        if (t == null) return "";
        String s = t.trim().toLowerCase(Locale.US);
        return s.startsWith("0x") ? s.substring(2) : s;
    }
}
