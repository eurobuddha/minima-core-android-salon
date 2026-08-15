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
