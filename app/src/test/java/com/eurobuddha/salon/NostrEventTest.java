package com.eurobuddha.salon;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NostrEventTest {

    @Test public void escapingFollowsNip01() {
        assertEquals("a/b", NostrEvent.escape("a/b"));                    // the org.json trap: '/' must stay raw
        assertEquals("a\\\"b", NostrEvent.escape("a\"b"));
        assertEquals("a\\\\b", NostrEvent.escape("a\\b"));
        assertEquals("\\n\\r\\t\\b\\f", NostrEvent.escape("\n\r\t\b\f"));
        assertEquals("\\u0001", NostrEvent.escape(String.valueOf((char) 1)));
        assertEquals("emoji 🌸 raw", NostrEvent.escape("emoji 🌸 raw"));
    }

    @Test public void canonicalFormExact() {
        String c = NostrEvent.canonical(
                "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d",
                1700000000L, 24242,
                new String[][]{ { "t", "upload" }, { "x", "aa" }, { "expiration", "1700000600" } },
                "Upload a/b.jpg");
        assertEquals("[0,\"3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d\","
                + "1700000000,24242,[[\"t\",\"upload\"],[\"x\",\"aa\"],[\"expiration\",\"1700000600\"]],"
                + "\"Upload a/b.jpg\"]", c);
    }

    /** Parse the produced event with org.json, rebuild the canonical form from the
     *  PARSED fields, and check the embedded id and sig hold — catches any divergence
     *  between what was hashed and what was serialized, with no external vector. */
    @Test public void signedEventRoundTrip() throws Exception {
        byte[] sk = Hex.from("0000000000000000000000000000000000000000000000000000000000000003");
        String[][] tags = { { "t", "upload" }, { "x", "deadbeef" }, { "expiration", "1700000600" } };
        String json = NostrEvent.signedJson(sk, 24242, tags, "Upload \"x\"/emoji 🌸.jpg", 1700000000L);

        JSONObject o = new JSONObject(json);
        assertEquals(24242, o.getInt("kind"));
        assertEquals(1700000000L, o.getLong("created_at"));
        assertEquals(Hex.to(Secp256k1.pubkeyXOnly(sk)), o.getString("pubkey"));

        JSONArray jt = o.getJSONArray("tags");
        String[][] parsedTags = new String[jt.length()][];
        for (int i = 0; i < jt.length(); i++) {
            JSONArray t = jt.getJSONArray(i);
            parsedTags[i] = new String[t.length()];
            for (int j = 0; j < t.length(); j++) parsedTags[i][j] = t.getString(j);
        }
        String canonical = NostrEvent.canonical(o.getString("pubkey"), o.getLong("created_at"),
                o.getInt("kind"), parsedTags, o.getString("content"));
        byte[] id = NostrEvent.sha256(canonical);
        assertEquals(o.getString("id"), Hex.to(id));
        assertTrue(Secp256k1.verify(id, Hex.from(o.getString("pubkey")), Hex.from(o.getString("sig"))));
    }

    @Test public void authHeaderIsStandardBase64OfTheEvent() {
        String event = "{\"id\":\"ab\",\"content\":\"a/b\"}";
        String header = NostrEvent.authHeader(event);
        assertTrue(header.startsWith("Nostr "));
        String b64 = header.substring("Nostr ".length());
        assertTrue(b64.matches("[A-Za-z0-9+/]+=*"));   // standard alphabet, not URL-safe
        assertArrayEquals(event.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.util.Base64.getDecoder().decode(b64));
    }
}
