package com.eurobuddha.salon;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;

/** A QR bitmap for a string (tip address / profile URL) — for in-person scanning. */
final class Qr {
    static Bitmap bitmap(String data, int size) {
        if (data == null || data.isEmpty()) return null;
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            for (int x = 0; x < w; x++)
                for (int y = 0; y < h; y++) bmp.setPixel(x, y, m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            return bmp;
        } catch (Exception e) { return null; }
    }

    private Qr() {}
}
