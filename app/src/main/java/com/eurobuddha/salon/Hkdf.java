package com.eurobuddha.salon;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HKDF-SHA256 (RFC 5869). Ported from support/freezepeach/.../comms/Hkdf.java. */
final class Hkdf {

    static byte[] derive(byte[] ikm, String info, int len) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] infoB = info.getBytes(StandardCharsets.UTF_8);
            byte[] okm = new byte[len];
            byte[] t = new byte[0];
            int pos = 0;
            for (int ctr = 1; pos < len; ctr++) {
                mac.update(t);
                mac.update(infoB);
                mac.update((byte) ctr);
                t = mac.doFinal();
                int n = Math.min(t.length, len - pos);
                System.arraycopy(t, 0, okm, pos, n);
                pos += n;
            }
            return okm;
        } catch (Exception e) {
            throw new RuntimeException("HKDF failed", e);
        }
    }

    private Hkdf() {}
}
