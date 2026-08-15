package com.eurobuddha.salon;

import android.content.Context;
import android.util.Base64;

import com.goterl.lazysodium.interfaces.SecretBox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Resolves "relay1:" content refs produced by {@link RelayUploader}: fetch the
 * ciphertext chunks from the relay named in the ref, reassemble, decrypt. All
 * methods are SYNCHRONOUS and MUST be called off the main thread.
 */
final class RelayResolver {

    static final String PREFIX = "relay1:";
    private static final int MAX_MEDIA_CHUNKS = 512;
    private static final long MAX_MEDIA_BYTES = 80L * 1024 * 1024;   // covers Salon's 60MB video cap

    static boolean isRelayRef(String s) { return s != null && s.startsWith(PREFIX); }

    /** Any resolvable media ref: the legacy MaxLite relay OR the Maxima mesh. */
    static boolean isMediaRef(String s) {
        return isRelayRef(s) || MaximaLink.isMediaRef(s);
    }

    static final class Media { final byte[] bytes; final String mime; Media(byte[] b, String m) { bytes = b; mime = m; } }

    private static JSONObject manifest(String ref) throws Exception {
        byte[] json = Base64.decode(ref.substring(PREFIX.length()), Base64.NO_WRAP | Base64.URL_SAFE);
        return new JSONObject(new String(json, StandardCharsets.UTF_8));
    }

    /** Fetch + decrypt a ref to bytes + mime. Handles both schemes. */
    static Media resolveBytes(String ref) throws Exception {
        // Maxima mesh: the transport does the fetch/verify/decrypt; we just read
        // the mime out of the (base64url) manifest for display.
        if (MaximaLink.isMediaRef(ref)) {
            String json = new String(Base64.decode(
                    ref.substring(MaximaLink.MEDIA_PREFIX.length()),
                    Base64.NO_WRAP | Base64.URL_SAFE), StandardCharsets.UTF_8);
            String mime = new JSONObject(json).optString("mime", "application/octet-stream");
            return new Media(MaximaLink.getMedia(ref), mime);
        }
        JSONObject m = manifest(ref);
        String base = m.getString("relay");
        byte[] key = Hex.from(m.getString("key"));
        byte[] nonce = Hex.from(m.getString("nonce"));
        String mime = m.optString("mime", "application/octet-stream");
        JSONArray chunkids = m.getJSONArray("chunks");
        if (chunkids.length() > MAX_MEDIA_CHUNKS || m.optLong("size", 0) > MAX_MEDIA_BYTES)
            throw new Exception("relay media too large");
        ByteArrayOutputStream cipher = new ByteArrayOutputStream();
        for (int i = 0; i < chunkids.length(); i++) {
            byte[] c = RelayBlob.getChunk(base, chunkids.optString(i));
            cipher.write(c, 0, c.length);
            if (cipher.size() > MAX_MEDIA_BYTES + 4096) throw new Exception("relay media exceeded cap");
        }
        byte[] c = cipher.toByteArray();
        if (c.length < SecretBox.MACBYTES) throw new Exception("relay media too short");
        byte[] out = new byte[c.length - SecretBox.MACBYTES];
        if (!Sodium.get().cryptoSecretBoxOpenEasy(out, c, c.length, nonce, key))
            throw new Exception("relay decrypt failed");
        return new Media(out, mime);
    }

    static JSONObject resolveJson(String ref) throws Exception {
        return new JSONObject(new String(resolveBytes(ref).bytes, StandardCharsets.UTF_8));
    }

    /** Decrypt to a cache file (VideoView / MediaPlayer need a path/Uri, not bytes). */
    static File resolveToTempFile(Context ctx, String ref) throws Exception {
        Media m = resolveBytes(ref);
        File dir = new File(ctx.getCacheDir(), "relay"); dir.mkdirs();
        String ext = (m.mime.contains("video") || m.mime.contains("mp4")) ? ".mp4"
                   : (m.mime.contains("audio") || m.mime.contains("mpeg")) ? ".mp3"
                   : m.mime.contains("png") ? ".png" : ".jpg";
        File f = new File(dir, Integer.toHexString(ref.hashCode()) + ext);
        if (!f.exists() || f.length() == 0) {
            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(m.bytes); }
        }
        return f;
    }

    /** Refresh a ref's chunks' TTL (a GET touches them) without decrypting. */
    static void touch(String ref) {
        try {
            JSONObject m = manifest(ref);
            String base = m.getString("relay");
            JSONArray chunkids = m.getJSONArray("chunks");
            for (int i = 0; i < chunkids.length(); i++) RelayBlob.touch(base, chunkids.optString(i));
        } catch (Exception ignored) {}
    }

    private RelayResolver() {}
}
