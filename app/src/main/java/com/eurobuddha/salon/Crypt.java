package com.eurobuddha.salon;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** At-rest wrapping for hosting credentials (SFTP passwords, API tokens) —
 *  the first REUSABLE off-device secrets this app has ever stored, so plain
 *  SharedPreferences (extractable via device backup) is not acceptable.
 *  AndroidKeyStore AES/GCM; values are "gcm:" + b64(iv|ciphertext). When the
 *  Keystore is unavailable (Robolectric, exotic devices) values fall back to
 *  "plain:" + value — honest, visible in the stored form, and covered by the
 *  backup-exclusion rule either way. */
final class Crypt {

    private static final String ALIAS = "atelier_hosting";

    private Crypt() {}

    static String encrypt(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            SecretKey key = key();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "gcm:" + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            return "plain:" + value;
        }
    }

    static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (stored.startsWith("plain:")) return stored.substring(6);
        if (!stored.startsWith("gcm:")) return stored;   // legacy/unknown: as-is
        try {
            byte[] all = Base64.decode(stored.substring(4), Base64.NO_WRAP);
            byte[] iv = new byte[12];
            byte[] ct = new byte[all.length - 12];
            System.arraycopy(all, 0, iv, 0, 12);
            System.arraycopy(all, 12, ct, 0, ct.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }
}
