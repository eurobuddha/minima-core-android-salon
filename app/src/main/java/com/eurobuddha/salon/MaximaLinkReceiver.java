package com.eurobuddha.salon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * The Maxima transport talks back here: RESPONSE (replies to our requests),
 * DELIVER (an inbound Salon DM), EVENT (host/contact changes — our cue to
 * refresh our published addresses).
 *
 * Guarded in the manifest by Maxima's own signature-level USE_MAXIMA
 * permission, so only the family-signed transport can feed us. An inbound DM's
 * payload is the same libsodium-sealed blob as the coin path; it funnels into
 * {@link SalonNotifyReceiver#intakeDm} where the sender's msgpk signature is
 * verified before anything is stored — the pipe changed, the trust check did not.
 */
public class MaximaLinkReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        switch (action) {
            case MaximaLink.ACTION_RESPONSE:
                handleResponse(app, intent);
                return;
            case MaximaLink.ACTION_DELIVER:
                // libsodium + SQLite blow the broadcast ANR budget; hand off.
                final PendingResult pr = goAsync();
                new Thread(() -> {
                    try {
                        handleDeliver(app, intent);
                    } catch (Exception ignored) {
                    } finally {
                        pr.finish();
                    }
                }).start();
                return;
            case MaximaLink.ACTION_EVENT:
                // Hosts changed = our reachable addresses may have moved.
                MaximaLink.connect(app);
                return;
            default:
        }
    }

    private void handleResponse(Context ctx, Intent i) {
        String reqId = i.getStringExtra(MaximaLink.X_REQID);
        String error = i.getStringExtra(MaximaLink.X_ERROR);
        String result = i.getStringExtra(MaximaLink.X_RESULT);

        // A pending DM send?
        if (reqId != null && reqId.startsWith("dm-")) {
            MaximaLink.SendCb cb = MaximaLink.PENDING_SENDS.remove(reqId);
            if (cb == null) {
                return;   // already timed out and failed over
            }
            // UNKNOWN = the relay HELD it for an offline peer. Sent-pending.
            if ("OK".equals(result)) {
                cb.onSent("OK", false);
            } else if ("UNKNOWN".equals(result)) {
                cb.onSent("UNKNOWN", true);
            } else {
                cb.onFailed(error != null ? error : String.valueOf(result));
            }
            return;
        }

        // Handshake replies (register/subscribe/identity) have no reqId routing;
        // classify by payload shape.
        if (i.hasExtra(MaximaLink.X_ENABLED)) {
            MaximaLink.onRegisterResult(ctx, i.getBooleanExtra(MaximaLink.X_ENABLED, false));
            return;
        }
        String addresses = i.getStringExtra(MaximaLink.X_ADDRESSES);
        if (addresses != null) {
            MaximaLink.onIdentity(ctx, addresses, i.getStringExtra(MaximaLink.X_PUBLICKEY));
        }
        // subscribe acks need no action; errors here are benign (retried on open)
    }

    private void handleDeliver(Context ctx, Intent i) {
        if (!MaximaLink.APPLICATION.equals(i.getStringExtra(MaximaLink.X_APPLICATION))) {
            return;
        }
        String msgid = i.getStringExtra(MaximaLink.X_MSGID);
        String sealedHex = i.getStringExtra(MaximaLink.X_DATA);
        if (sealedHex == null) {
            // Large payloads arrive as a content:// grant, pruned after ~5min -
            // copy out immediately.
            Uri uri = i.getParcelableExtra(MaximaLink.X_DATA_URI);
            if (uri == null) {
                return;
            }
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                sealedHex = "0x" + Hex.to(bos.toByteArray());
            } catch (Exception e) {
                return;
            }
        }
        // Same intake as the coin path: open() verifies the sender's msgpk
        // signature; invalid or duplicate messages die there.
        SalonNotifyReceiver.intakeDm(ctx, sealedHex,
                "mx-" + (msgid == null ? String.valueOf(System.currentTimeMillis()) : msgid));
    }
}
