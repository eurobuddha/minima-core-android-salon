package com.eurobuddha.salon;

import java.math.BigInteger;
import java.security.MessageDigest;

/** BIP-340 Schnorr signatures over secp256k1, pure Java (BigInteger affine math).
 *  Sign + verify only — no ECDH, no DER, no ECDSA. Exists for the nostr auth
 *  events Blossom hosting requires (kind 24242, see NostrEvent/BlossomUploader).
 *
 *  Deliberately hand-rolled instead of pulling BouncyCastle: with R8 off the
 *  jdk18on jar lands whole (~4 MB) in the APK for 1–2 signatures per upload.
 *  BigInteger is NOT constant-time; accepted — signing is phone-local (no remote
 *  timing oracle) and the seed already lives in process memory, the same posture
 *  as SalonComms. Every signature is self-verified before it leaves this class,
 *  and the math is pinned to the official BIP-340 test vectors in Bip340Test. */
final class Secp256k1 {

    static final BigInteger P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    private static final BigInteger GX = new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);
    private static final BigInteger GY = new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);
    private static final BigInteger[] G = { GX, GY };
    private static final BigInteger SEVEN = BigInteger.valueOf(7);

    private Secp256k1() {}

    /** x-only (32-byte big-endian) public key for a 32-byte secret key. */
    static byte[] pubkeyXOnly(byte[] seckey) {
        return bytes32(mul(G, toScalar(seckey))[0]);
    }

    /** BIP-340 sign. msg is arbitrary length (nostr signs the 32-byte event id). */
    static byte[] sign(byte[] msg, byte[] seckey, byte[] auxRand) {
        if (auxRand == null || auxRand.length != 32) throw new IllegalArgumentException("aux_rand must be 32 bytes");
        BigInteger d0 = toScalar(seckey);
        BigInteger[] pub = mul(G, d0);
        BigInteger d = evenY(pub) ? d0 : N.subtract(d0);
        byte[] px = bytes32(pub[0]);
        byte[] t = xor(bytes32(d), taggedHash("BIP0340/aux", auxRand));
        BigInteger k0 = new BigInteger(1, taggedHash("BIP0340/nonce", t, px, msg)).mod(N);
        if (k0.signum() == 0) throw new IllegalStateException("zero nonce");
        BigInteger[] r = mul(G, k0);
        BigInteger k = evenY(r) ? k0 : N.subtract(k0);
        byte[] rx = bytes32(r[0]);
        BigInteger e = new BigInteger(1, taggedHash("BIP0340/challenge", rx, px, msg)).mod(N);
        byte[] sig = new byte[64];
        System.arraycopy(rx, 0, sig, 0, 32);
        System.arraycopy(bytes32(k.add(e.multiply(d)).mod(N)), 0, sig, 32, 32);
        // A wrong signature here means a math bug — fail loudly instead of shipping
        // a request the server rejects with an unexplained 401.
        if (!verify(msg, px, sig)) throw new IllegalStateException("BIP-340 self-verify failed");
        return sig;
    }

    /** BIP-340 verify (also the sign() self-check). */
    static boolean verify(byte[] msg, byte[] pubX, byte[] sig) {
        if (pubX == null || pubX.length != 32 || sig == null || sig.length != 64) return false;
        BigInteger[] pub = liftX(new BigInteger(1, pubX));
        if (pub == null) return false;
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(sig, 0, 32));
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(sig, 32, 64));
        if (r.compareTo(P) >= 0 || s.compareTo(N) >= 0) return false;
        BigInteger e = new BigInteger(1, taggedHash("BIP0340/challenge", bytes32(r), bytes32(pub[0]), msg)).mod(N);
        BigInteger[] R = add(mul(G, s), mul(pub, N.subtract(e)));   // s·G − e·P
        return R != null && evenY(R) && R[0].equals(r);
    }

    /* ---------------- internals ---------------- */

    private static BigInteger toScalar(byte[] seckey) {
        if (seckey == null || seckey.length != 32) throw new IllegalArgumentException("secret key must be 32 bytes");
        BigInteger d = new BigInteger(1, seckey);
        if (d.signum() == 0 || d.compareTo(N) >= 0) throw new IllegalArgumentException("secret key out of range");
        return d;
    }

    /** The even-Y point with the given x, or null when x isn't on the curve (or ≥ p). */
    private static BigInteger[] liftX(BigInteger x) {
        if (x.signum() < 0 || x.compareTo(P) >= 0) return null;
        BigInteger c = x.pow(3).add(SEVEN).mod(P);
        BigInteger y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P);   // sqrt works because p ≡ 3 (mod 4)
        if (!y.multiply(y).mod(P).equals(c)) return null;
        return new BigInteger[]{ x, y.testBit(0) ? P.subtract(y) : y };
    }

    private static boolean evenY(BigInteger[] pt) { return !pt[1].testBit(0); }

    /** Affine addition; null is the point at infinity. */
    private static BigInteger[] add(BigInteger[] a, BigInteger[] b) {
        if (a == null) return b;
        if (b == null) return a;
        BigInteger lam;
        if (a[0].equals(b[0])) {
            if (!a[1].equals(b[1])) return null;   // p + (−p) = ∞
            lam = a[0].multiply(a[0]).multiply(BigInteger.valueOf(3))
                    .multiply(a[1].shiftLeft(1).modInverse(P)).mod(P);
        } else {
            lam = b[1].subtract(a[1]).multiply(b[0].subtract(a[0]).modInverse(P)).mod(P);
        }
        BigInteger x = lam.multiply(lam).subtract(a[0]).subtract(b[0]).mod(P);
        BigInteger y = lam.multiply(a[0].subtract(x)).subtract(a[1]).mod(P);
        return new BigInteger[]{ x, y };
    }

    /** Double-and-add scalar multiplication, MSB-first. Not constant-time (see header). */
    private static BigInteger[] mul(BigInteger[] pt, BigInteger k) {
        BigInteger[] acc = null;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            acc = add(acc, acc);
            if (k.testBit(i)) acc = add(acc, pt);
        }
        return acc;
    }

    /** sha256(sha256(tag) ‖ sha256(tag) ‖ parts…) per BIP-340. */
    static byte[] taggedHash(String tag, byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] th = md.digest(tag.getBytes("UTF-8"));
            md.reset();
            md.update(th);
            md.update(th);
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Fixed 32-byte big-endian — BigInteger.toByteArray() pads/signs unpredictably. */
    static byte[] bytes32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) out[i] = (byte) (a[i] ^ b[i]);
        return out;
    }
}
