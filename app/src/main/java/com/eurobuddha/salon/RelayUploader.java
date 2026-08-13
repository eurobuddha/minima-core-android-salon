package com.eurobuddha.salon;

import android.util.Base64;

import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.interfaces.SecretBox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Hosting backend that publishes ENCRYPTED content to the user's blind relay blob
 * store. Reuses the MediaTransport format (support/freezepeach/.../comms) — a fresh
 * crypto_secretbox key+nonce, 256 KiB ciphertext chunks POSTed to /blob, and a
 * {mime,size,key,nonce,chunks} manifest — but synchronously, and returns a
 * self-contained "relay1:&lt;base64url manifest&gt;" ref instead of an https URL. The
 * relay only ever holds opaque ciphertext; the key rides in the (public) ref, so a
 * public profile stays readable by any viewer while the relay stays blind.
 */
final class RelayUploader implements Hosting.Uploader {

    static final int CHUNK_SIZE = 256 * 1024;
    static final String DEFAULT_RELAY = "https://relay.privateprivate.org";

    private final Hosting.Profile profile;
    RelayUploader(Hosting.Profile p) { this.profile = p; }

    private String relayBase() {
        String u = profile.cfgStr("relayUrl");
        return RelayBlob.trimSlash(u.isEmpty() ? DEFAULT_RELAY : u);
    }

    @Override public String putFile(byte[] bytes, String relPath, String mime) throws Hosting.HostingException {
        try {
            LazySodium ls = Sodium.get();
            byte[] key = new byte[SecretBox.KEYBYTES];
            byte[] nonce = new byte[SecretBox.NONCEBYTES];
            SecureRandom r = new SecureRandom(); r.nextBytes(key); r.nextBytes(nonce);
            byte[] cipher = new byte[bytes.length + SecretBox.MACBYTES];
            if (!ls.cryptoSecretBoxEasy(cipher, bytes, bytes.length, nonce, key))
                throw new Hosting.HostingException("encrypt failed");

            String base = relayBase();
            JSONArray chunkids = new JSONArray();
            for (int off = 0; off < cipher.length; off += CHUNK_SIZE) {
                int len = Math.min(CHUNK_SIZE, cipher.length - off);
                byte[] c = new byte[len]; System.arraycopy(cipher, off, c, 0, len);
                chunkids.put(RelayBlob.putChunk(base, c));
            }

            JSONObject m = new JSONObject();
            m.put("v", 1);
            m.put("relay", base);
            m.put("mime", mime == null ? "application/octet-stream" : mime);
            m.put("size", bytes.length);
            m.put("key", Hex.to(key));
            m.put("nonce", Hex.to(nonce));
            m.put("chunks", chunkids);
            return RelayResolver.PREFIX + Base64.encodeToString(
                    m.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (Hosting.HostingException he) {
            throw he;
        } catch (Exception e) {
            throw new Hosting.HostingException("relay upload failed: " + e.getMessage());
        }
    }

    @Override public boolean exists(String relPath) { return false; }   // content-addressed, idempotent
}
