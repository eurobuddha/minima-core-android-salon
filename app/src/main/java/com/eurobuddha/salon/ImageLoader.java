package com.eurobuddha.salon;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class ImageLoader {
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private static final int THUMB_PX = 420;
    private static final int FULL_PX = 1800;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final java.util.concurrent.ExecutorService EXEC =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    private ImageLoader() {}

    public static void loadOver(final android.app.Activity act, final String url, final ImageView iv) {
        loadOver(act, url, iv, THUMB_PX);
    }

    public static void loadFull(final android.app.Activity act, final String url, final ImageView iv) {
        loadOver(act, url, iv, FULL_PX);
    }

    private static void loadOver(final android.app.Activity act, final String url, final ImageView iv, final int reqPx) {
        iv.setTag(url);
        if (url == null || url.isEmpty()) return;
        // data-URI "urls" are 12KB strings — digest the cache key instead of
        // hashing/equals-ing the whole payload on every lookup
        String key = reqPx + "|" + url.length() + ":" + url.hashCode();
        Bitmap cached = CACHE.get(key);
        if (cached != null) { iv.setImageBitmap(cached); return; }
        EXEC.execute(() -> {
            final Bitmap b = decode(act, url, reqPx);
            if (b == null) return;
            CACHE.put(key, b);
            act.runOnUiThread(() -> {
                if (act.isDestroyed()) return;
                if (url.equals(iv.getTag())) iv.setImageBitmap(b);
            });
        });
    }

    static Bitmap decode(android.content.Context ctx, String url, int reqPx) {
        try {
            byte[] bytes = url.startsWith("data:") ? dataUriBytes(url) : fetchCached(ctx, url);
            if (bytes == null || bytes.length == 0) return null;
            if (looksLikeSvg(bytes, url)) return renderSvg(bytes, reqPx);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqPx);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opt);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean looksLikeSvg(byte[] bytes, String url) {
        if (url != null && url.toLowerCase().contains(".svg")) return true;
        int n = Math.min(bytes.length, 300);
        String head = new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim().toLowerCase();
        return head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg")) || head.contains("<svg");
    }

    private static Bitmap renderSvg(byte[] bytes, int reqPx) {
        try {
            com.caverock.androidsvg.SVG svg =
                    com.caverock.androidsvg.SVG.getFromString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            float dw = svg.getDocumentWidth(), dh = svg.getDocumentHeight();
            int w = reqPx, h = reqPx;
            if (dw > 0 && dh > 0) {
                if (dw >= dh) { w = reqPx; h = Math.max(1, Math.round(reqPx * dh / dw)); }
                else { h = reqPx; w = Math.max(1, Math.round(reqPx * dw / dh)); }
            }
            svg.setDocumentWidth(w);
            svg.setDocumentHeight(h);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            svg.renderToCanvas(new android.graphics.Canvas(bmp));
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int sampleSize(int w, int h, int reqPx) {
        int s = 1;
        int max = Math.max(w, h);
        while (max / s > reqPx) s <<= 1;
        return Math.max(1, s);
    }

    /* remote icons persist to disk so a wallet full of hosted-image NFTs
     * still shows every once-seen icon offline (the identicon-fallback
     * report: airplane mode turned the whole Singles wall into placeholders) */
    private static final int DISK_MAX = 300;

    private static java.io.File diskFile(android.content.Context ctx, String url) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            java.io.File dir = new java.io.File(ctx.getCacheDir(), "nfticons");
            if (!dir.exists() && !dir.mkdirs()) return null;
            return new java.io.File(dir, sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] fetchCached(android.content.Context ctx, String url) {
        java.io.File f = ctx == null ? null : diskFile(ctx, url);
        byte[] bytes = null;
        try { bytes = fetch(url); } catch (Exception ignored) {}
        if (bytes != null && bytes.length > 0) {
            if (f != null) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    out.write(bytes);
                    trimDisk(f.getParentFile());
                } catch (Exception ignored) {}
            }
            return bytes;
        }
        if (f != null && f.exists()) {                 // offline: serve the last-seen bytes
            try { return java.nio.file.Files.readAllBytes(f.toPath()); }
            catch (Exception ignored) {}
        }
        return null;
    }

    private static void trimDisk(java.io.File dir) {
        java.io.File[] all = dir == null ? null : dir.listFiles();
        if (all == null || all.length <= DISK_MAX) return;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < all.length - DISK_MAX; i++) all[i].delete();
    }

    private static byte[] fetch(String url) throws Exception {
        String f = url.startsWith("ipfs://") ? "https://ipfs.io/ipfs/" + url.substring("ipfs://".length()) : url;
        URL u = new URL(f);
        if (isBlockedHost(u.getHost())) return null;
        HttpURLConnection con = (HttpURLConnection) u.openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(15000);
        con.setInstanceFollowRedirects(true);
        try (InputStream in = con.getInputStream(); java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) return null;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            con.disconnect();
        }
    }

    static boolean isBlockedHost(String host) {
        if (host == null || host.isEmpty()) return true;
        try {
            for (java.net.InetAddress a : java.net.InetAddress.getAllByName(host)) {
                if (a.isLoopbackAddress() || a.isAnyLocalAddress()
                        || a.isLinkLocalAddress() || a.isSiteLocalAddress()) {
                    return true;
                }
            }
        } catch (Exception e) {
            return true;
        }
        return false;
    }

    private static byte[] dataUriBytes(String dataUri) {
        int comma = dataUri.indexOf(',');
        if (comma < 0) return null;
        if (!dataUri.substring(0, comma).contains("base64")) return null;
        return Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
    }
}
