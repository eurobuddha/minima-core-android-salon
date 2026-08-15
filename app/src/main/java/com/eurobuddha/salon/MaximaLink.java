package com.eurobuddha.salon;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Salon's line to the Maxima transport — the first companion APK to ride it.
 *
 * Maxima (com.eurobuddha.maxima.app) is the always-on decentralised comms layer:
 * identity, sealing, NAT traversal, retries and the offline mailbox live THERE;
 * the Salon brings only its own message format over broadcast IPC, exactly as
 * the Minima node IPC works. This class wraps the whole conversation:
 *
 *   REGISTER   ask to connect (also wakes the transport); the user approves
 *              once, in the Maxima app's Settings -> Connected apps
 *   SUBSCRIBE  claim our application string, {@link #APPLICATION} - first claim
 *              wins permanently, so no other app can read Salon DMs
 *   IDENTITY   cache our Mx addresses (published in profile.json as "mxaddr",
 *              which is how peers learn to reach us - zero new protocol)
 *   SEND       a DM leaves here; UNKNOWN means "relay is HOLDING it for the
 *              offline peer" and is success-pending, never failure
 *   DELIVER    inbound arrives at {@link MaximaLinkReceiver} and funnels into
 *              the same intake as on-chain DMs
 *
 * The DM payload over Maxima is the SAME libsodium-sealed blob the coin path
 * puts in state[99]. Maxima's own E2E already seals the wire; keeping our seal
 * inside it means the sender's msgpk - the key DM threads are keyed on - stays
 * signature-verified by the code that already does it, on both paths.
 */
final class MaximaLink {

    /** Our application-string namespace on the transport. */
    static final String APPLICATION = "salon_dm_v1";

    static final String MAXIMA_PKG = "com.eurobuddha.maxima.app";
    private static final String RECEIVER = MaximaLinkReceiver.class.getName();
    private static final String PREFS = "salon_maxima";

    // Maxima IPC vocabulary (mirrors maxima's MaximaApiMessages).
    static final String ACTION_REGISTER = MAXIMA_PKG + ".REGISTER";
    static final String ACTION_SEND = MAXIMA_PKG + ".SEND";
    static final String ACTION_SUBSCRIBE = MAXIMA_PKG + ".SUBSCRIBE";
    static final String ACTION_IDENTITY = MAXIMA_PKG + ".IDENTITY";
    static final String ACTION_RESPONSE = MAXIMA_PKG + ".RESPONSE";
    static final String ACTION_DELIVER = MAXIMA_PKG + ".DELIVER";
    static final String ACTION_EVENT = MAXIMA_PKG + ".EVENT";

    static final String X_PACKAGE = "package";
    static final String X_CLASS = "class";
    static final String X_REQID = "requestid";
    static final String X_APPLICATION = "application";
    static final String X_TO = "to";
    static final String X_DATA = "data";
    static final String X_DATA_URI = "datauri";
    static final String X_RESULT = "result";
    static final String X_ERROR = "error";
    static final String X_ENABLED = "enabled";
    static final String X_ADDRESSES = "addresses";
    static final String X_PUBLICKEY = "publickey";
    static final String X_MSGID = "msgid";

    /** A send outcome, marshalled back from the RESPONSE broadcast. */
    interface SendCb {
        /** OK, or UNKNOWN (= mailboxed for an offline peer: sent-pending). */
        void onSent(String status, boolean pending);

        void onFailed(String error);
    }

    /** In-flight request callbacks, keyed by requestid. */
    static final Map<String, SendCb> PENDING_SENDS = new ConcurrentHashMap<>();

    /** In-flight blocking media calls, keyed by requestid. */
    static final Map<String, MediaWaiter> PENDING_MEDIA = new ConcurrentHashMap<>();

    static final class MediaWaiter {
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        volatile String resultText;   // put: the manifest JSON
        volatile android.net.Uri uri; // get: bytes handed back as content://
        volatile String error;
    }

    /** Application context, cached on connect() so the static media helpers -
     *  called from background threads without a Context - can broadcast. */
    private static volatile Context sApp;

    private MaximaLink() {
    }

    // ---------------------------------------------------------------
    // lifecycle: register + subscribe + identity, driven on app open
    // ---------------------------------------------------------------

    /**
     * Kick the whole handshake. Safe to call every launch: REGISTER is
     * idempotent (and wakes the transport), SUBSCRIBE re-claims our string,
     * IDENTITY refreshes the cached addresses. Each step advances when the
     * previous one's RESPONSE lands in {@link MaximaLinkReceiver}.
     */
    static void connect(Context ctx) {
        sApp = ctx.getApplicationContext();
        if (!isInstalled(ctx)) {
            return;
        }
        send(ctx, ACTION_REGISTER, i -> {
        });
    }

    /** Called by the receiver as RESPONSEs arrive; advances the handshake. */
    static void onRegisterResult(Context ctx, boolean approved) {
        prefs(ctx).edit().putBoolean("approved", approved).apply();
        if (approved) {
            send(ctx, ACTION_SUBSCRIBE, i -> i.putExtra(X_APPLICATION, APPLICATION));
            send(ctx, ACTION_IDENTITY, i -> {
            });
        }
    }

    static void onIdentity(Context ctx, String addressesCsv, String publicKeyHex) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        boolean changed = !addressesCsv.equals(prefs(ctx).getString("mxaddr", ""));
        e.putString("mxaddr", addressesCsv == null ? "" : addressesCsv);
        e.putString("mxid", publicKeyHex == null ? "" : publicKeyHex);
        e.apply();
        if (changed) {
            // Our reachable addresses moved (relay churn). The profile carries
            // them, so it needs a re-publish; MainActivity picks this flag up.
            prefs(ctx).edit().putBoolean("addr_dirty", true).apply();
        }
    }

    /** Our current Maxima addresses (CSV), for profile.json "mxaddr". */
    static String myAddresses(Context ctx) {
        return prefs(ctx).getString("mxaddr", "");
    }

    static boolean isApproved(Context ctx) {
        return prefs(ctx).getBoolean("approved", false);
    }

    /** Addresses changed since the profile was last published? */
    static boolean addressesDirty(Context ctx) {
        return prefs(ctx).getBoolean("addr_dirty", false);
    }

    static void clearAddressesDirty(Context ctx) {
        prefs(ctx).edit().putBoolean("addr_dirty", false).apply();
    }

    static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(MAXIMA_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Can we send over Maxima right now (installed + user-approved)? */
    static boolean isReady(Context ctx) {
        return isInstalled(ctx) && isApproved(ctx);
    }

    // ---------------------------------------------------------------
    // sending
    // ---------------------------------------------------------------

    /**
     * Send a sealed DM blob (hex, as {@code crypto.seal()} returns it) to one of
     * the peer's Maxima addresses (CSV from their profile). Tries the first
     * address; on hard failure the caller's onFailed fires and the coin path
     * takes over. UNKNOWN is success-pending.
     */
    static void sendDm(Context ctx, String peerMxAddrCsv, String sealedHex, SendCb cb) {
        String first = peerMxAddrCsv == null ? "" : peerMxAddrCsv.split(",")[0].trim();
        if (first.isEmpty()) {
            cb.onFailed("no maxima address");
            return;
        }
        String reqId = "dm-" + System.currentTimeMillis() + "-" + Math.abs(first.hashCode());
        PENDING_SENDS.put(reqId, cb);
        // Sends time out client-side; if no RESPONSE lands, fail over to chain.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            SendCb late = PENDING_SENDS.remove(reqId);
            if (late != null) {
                late.onFailed("maxima timeout");
            }
        }, 25_000);
        send(ctx, ACTION_SEND, i -> {
            i.putExtra(X_REQID, reqId);
            i.putExtra(X_TO, first);
            i.putExtra(X_APPLICATION, APPLICATION);
            // Sealed blobs are small for text DMs; inline as 0x hex. (Media refs
            // ride inside the sealed JSON, so the blob stays small either way.)
            i.putExtra(X_DATA, sealedHex.startsWith("0x") ? sealedHex : "0x" + sealedHex);
        });
    }

    // ---------------------------------------------------------------
    // self-hosted media (blocking; call from a background thread)
    // ---------------------------------------------------------------

    static final String MEDIA_PREFIX = "mx1:";
    static final String ACTION_MEDIA = MAXIMA_PKG + ".MEDIA";
    static final String X_OP = "op";
    static final String X_DATA_LEN = "datalen";

    static boolean isMediaRef(String s) {
        return s != null && s.startsWith(MEDIA_PREFIX);
    }

    /**
     * Publish bytes through Maxima's self-hosted media layer; returns an
     * {@code mx1:<base64url manifest>} ref. Blocks on the IPC round trip.
     */
    static String putMedia(byte[] zBytes, String zMime) throws Exception {
        Context ctx = requireApp();
        // Hand the bytes to Maxima as a content:// grant (they can exceed the
        // broadcast parcel limit).
        android.net.Uri uri = stageForMaxima(ctx, zBytes);
        String reqId = "mput-" + System.currentTimeMillis() + "-" + zBytes.length;
        MediaWaiter w = new MediaWaiter();
        PENDING_MEDIA.put(reqId, w);
        Intent i = new Intent(ACTION_MEDIA);
        i.setPackage(MAXIMA_PKG);
        i.putExtra(X_PACKAGE, ctx.getPackageName());
        i.putExtra(X_CLASS, RECEIVER);
        i.putExtra(X_REQID, reqId);
        i.putExtra(X_OP, "put");
        i.setDataAndType(uri, zMime == null ? "application/octet-stream" : zMime);
        i.putExtra(X_DATA_URI, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.grantUriPermission(MAXIMA_PKG, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.sendBroadcast(i);

        if (!w.latch.await(90, java.util.concurrent.TimeUnit.SECONDS)) {
            PENDING_MEDIA.remove(reqId);
            throw new Exception("maxima media put timeout");
        }
        PENDING_MEDIA.remove(reqId);
        if (w.error != null || w.resultText == null) {
            throw new Exception("maxima media put: " + w.error);
        }
        return MEDIA_PREFIX + android.util.Base64.encodeToString(
                w.resultText.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
    }

    /** Fetch + reassemble an {@code mx1:} ref to bytes. Blocks on the IPC round trip. */
    static byte[] getMedia(String zRef) throws Exception {
        Context ctx = requireApp();
        String manifestJson = new String(android.util.Base64.decode(
                zRef.substring(MEDIA_PREFIX.length()),
                android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE),
                java.nio.charset.StandardCharsets.UTF_8);
        String reqId = "mget-" + System.currentTimeMillis();
        MediaWaiter w = new MediaWaiter();
        PENDING_MEDIA.put(reqId, w);
        Intent i = new Intent(ACTION_MEDIA);
        i.setPackage(MAXIMA_PKG);
        i.putExtra(X_PACKAGE, ctx.getPackageName());
        i.putExtra(X_CLASS, RECEIVER);
        i.putExtra(X_REQID, reqId);
        i.putExtra(X_OP, "get");
        i.putExtra(X_DATA, manifestJson);
        ctx.sendBroadcast(i);

        if (!w.latch.await(90, java.util.concurrent.TimeUnit.SECONDS)) {
            PENDING_MEDIA.remove(reqId);
            throw new Exception("maxima media get timeout");
        }
        PENDING_MEDIA.remove(reqId);
        if (w.error != null || w.uri == null) {
            throw new Exception("maxima media get: " + w.error);
        }
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(w.uri)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static android.net.Uri stageForMaxima(Context ctx, byte[] bytes) throws Exception {
        java.io.File dir = new java.io.File(ctx.getCacheDir(), "shared");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        java.io.File f = new java.io.File(dir, "mxmedia-" + System.nanoTime() + ".bin");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(bytes);
        }
        return androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.getPackageName() + ".files", f);
    }

    private static Context requireApp() throws Exception {
        Context c = sApp;
        if (c == null) {
            throw new Exception("Maxima link not initialised");
        }
        if (!isReady(c)) {
            throw new Exception("Maxima not ready");
        }
        return c;
    }

    // ---------------------------------------------------------------

    private interface Filler {
        void fill(Intent i);
    }

    private static void send(Context ctx, String action, Filler filler) {
        Intent i = new Intent(action);
        i.setPackage(MAXIMA_PKG);
        i.putExtra(X_PACKAGE, ctx.getPackageName());
        i.putExtra(X_CLASS, RECEIVER);
        filler.fill(i);
        ctx.sendBroadcast(i);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
