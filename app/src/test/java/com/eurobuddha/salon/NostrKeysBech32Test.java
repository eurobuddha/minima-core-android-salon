package com.eurobuddha.salon;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Pins the npub encoding to the NIP-19 spec's test vector
 *  (https://github.com/nostr-protocol/nips/blob/master/19.md). */
public class NostrKeysBech32Test {

    @Test public void nip19SpecVector() {
        byte[] pub = Hex.from("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d");
        assertEquals("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6",
                NostrKeys.npub(pub));
    }

    @Test public void npubShape() {
        String npub = NostrKeys.npub(new byte[32]);
        assertEquals(63, npub.length());               // npub + '1' + 52 data + 6 checksum
        assertEquals("npub1", npub.substring(0, 5));
    }
}
