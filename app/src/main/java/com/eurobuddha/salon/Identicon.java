package com.eurobuddha.salon;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

public final class Identicon {
    /* Placeholder identicons are drawn under every image on every render —
     * cache them or grids allocate megabytes per rebuild. */
    private static final android.util.LruCache<String, Bitmap> CACHE =
            new android.util.LruCache<String, Bitmap>(6 * 1024 * 1024) {
                @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
            };

    private Identicon() {}

    /** Web-validation plaque — a blue disc with a white check, overlaid in a
     *  corner of a validated collection's image (ported from NFTwallet's
     *  Identicon.checkBadge so both clients show the same verified mark). */
    public static Bitmap checkBadge(int px) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = px / 2f;
        p.setColor(0xFFFFFFFF); c.drawCircle(r, r, r, p);                             // white outline ring
        p.setColor(0xFF2F80ED); c.drawCircle(r, r, r - Math.max(1, px * 0.09f), p);   // blue disc
        p.setColor(0xFFFFFFFF); p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px * 0.13f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        path.moveTo(px * 0.28f, px * 0.52f);
        path.lineTo(px * 0.43f, px * 0.67f);
        path.lineTo(px * 0.73f, px * 0.35f);
        c.drawPath(path, p);
        return bmp;
    }

    public static Bitmap forToken(String tokenid, int px) {
        String key = px + "|" + (tokenid == null ? "" : tokenid);
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        Bitmap made = draw(tokenid, px);
        CACHE.put(key, made);
        return made;
    }

    private static Bitmap draw(String tokenid, int px) {
        String hex = (tokenid == null ? "" : tokenid).replaceFirst("(?i)^0x", "").toLowerCase();
        StringBuilder sb = new StringBuilder(hex);
        while (sb.length() < 32) sb.append('0');
        hex = sb.toString();

        // Seeds are arbitrary strings ("slot3", "mint0"), not just hex
        int seed = 0;
        for (int i = 0; i < 4; i++) seed = seed * 31 + hex.charAt(i);
        seed = seed & 0x7FFFFFFF;
        int fg = (seed & 1) == 0 ? 0xFF111111 : 0xFFC1121F;
        int bg = (seed & 2) == 0 ? 0xFFFFFFFF : 0xFFE8E8E4;

        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(bg);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(fg);

        float scale = px / 48f;
        float cell = 8 * scale, pad = 4 * scale;
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 3; c++) {
                int idx = (r * 3 + c + 8) % 32;
                if ((Character.digit(hex.charAt(idx), 16) & 1) == 0) continue;
                int mirror = (c == 0) ? 4 : (c == 1 ? 3 : 2);
                drawCell(cv, p, c, r, cell, pad);
                if (mirror != c) drawCell(cv, p, mirror, r, cell, pad);
            }
        }
        return bmp;
    }

    public static Bitmap minima(int px, int color) {
        Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        float s = px / 48f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(2.2f * s);
        cv.drawCircle(24 * s, 24 * s, 20 * s, p);
        p.setStrokeWidth(2.6f * s);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        Path path = new Path();
        path.moveTo(14 * s, 32 * s);
        path.lineTo(14 * s, 18 * s);
        path.lineTo(24 * s, 26 * s);
        path.lineTo(34 * s, 18 * s);
        path.lineTo(34 * s, 32 * s);
        cv.drawPath(path, p);
        return bmp;
    }

    private static void drawCell(Canvas cv, Paint p, int col, int row, float cell, float pad) {
        float x = col * cell + pad, y = row * cell + pad;
        cv.drawRect(x, y, x + cell, y + cell, p);
    }

    @SuppressWarnings("unused")
    private static int hsl(int h, int s, int l) {
        float ss = s / 100f, ll = l / 100f;
        float c = (1 - Math.abs(2 * ll - 1)) * ss;
        float hp = h / 60f;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; }
        else if (hp < 2) { r = x; g = c; }
        else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; }
        else if (hp < 5) { r = c; b = x; }
        else { r = c; b = x; }
        float m = ll - c / 2;
        return Color.rgb(Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }
}
