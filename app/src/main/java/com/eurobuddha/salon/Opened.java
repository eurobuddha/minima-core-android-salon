package com.eurobuddha.salon;

/** Result of opening a sealed DM blob: the plaintext plus whether the sender signature verified. */
final class Opened {
    final boolean valid;         // sender signature checked out
    final String fromPublicId;   // sender's msgpk (boxPk||signPk hex), embedded + verified
    final byte[] plaintext;

    Opened(boolean valid, String fromPublicId, byte[] plaintext) {
        this.valid = valid;
        this.fromPublicId = fromPublicId;
        this.plaintext = plaintext;
    }
}
