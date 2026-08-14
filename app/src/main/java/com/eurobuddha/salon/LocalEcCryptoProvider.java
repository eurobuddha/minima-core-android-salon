package com.eurobuddha.salon;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.interfaces.Box;
import com.goterl.lazysodium.interfaces.Sign;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Client-side end-to-end crypto — NO node crypto, NO Maxima. Ported verbatim from
 * support/freezepeach/.../comms/LocalEcCryptoProvider.java. Envelope:
 *   payload = { f: senderMsgpk, b: bodyHex, s: Ed25519_sign(senderSignKey, senderMsgpk || body) }
 *   blob    = crypto_box_seal(payload, recipientBoxKey)   // anonymous; only the recipient opens it
 * seal() returns the hex to put in coin state[99]; open() returns null if the blob isn't for me.
 */
final class LocalEcCryptoProvider {

    private final LazySodium ls;
    private final CommsIdentity me;

    LocalEcCryptoProvider(LazySodium ls, CommsIdentity me) { this.ls = ls; this.me = me; }

    String identity() { return me.publicId(); }

    String seal(String toPublicId, byte[] plaintext) {
        try {
            byte[] from = Hex.from(me.publicId());
            byte[] signed = concat(from, plaintext);
            byte[] sig = new byte[Sign.BYTES];
            if (!ls.cryptoSignDetached(sig, signed, signed.length, me.signSk)) throw new RuntimeException("sign failed");

            JSONObject payload = new JSONObject();
            payload.put("f", me.publicId());
            payload.put("b", Hex.to(plaintext));
            payload.put("s", Hex.to(sig));
            byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

            byte[] recipientBoxPk = CommsIdentity.boxPkOf(toPublicId);
            byte[] cipher = new byte[payloadBytes.length + Box.SEALBYTES];
            if (!ls.cryptoBoxSeal(cipher, payloadBytes, payloadBytes.length, recipientBoxPk)) throw new RuntimeException("seal failed");
            return Hex.to(cipher);
        } catch (Exception e) {
            throw new RuntimeException("seal error: " + e.getMessage(), e);
        }
    }

    Opened open(String blobHex) {
        try {
            byte[] cipher = Hex.from(blobHex);
            if (cipher.length <= Box.SEALBYTES) return null;
            byte[] payloadBytes = new byte[cipher.length - Box.SEALBYTES];
            if (!ls.cryptoBoxSealOpen(payloadBytes, cipher, cipher.length, me.boxPk, me.boxSk)) return null;

            JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
            String from = payload.optString("f", "");
            byte[] body = Hex.from(payload.optString("b", ""));
            byte[] sig  = Hex.from(payload.optString("s", ""));
            if (!CommsIdentity.isValidPublicId(from) || sig.length != Sign.BYTES) return new Opened(false, from, body);

            byte[] signed = concat(Hex.from(from), body);
            byte[] signPk = CommsIdentity.signPkOf(from);
            boolean valid = ls.cryptoSignVerifyDetached(sig, signed, signed.length, signPk);
            return new Opened(valid, from, body);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
