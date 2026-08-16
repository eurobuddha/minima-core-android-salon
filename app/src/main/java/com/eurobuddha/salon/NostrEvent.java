package com.eurobuddha.salon;

import java.security.MessageDigest;
import java.security.SecureRandom;

/** Nostr events for Blossom auth (kind 24242). The NIP-01 canonical serialization
 *  is hand-built: org.json escapes '/' as '\/', which changes the sha256 event id
 *  and gets the signature rejected server-side — never let it near these strings
 *  (parsing responses with it is fine). */
final class NostrEvent {

    private NostrEvent() {}

    /** Complete signed event JSON. The final object is rebuilt with the same
     *  deterministic escaper/serializers that produced the hashed canonical
     *  form, so id, tags and content can never diverge from what was signed. */
    static String signedJson(byte[] seckey, int kind, String[][] tags, String content, long createdAt) {
        String pk = Hex.to(Secp256k1.pubkeyXOnly(seckey));
        byte[] id = sha256(canonical(pk, createdAt, kind, tags, content));
        byte[] aux = new byte[32];
        new SecureRandom().nextBytes(aux);
        byte[] sig = Secp256k1.sign(id, seckey, aux);
        return "{\"id\":\"" + Hex.to(id) + "\",\"pubkey\":\"" + pk + "\",\"created_at\":" + createdAt
                + ",\"kind\":" + kind + ",\"tags\":" + tagsJson(tags) + ",\"content\":\"" + escape(content)
                + "\",\"sig\":\"" + Hex.to(sig) + "\"}";
    }

    /** NIP-01 canonical array — no whitespace, bare integers, sha256 of this is the id. */
    static String canonical(String pubkeyHex, long createdAt, int kind, String[][] tags, String content) {
        return "[0,\"" + pubkeyHex + "\"," + createdAt + "," + kind + ","
                + tagsJson(tags) + ",\"" + escape(content) + "\"]";
    }

    static String tagsJson(String[][] tags) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < tags.length; i++) {
            if (i > 0) b.append(',');
            b.append('[');
            for (int j = 0; j < tags[i].length; j++) {
                if (j > 0) b.append(',');
                b.append('"').append(escape(tags[i][j])).append('"');
            }
            b.append(']');
        }
        return b.append(']').toString();
    }

    /** NIP-01 escaping: named escapes for the JSON specials, \\u00XX for other
     *  control chars, everything else (including '/' and multi-byte UTF-8) raw. */
    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"':  b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    /** Blossom Authorization header: "Nostr " + standard base64 of the event JSON.
     *  java.util.Base64 (minSdk 28 ≥ API 26) so JVM unit tests can exercise it. */
    static String authHeader(String eventJson) {
        return "Nostr " + java.util.Base64.getEncoder()
                .encodeToString(eventJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static byte[] sha256(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
