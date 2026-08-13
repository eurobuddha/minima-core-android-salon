package com.eurobuddha.salon;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Base64;

import java.io.ByteArrayOutputStream;

public final class ImageTools {

    /* Budgets in base64 chars. STATE_IMG rides TWICE in a transfer txn (input
     * proof + output state) against the 64KB TxPoW cap — 16000 proven by
     * image-budget-spike.sh (stamp + double-carry transfer confirmed on-chain
     * 2026-08-05, state intact). */
    public static final int STATE_IMG_BUDGET = 16000;
    public static final int ARTIMAGE_BUDGET = 9000;
    public static final int ICON_BUDGET = 6000;

    private ImageTools() {}

    /** True when the picked content is an SVG document (by declared type,
     *  extension, or content sniff). */
    public static boolean isSvgUri(Context c, Uri uri) {
        try {
            String type = c.getContentResolver().getType(uri);
            if ("image/svg+xml".equals(type)) return true;
            String s = uri.toString().toLowerCase();
            if (s.endsWith(".svg")) return true;
            try (java.io.InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) return false;
                byte[] head = new byte[256];
                int n = in.read(head);
                if (n <= 0) return false;
                String text = new String(head, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim().toLowerCase();
                return text.startsWith("<svg") || (text.startsWith("<?xml") && text.contains("<svg"));
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read, sanitize and base64 an SVG. Returns "" when it is not a valid
     *  SVG, cannot be made inert, or exceeds the budget. */
    public static String svgBase64FromUri(Context c, Uri uri, int budget) {
        try (java.io.InputStream in = c.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (in == null) return "";
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 256 * 1024) return "";   // absurd for flat art
                bos.write(buf, 0, n);
            }
            String svg = bos.toString("UTF-8");
            String clean = SvgSanitizer.sanitize(svg);
            if (clean == null || clean.isEmpty()) return "";
            String b64 = Base64.encodeToString(clean.getBytes(java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
            return b64.length() <= budget ? b64 : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** Decode via ImageDecoder: the platform applies JPEG EXIF and HEIF
     *  orientation exactly once — no more sideways Samsung photos. */
    public static String compressUri(Context c, Uri uri, int budget) throws Exception {
        ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), uri);
        Bitmap bmp = ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(false);
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            int max = Math.max(w, h);
            if (max > 2048) {
                float s = 2048f / max;
                decoder.setTargetSize(Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
            }
        });
        if (bmp == null) return "";
        return compressBitmap(bmp, budget);
    }

    public static String recompressBase64(String b64, int budget) {
        if (b64 == null || b64.isEmpty()) return "";
        if (b64.length() <= budget) return b64;
        return rotateBase64(b64, 0, budget);
    }

    /* ---- wallet icons: ALWAYS square. Wallet tiles are square; a portrait
     * plate dropped in raw gets letterboxed into an ugly sliver. Center-crop
     * cover, like every proper token icon. ---- */

    public static String iconFromUri(Context c, Uri uri, int budget) {
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), uri);
            Bitmap bmp = ImageDecoder.decodeBitmap(src, (d, info, s) -> {
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                int max = Math.max(w, h);
                if (max > 1024) {
                    float f = 1024f / max;
                    d.setTargetSize(Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)));
                }
            });
            if (bmp == null) return "";
            return compressIconBitmap(centerSquare(bmp), budget);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Wallet icon from an already-rasterized bitmap (e.g. an SVG plate too big
     *  to embed as vector text) — square-cropped + compressed like every icon. */
    public static String iconFromBitmap(Bitmap bmp, int budget) {
        if (bmp == null) return "";
        try {
            return compressIconBitmap(centerSquare(bmp), budget);
        } catch (Throwable t) {
            return "";
        }
    }

    public static String iconFromBase64(String b64, int budget) {
        if (b64 == null || b64.isEmpty()) return "";
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (src == null) return "";
            return compressIconBitmap(centerSquare(src), budget);
        } catch (Throwable t) {
            return "";
        }
    }

    private static Bitmap centerSquare(Bitmap src) {
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        return Bitmap.createBitmap(src, x, y, side, side);
    }

    /** EXIF-correct bitmap bounded so its LONG edge ≤ maxEdge, aspect ratio
     *  kept (no crop). For hosting-grade plates. Null on failure. */
    public static Bitmap boundedBitmap(Context c, Uri uri, int maxEdge) {
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(src, (d, info, s) -> {
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                d.setMutableRequired(false);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                int max = Math.max(w, h);
                if (max > maxEdge) {
                    float f = maxEdge / (float) max;
                    d.setTargetSize(Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)));
                }
            });
        } catch (Throwable t) {
            return null;
        }
    }

    /** EXIF-correct center-cropped square bitmap at px×px via ImageDecoder
     *  (orientation applied once). Null on any failure. */
    public static Bitmap squareBitmap(Context c, Uri uri, int px) {
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(c.getContentResolver(), uri);
            Bitmap bmp = ImageDecoder.decodeBitmap(src, (d, info, s) -> {
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                int max = Math.max(w, h);
                if (max > px * 2) {
                    float f = (px * 2f) / max;
                    d.setTargetSize(Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)));
                }
            });
            if (bmp == null) return null;
            return Bitmap.createScaledBitmap(centerSquare(bmp), px, px, true);
        } catch (Throwable t) {
            return null;
        }
    }

    /** n×n ARGB pixel grid of a bitmap (any size). */
    public static int[] gridPixels(Bitmap bmp, int n) {
        if (bmp == null) return null;
        Bitmap g = bmp.getWidth() == n && bmp.getHeight() == n
                ? bmp : Bitmap.createScaledBitmap(bmp, n, n, true);
        int[] px = new int[n * n];
        g.getPixels(px, 0, n, 0, 0, n, n);
        return px;
    }

    /** jpeg b64 of a painted bitmap within the paint budget — largest
     *  size/quality rung that fits wins (the Painted finish's image). */
    public static String paintB64(Bitmap src, int budget) {
        if (src == null) return "";
        // floor rungs are the guarantee: paprika paintings measured 8.3-9.2K
        // b64 at 208q50 — an empty return silently kills every Painted plate
        int[] dims = {384, 320, 256, 208, 176, 152, 128};
        int[] quals = {82, 72, 62, 50, 42};
        for (int dim : dims) {
            Bitmap scaled = Bitmap.createScaledBitmap(src, dim, dim, true);
            for (int q : quals) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.JPEG, q, bos);
                String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                if (b64.length() <= budget) return b64;
            }
        }
        return "";
    }

    private static String compressIconBitmap(Bitmap sq, int budget) {
        // reach the floor: a 180px/q60 stop failed busy uploads against the
        // 6000-char icon budget, silently falling back to plate 1 (2026-08-11)
        int[] dims = {512, 400, 320, 240, 180, 140, 120};
        for (int dim : dims) {
            int side = Math.min(dim, sq.getWidth());
            Bitmap scaled = Bitmap.createScaledBitmap(sq, side, side, true);
            for (int quality = 88; quality >= 44; quality -= 8) {
                String b64 = encode(scaled, quality);
                if (!b64.isEmpty() && b64.length() <= budget) return b64;
            }
            if (scaled != sq) scaled.recycle();
        }
        // last resort — same law as compressBitmap: raster always fits
        for (int dim = 128; dim >= 16; dim /= 2) {
            int side = Math.min(dim, sq.getWidth());
            Bitmap scaled = Bitmap.createScaledBitmap(sq, Math.max(1, side), Math.max(1, side), true);
            String b64 = encode(scaled, 40);
            if (!b64.isEmpty() && b64.length() <= budget) return b64;
        }
        return "";
    }

    public static String rotateBase64(String b64, int degrees, int budget) {
        if (b64 == null || b64.isEmpty()) return "";
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (src == null) return "";
            Bitmap out = src;
            if (degrees != 0) {
                Matrix m = new Matrix();
                m.postRotate(degrees);
                out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
                if (out != src) src.recycle();
            }
            return compressBitmap(out, budget);
        } catch (Throwable t) {
            return "";
        }
    }

    /* Resolution-first WebP search: WebP buys ~30% quality per byte over
     * JPEG, and for photos a larger plate at moderate quality reads better
     * than a small plate at high quality. First fit at the LARGEST dim wins. */
    /* The ladder MUST reach the floor: a 300px/q60 stop left busy photos at
     * ~8-16K b64 — "cannot be shrunk" refusals for budgets a 180px rung fits
     * easily (2026-08-10, Trois Jours à Paris plate 1). Raster always fits. */
    private static final int[] DIMS = {1080, 900, 720, 560, 420, 300, 240, 180, 140};
    private static final int[] QUALITIES = {88, 78, 68, 60, 50, 42};

    private static String compressBitmap(Bitmap src, int budget) {
        for (int dim : DIMS) {
            float scale = Math.min(1f, dim / (float) Math.max(src.getWidth(), src.getHeight()));
            Bitmap scaled = Bitmap.createScaledBitmap(src,
                    Math.max(1, Math.round(src.getWidth() * scale)),
                    Math.max(1, Math.round(src.getHeight() * scale)), true);
            for (int quality : QUALITIES) {
                String b64 = encode(scaled, quality);
                if (!b64.isEmpty() && b64.length() <= budget) {
                    if (scaled != src) { /* keep result, drop working copy */ }
                    return b64;
                }
            }
            if (scaled != src) scaled.recycle();
        }
        // Last resort: halve dimensions at floor quality until it fits.
        // "Raster always fits" is a law, not an aspiration (2026-08-11) —
        // every fixed slim target in the app relies on this never failing.
        for (int dim = 128; dim >= 16; dim /= 2) {
            float scale = Math.min(1f, dim / (float) Math.max(src.getWidth(), src.getHeight()));
            Bitmap scaled = Bitmap.createScaledBitmap(src,
                    Math.max(1, Math.round(src.getWidth() * scale)),
                    Math.max(1, Math.round(src.getHeight() * scale)), true);
            String b64 = encode(scaled, 40);
            if (!b64.isEmpty() && b64.length() <= budget) return b64;
        }
        return "";
    }

    @SuppressWarnings("deprecation")
    private static String encode(Bitmap bmp, int quality) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Bitmap.CompressFormat fmt = android.os.Build.VERSION.SDK_INT >= 30
                    ? Bitmap.CompressFormat.WEBP_LOSSY
                    : Bitmap.CompressFormat.WEBP;
            if (!bmp.compress(fmt, quality, out)) {
                out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
            }
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Correct data URI for a sealed b64 payload, sniffed from magic bytes.
     *  WebP = RIFF….WEBP, JPEG = FFD8, PNG = 89PNG, SVG = text starting <svg/<?xml. */
    public static String dataUri(String b64) {
        if (b64 == null || b64.isEmpty()) return "";
        return "data:" + mimeOf(b64) + ";base64," + b64;
    }

    public static String mimeOf(String b64) {
        try {
            int take = Math.min(b64.length(), 32);
            take -= take % 4;   // decodable prefix
            byte[] head = java.util.Base64.getMimeDecoder().decode(b64.substring(0, take));
            if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
                return "image/webp";
            }
            if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) return "image/jpeg";
            if (head.length >= 4 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                return "image/png";
            }
            String text = new String(head, java.nio.charset.StandardCharsets.UTF_8).trim().toLowerCase();
            if (text.startsWith("<svg") || text.startsWith("<?xml")) return "image/svg+xml";
        } catch (Throwable ignored) {}
        return "image/jpeg";
    }
}
