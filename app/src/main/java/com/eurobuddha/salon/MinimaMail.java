package com.eurobuddha.salon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Direct messages over MINIMA MAIL — fully on-chain, no chat server (so no UK
 * user-to-user-service exposure). A DM is a real coin sent to the recipient's
 * address with the message ENCRYPTED (and signed) into coin state[99] by the
 * node's built-in {@code maxmessage} (a LOCAL keypair op — not the Maxima network).
 * The recipient scans coins at their own address and {@code maxmessage decrypt}s each
 * state[99]; only their node can open it. Mirrors {@code mds/chainmail} (txns.js /
 * service.js), but sent to the recipient's own address so tips + DMs share one inbox
 * scan. Large media stays on the encrypted relay; only its small sealed manifest
 * rides in the message JSON.
 */
final class MinimaMail {

    static final String AMOUNT = "0.001";   // dust carrier, like chainmail

    interface Cb { void onSent(String txpowid); void onFailed(String message); }
    interface Scanned { void onMessages(List<Msg> messages); }

    /** A decrypted inbound DM. */
    static final class Msg {
        final String coinid, fromHandle, body, mediaRef, mediaMime;
        final long ts;
        Msg(String coinid, String fromHandle, String body, String mediaRef, String mediaMime, long ts) {
            this.coinid = coinid; this.fromHandle = fromHandle; this.body = body;
            this.mediaRef = mediaRef; this.mediaMime = mediaMime; this.ts = ts;
        }
    }

    /** Encrypt {@code message} to the recipient's maxima public key and send it as a
     *  coin to their address. */
    static void send(final NodeApi node, final String toAddress, final String toPublicKey,
                     final JSONObject message, final Cb cb) {
        try {
            String hexdata = Hex.to(message.toString().getBytes(StandardCharsets.UTF_8));
            node.cmd("maxmessage action:encrypt publickey:" + toPublicKey + " data:" + hexdata, new NodeApi.Cb() {
                @Override public void onResult(JSONObject enc) {
                    if (!enc.optBoolean("status", false)) { cb.onFailed("encrypt failed — bad recipient key?"); return; }
                    JSONObject r = enc.optJSONObject("response");
                    String sealed = r == null ? "" : r.optString("data", "");
                    if (sealed.isEmpty()) { cb.onFailed("encrypt returned nothing"); return; }
                    String state = "{\"99\":\"" + sealed + "\"}";
                    node.cmd("send amount:" + AMOUNT + " address:" + toAddress + " state:" + state, new NodeApi.Cb() {
                        @Override public void onResult(JSONObject j) {
                            if (j.optBoolean("status", false) || j.optBoolean("pending", false)) {
                                JSONObject rr = j.optJSONObject("response");
                                cb.onSent(rr != null ? rr.optString("txpowid", "") : "");
                            } else cb.onFailed(j.optString("error", "the node rejected the message"));
                        }
                        @Override public void onError(String m) { cb.onFailed(m); }
                    });
                }
                @Override public void onError(String m) { cb.onFailed(m); }
            });
        } catch (Exception e) { cb.onFailed(e.getMessage()); }
    }

    /** Scan coins at {@code myAddress}, maxmessage-decrypt each state[99], and return the
     *  DMs that opened (skipping tips / coins not for us). Runs several node calls. */
    static void scan(final NodeApi node, final String myAddress, final Scanned cb) {
        node.cmd("coins address:" + myAddress + " order:desc", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray arr = j.optJSONArray("response");
                final List<String[]> pending = new ArrayList<>();   // {coinid, sealedState99}
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i); if (c == null) continue;
                    String s99 = state99(c); String cid = c.optString("coinid", "");
                    if (!s99.isEmpty() && !cid.isEmpty()) pending.add(new String[]{cid, s99});
                }
                decryptNext(node, pending, 0, new ArrayList<>(), cb);
            }
            @Override public void onError(String m) { cb.onMessages(new ArrayList<>()); }
        });
    }

    private static void decryptNext(final NodeApi node, final List<String[]> pending, final int i,
                                    final List<Msg> out, final Scanned cb) {
        if (i >= pending.size()) { cb.onMessages(out); return; }
        final String coinid = pending.get(i)[0], sealed = pending.get(i)[1];
        node.cmd("maxmessage action:decrypt data:" + sealed, new NodeApi.Cb() {
            @Override public void onResult(JSONObject dec) {
                try {
                    if (dec.optBoolean("status", false)) {
                        JSONObject m = dec.optJSONObject("response");
                        JSONObject inner = m == null ? null : m.optJSONObject("message");
                        if (inner != null && inner.optBoolean("valid", false)) {
                            String hex = inner.optString("data", "");
                            String jsonStr = new String(Hex.from(hex), StandardCharsets.UTF_8);
                            JSONObject msg = new JSONObject(jsonStr);
                            out.add(new Msg(coinid, msg.optString("from", "someone"), msg.optString("body", ""),
                                    msg.optString("media", ""), msg.optString("mime", ""), msg.optLong("ts", 0)));
                        }
                    }
                } catch (Exception ignored) {}
                decryptNext(node, pending, i + 1, out, cb);
            }
            @Override public void onError(String mm) { decryptNext(node, pending, i + 1, out, cb); }
        });
    }

    private static String state99(JSONObject coin) {
        JSONArray st = coin.optJSONArray("state");
        if (st != null) for (int k = 0; k < st.length(); k++) {
            JSONObject s = st.optJSONObject(k);
            if (s != null && s.optInt("port", -1) == 99) return s.optString("data", "");
        }
        return "";
    }

    /** Build the message JSON to seal. */
    static JSONObject compose(String fromHandle, String body, String mediaRef, String mediaMime, long ts) {
        JSONObject m = new JSONObject();
        try {
            m.put("v", 1); m.put("from", fromHandle); m.put("body", body == null ? "" : body); m.put("ts", ts);
            if (mediaRef != null && !mediaRef.isEmpty()) { m.put("media", mediaRef); m.put("mime", mediaMime == null ? "" : mediaMime); }
        } catch (Exception ignored) {}
        return m;
    }

    private MinimaMail() {}
}
