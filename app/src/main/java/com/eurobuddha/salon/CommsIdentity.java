package com.eurobuddha.salon;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.interfaces.Box;
import com.goterl.lazysodium.interfaces.Sign;

import java.util.Arrays;

/**
 * Seed-derived messaging identity: X25519 (encryption) + Ed25519 (signing), deterministic
 * from a seed. Published identity = 0x + boxPublicKey + signPublicKey (64 bytes). Ported
 * from support/freezepeach/.../comms/CommsIdentity.java (APP_CONTEXT = "salon"). This is an
 * APP-side keypair — NOT the node's wallet/script key, and NOT Maxima; all crypto is in-app.
 */
final class CommsIdentity {

    private static final String APP_CONTEXT = "salon";

    final byte[] boxPk, boxSk;     // X25519 — encryption
    final byte[] signPk, signSk;   // Ed25519 — signing

    private CommsIdentity(byte[] boxPk, byte[] boxSk, byte[] signPk, byte[] signSk) {
        this.boxPk = boxPk; this.boxSk = boxSk; this.signPk = signPk; this.signSk = signSk;
    }

    static CommsIdentity fromSeed(LazySodium ls, byte[] seed) {
        byte[] boxSeed  = Hkdf.derive(seed, APP_CONTEXT + "-box-v1",  Box.SEEDBYTES);
        byte[] signSeed = Hkdf.derive(seed, APP_CONTEXT + "-sign-v1", Sign.SEEDBYTES);
        byte[] boxPk = new byte[Box.PUBLICKEYBYTES], boxSk = new byte[Box.SECRETKEYBYTES];
        byte[] signPk = new byte[Sign.PUBLICKEYBYTES], signSk = new byte[Sign.SECRETKEYBYTES];
        if (!ls.cryptoBoxSeedKeypair(boxPk, boxSk, boxSeed)) throw new RuntimeException("box seed keypair failed");
        if (!ls.cryptoSignSeedKeypair(signPk, signSk, signSeed)) throw new RuntimeException("sign seed keypair failed");
        return new CommsIdentity(boxPk, boxSk, signPk, signSk);
    }

    /** boxPk || signPk, hex with 0x prefix — what you publish (msgpk). */
    String publicId() { return "0x" + Hex.to(boxPk) + Hex.to(signPk); }

    static boolean isValidPublicId(String publicId) {
        try { return Hex.from(publicId).length == Box.PUBLICKEYBYTES + Sign.PUBLICKEYBYTES; }
        catch (Exception e) { return false; }
    }

    static byte[] boxPkOf(String publicId) {
        return Arrays.copyOfRange(Hex.from(publicId), 0, Box.PUBLICKEYBYTES);
    }

    static byte[] signPkOf(String publicId) {
        byte[] b = Hex.from(publicId);
        return Arrays.copyOfRange(b, Box.PUBLICKEYBYTES, Box.PUBLICKEYBYTES + Sign.PUBLICKEYBYTES);
    }
}
