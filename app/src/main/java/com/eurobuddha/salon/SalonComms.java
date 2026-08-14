package com.eurobuddha.salon;

import android.content.Context;

import java.security.SecureRandom;

/**
 * The device's messaging identity for DMs — an APP-managed libsodium keypair (X25519 box
 * + Ed25519 sign), independent of the Minima wallet/node (which has no Maxima). The 32-byte
 * seed is stored encrypted at rest (Android Keystore via {@link Crypt}); the published
 * identity (msgpk = boxPk‖signPk) goes in profile.json. Because it's app-managed, it needs
 * an explicit backup/restore — a reinstall without a backup starts a fresh inbox.
 */
final class SalonComms {

    private static LocalEcCryptoProvider CRYPTO;
    private static String MSGPK = "";

    static synchronized LocalEcCryptoProvider crypto(Context c) {
        if (CRYPTO == null) {
            CommsIdentity id = CommsIdentity.fromSeed(Sodium.get(), loadOrCreateSeed(c));
            CRYPTO = new LocalEcCryptoProvider(Sodium.get(), id);
            MSGPK = id.publicId();
        }
        return CRYPTO;
    }

    static synchronized String publicId(Context c) { crypto(c); return MSGPK; }

    private static byte[] loadOrCreateSeed(Context c) {
        String enc = SalonStore.get(c, "msgseed");
        if (!enc.isEmpty()) {
            try { byte[] s = Hex.from(Crypt.decrypt(enc)); if (s.length == 32) return s; } catch (Exception ignored) {}
        }
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        SalonStore.put(c, "msgseed", Crypt.encrypt(Hex.to(seed)));
        return seed;
    }

    /** Backup: the 32-byte messaging seed as hex (guard it — it IS your inbox). */
    static String exportSeed(Context c) {
        String e = SalonStore.get(c, "msgseed");
        return e.isEmpty() ? "" : Crypt.decrypt(e);
    }

    /** Restore from a hex seed; returns true on success and re-derives the identity. */
    static synchronized boolean importSeed(Context c, String hex) {
        try {
            byte[] s = Hex.from(hex.trim());
            if (s.length != 32) return false;
            SalonStore.put(c, "msgseed", Crypt.encrypt(Hex.to(s)));
            CRYPTO = null; MSGPK = "";
            SalonStore.put(c, "msgpk", publicId(c));
            return true;
        } catch (Exception e) { return false; }
    }

    private SalonComms() {}
}
