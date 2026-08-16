package com.eurobuddha.salon;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.minimarex.minimaapi.MinimaAPI;
import org.minimarex.minimaapi.MinimaAPIMessages;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Closed-app alerts. The Minima core pushes a targeted {@code org.minimarex.minimacore.NOTIFY}
 * broadcast to our package whenever a coin lands in our wallet (NEWCOIN) or at a coinnotify
 * address (NOTIFYCOIN) — even when Salon is dead/backgrounded. We classify the coin: a DM
 * (state[99], decrypted in-app) or a tip (state[1]=handle), store the DM, and post a heads-up
 * notification. No foreground service, no persistent notification. Mirrors
 * apks/terminal/.../MinimaNotifyReceiver.
 */
public class SalonNotifyReceiver extends BroadcastReceiver {

    static final String CHANNEL = "salon_activity";

    @Override public void onReceive(Context ctx, Intent intent) {
        if (!Objects.equals(intent.getAction(), MinimaAPIMessages.MINIMA_API_NOTIFY)) return;
        if (!MinimaAPI.checkMinimaID(ctx, intent)) return;   // only our paired node
        final String message = intent.getStringExtra(MinimaAPIMessages.MINIMA_API_NOTIFY_DATA);
        if (message == null) return;
        // In-app libsodium decrypt + SQLite are too heavy for the broadcast thread's ANR
        // budget (esp. first-run native-lib load) — hand off and finish asynchronously.
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> { try { process(app, message); } catch (Exception ignored) {} finally { pr.finish(); } }).start();
    }

    private void process(Context ctx, String message) {
        try {
            JSONObject j = new JSONObject(message);
            String event = j.optString("event", "");
            if (!event.equals("NEWCOIN") && !event.equals("NOTIFYCOIN")) return;
            JSONObject data = j.optJSONObject("data"); if (data == null) return;
            JSONObject coin = data.optJSONObject("coin"); if (coin == null) coin = data;
            String myAddr = SalonStore.get(ctx, "tipaddr");
            String addr = coin.optString("address", data.optString("address", ""));
            if (event.equals("NOTIFYCOIN") && !myAddr.isEmpty() && !addr.equalsIgnoreCase(myAddr)) return;

            String s99 = statePort(coin, 99);
            if (!s99.isEmpty()) { handleDm(ctx, coin, s99); return; }

            String from = SalonRegistry.unhex(statePort(coin, 1));
            if (!from.isEmpty()) {
                String amt = coin.optString("tokenamount", coin.optString("amount", "?"));
                String tok = TipTransport.MXUSD.equalsIgnoreCase(coin.optString("tokenid", "")) ? "mxUSD" : "MINIMA";
                notify(ctx, "💰 Tip received", "@" + from.replaceFirst("^@", "") + " tipped you " + amt + " " + tok);
            }
        } catch (Exception ignored) {}
    }

    private void handleDm(Context ctx, JSONObject coin, String s99) {
        intakeDm(ctx, s99, coin.optString("coinid", ""));
    }

    /**
     * The ONE inbound-DM path, shared by both transports: on-chain coins
     * (state[99] via the core's NOTIFY broadcast or a scan) and Maxima
     * deliveries ({@link MaximaLinkReceiver}). The payload is the identical
     * libsodium-sealed blob either way, so sender authenticity (the msgpk the
     * thread is keyed on) is verified by the same signature check regardless of
     * which pipe carried it.
     *
     * @param sealedHex the sealed blob, hex with or without 0x
     * @param msgId     dedup id — a coinid on-chain, "mx-&lt;msgid&gt;" over Maxima
     * @return true if this was a valid, NEW message
     */
    static boolean intakeDm(Context ctx, String sealedHex, String msgId) {
        try {
            String hex = (sealedHex.startsWith("0x") || sealedHex.startsWith("0X"))
                    ? sealedHex.substring(2) : sealedHex;
            Opened o = SalonComms.crypto(ctx).open(hex);
            if (o == null || !o.valid) return false;   // not for us, or signature fails (anti-impersonation)
            JSONObject m = new JSONObject(new String(o.plaintext, StandardCharsets.UTF_8));
            String from = m.optString("from", "someone"), body = m.optString("body", ""), media = m.optString("media", "");
            String preview = !body.isEmpty() ? body : (!media.isEmpty() ? "📎 media" : "");
            boolean isNew = MailDb.get(ctx).insert(msgId, o.fromPublicId, false, body, media, m.optString("mime", ""), m.optLong("ts", 0), o.valid);
            // Learn the sender's Maxima address from the message itself, so our
            // reply goes back over Maxima — the transport becomes two-way after a
            // single message, no profile-view required. (Runs even on a duplicate,
            // in case we learned the peer before they published an mxaddr.)
            String peerMx = m.optString("mxaddr", "");
            if (!peerMx.isEmpty()) MailDb.get(ctx).setMxAddr(o.fromPublicId, peerMx);
            if (isNew) {
                MailDb.get(ctx).upsertContact(o.fromPublicId, from, "", m.optString("addr", ""), preview, m.optLong("ts", 0), true);
                notifyStatic(ctx, "💬 @" + from.replaceFirst("^@", ""), preview);
                // Repaint the open Messages/Thread tab. Without this a Maxima DM
                // (or a background coin NOTIFY) only notified - it never reached
                // the live UI, so the message sat in the DB unseen until you
                // navigated away and back.
                MainActivity.onInboxChanged();
            }
            return isNew;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void notify(Context ctx, String title, String text) {
        notifyStatic(ctx, title, text);
    }

    static void notifyStatic(Context ctx, String title, String text) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Salon activity", NotificationManager.IMPORTANCE_HIGH));
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(ctx.getApplicationInfo().icon)
                .setContentTitle(title).setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true).setContentIntent(pi);
        try { NotificationManagerCompat.from(ctx).notify((int) (System.currentTimeMillis() % 100000), b.build()); }
        catch (SecurityException ignored) {}   // POST_NOTIFICATIONS not granted
    }

    private static String statePort(JSONObject coin, int port) {
        JSONArray st = coin.optJSONArray("state");
        if (st != null) for (int k = 0; k < st.length(); k++) {
            JSONObject s = st.optJSONObject(k);
            if (s != null && s.optInt("port", -1) == port) return s.optString("data", "");
        }
        return "";
    }
}
