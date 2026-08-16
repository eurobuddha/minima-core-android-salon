package com.eurobuddha.salon;

import android.content.Context;

import java.math.BigInteger;

/** The user's nostr identity (for Blossom hosting) — a secp256k1 key derived
 *  deterministically from the existing 32-byte messaging seed (SalonComms).
 *  Nothing new is stored and nothing is added to backup: restoring the msgseed
 *  restores the same npub. Independent of the Ed25519/X25519 comms keypair —
 *  different HKDF info string, different curve. */
final class NostrKeys {

    // Uploads run on the io executor via Hosting.forProfile, which has no Context —
    // same app-context-in-a-static pattern as MaximaLink.
    private static volatile Context sApp;
    private static volatile String PUBHEX = "";

    private NostrKeys() {}

    static void init(Context c) { sApp = c.getApplicationContext(); }

    /** 32-byte secp256k1 secret key: HKDF(msgseed, "salon-nostr-v1") mod n. */
    static byte[] secKey() {
        byte[] seed = SalonComms.seed(sApp);
        BigInteger d = new BigInteger(1, Hkdf.derive(seed, "salon-nostr-v1", 32)).mod(Secp256k1.N);
        if (d.signum() == 0)   // probability ~2^-256; deterministic fallback keeps the key stable
            d = new BigInteger(1, Hkdf.derive(seed, "salon-nostr-v1#2", 32)).mod(Secp256k1.N);
        return Secp256k1.bytes32(d);
    }

    /** Lowercase-hex x-only public key — the nostr "pubkey". */
    static synchronized String pubkeyHex() {
        if (PUBHEX.isEmpty()) PUBHEX = Hex.to(Secp256k1.pubkeyXOnly(secKey()));
        return PUBHEX;
    }

    /** NIP-19 npub (bech32) form of the same public key. */
    static String npub() { return npub(Hex.from(pubkeyHex())); }

    /** Drop the cached pubkey after a seed import — the identity changed. */
    static synchronized void invalidate() { PUBHEX = ""; }

    /* ---------------- bech32 (encode-only, NIP-19: plain bech32, const 1) ---------------- */

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] GEN = { 0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3 };

    static String npub(byte[] pub32) { return bech32("npub", pub32); }

    private static String bech32(String hrp, byte[] bytes) {
        int[] data = to5bit(bytes);
        int[] values = new int[hrp.length() * 2 + 1 + data.length + 6];   // trailing 6 zeros for checksum calc
        int p = 0;
        for (int i = 0; i < hrp.length(); i++) values[p++] = hrp.charAt(i) >> 5;
        p++;   // separator 0
        for (int i = 0; i < hrp.length(); i++) values[p++] = hrp.charAt(i) & 31;
        for (int d : data) values[p++] = d;
        int poly = polymod(values) ^ 1;
        StringBuilder out = new StringBuilder(hrp).append('1');
        for (int d : data) out.append(CHARSET.charAt(d));
        for (int i = 0; i < 6; i++) out.append(CHARSET.charAt((poly >> (5 * (5 - i))) & 31));
        return out.toString();
    }

    /** 8-bit → 5-bit regroup, final group zero-padded. */
    private static int[] to5bit(byte[] in) {
        int[] out = new int[(in.length * 8 + 4) / 5];
        int acc = 0, bits = 0, n = 0;
        for (byte b : in) {
            acc = (acc << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) { bits -= 5; out[n++] = (acc >> bits) & 31; }
        }
        if (bits > 0) out[n] = (acc << (5 - bits)) & 31;
        return out;
    }

    private static int polymod(int[] values) {
        int chk = 1;
        for (int v : values) {
            int top = chk >>> 25;
            chk = ((chk & 0x1FFFFFF) << 5) ^ v;
            for (int i = 0; i < 5; i++) if (((top >> i) & 1) != 0) chk ^= GEN[i];
        }
        return chk;
    }
}
