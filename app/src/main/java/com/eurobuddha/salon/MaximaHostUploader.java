package com.eurobuddha.salon;

/**
 * "Maxima mesh" hosting — the seventh destination, and the only one where YOU
 * are the server.
 *
 * A file is handed to the Maxima transport, which encrypts it, chunks it, keeps
 * it on this phone (the source of truth) and replicates it to a few relays so
 * it survives the phone sleeping. What comes back is a self-contained
 * {@code mx1:} ref — no URL, no third-party host, no expiry clock but the
 * relays' own. Resolved only inside a Maxima-capable app, exactly like the old
 * encrypted-relay type but with no central relay to trust or pay.
 */
final class MaximaHostUploader implements Hosting.Uploader {

    MaximaHostUploader(Hosting.Profile p) {
    }

    @Override
    public String putFile(byte[] bytes, String relPath, String mime) throws Hosting.HostingException {
        try {
            return MaximaLink.putMedia(bytes, mime);
        } catch (Exception e) {
            // Name the offending asset so a multi-file profile publish points at
            // the exact file (e.g. an oversize video) rather than failing vaguely.
            String file = relPath == null ? "" : relPath.substring(relPath.lastIndexOf('/') + 1);
            throw new Hosting.HostingException(
                    (file.isEmpty() ? "" : "“" + file + "”: ") + e.getMessage());
        }
    }

    @Override
    public boolean exists(String relPath) {
        return false;   // content-addressed, idempotent — always publish
    }

    @Override
    public void close() {
    }
}
