package com.eurobuddha.salon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Direct messages over MINIMA MAIL — fully on-chain, no chat server (so no UK
 * user-to-user-service exposure). A DM is a real coin sent to the recipient's address
 * with the message SEALED in-app (libsodium {@link LocalEcCryptoProvider}, NOT Maxima,
 * NOT node crypto) into coin state[99]. The recipient scans coins at their own address
 * and opens each state[99] IN-APP — one {@code coins} call, no per-message node crypto.
 * Only their box key opens the seal; an embedded Ed25519 signature authenticates the
 * sender. Large media stays on the encrypted relay; only its sealed manifest rides in
 * the message JSON. Idea mirrors mds/chainmail, transport = plain on-chain send.
 */
final class MinimaMail {

    static final String AMOUNT = "0.001";   // dust carrier

    interface Cb { void onSent(String txpowid); void onFailed(String message); }
    interface Scanned { void onMessages(List<Msg> messages); }

    /** A decrypted inbound DM. {@code fromPublicId} is cryptographically verified; {@code fromHandle}
     *  is the display name inside the (signed) payload. */
    static final class Msg {
        final String coinid, fromPublicId, fromHandle, fromAddr, body, mediaRef, mediaMime;
        final long ts; final boolean valid;
        String stableId = "";   // sender's app-level id from the payload, for cross-transport dedup
        Msg(String coinid, String fromPublicId, String fromHandle, String fromAddr, String body, String mediaRef, String mediaMime, long ts, boolean valid) {
            this.coinid = coinid; this.fromPublicId = fromPublicId; this.fromHandle = fromHandle; this.fromAddr = fromAddr;
            this.body = body; this.mediaRef = mediaRef; this.mediaMime = mediaMime; this.ts = ts; this.valid = valid;
        }
    }

    /** Seal {@code message} in-app to {@code toPublicId} (recipient msgpk) and send it as a
     *  coin to {@code toAddress}. */
    static void send(final NodeApi node, final LocalEcCryptoProvider crypto, final String toAddress,
                     final String toPublicId, final JSONObject message, final Cb cb) {
        if (!Args.isAddr(toAddress)) { cb.onFailed("recipient address is malformed — not sending"); return; }
        final String sealedHex;
        try {
            sealedHex = crypto.seal(toPublicId, message.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { cb.onFailed("encrypt failed — bad recipient key? " + e.getMessage()); return; }
        String state = "{\"99\":\"0x" + sealedHex + "\"}";
        node.cmd("send amount:" + AMOUNT + " address:" + toAddress + " state:" + state, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (j.optBoolean("status", false) || j.optBoolean("pending", false)) {
                    JSONObject r = j.optJSONObject("response");
                    cb.onSent(r != null ? r.optString("txpowid", "") : "");
                } else cb.onFailed(j.optString("error", "the node rejected the message"));
            }
            @Override public void onError(String m) { cb.onFailed(m); }
        });
    }

    /** Scan coins at {@code myAddress} and open each state[99] in-app; returns the DMs for me. */
    static void scan(final NodeApi node, final LocalEcCryptoProvider crypto, final String myAddress, final Scanned cb) {
        node.cmd("coins address:" + myAddress + " order:desc depth:200", new NodeApi.Cb() {   // bounded: address is public, guard against dust-flood overflow
            @Override public void onResult(JSONObject j) {
                List<Msg> out = new ArrayList<>();
                JSONArray arr = j.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i); if (c == null) continue;
                    String cid = c.optString("coinid", ""); String s99 = state99(c);
                    if (cid.isEmpty() || s99.isEmpty()) continue;
                    String hex = (s99.startsWith("0x") || s99.startsWith("0X")) ? s99.substring(2) : s99;
                    Opened o = crypto.open(hex);
                    if (o == null) continue;   // not for me / not a DM
                    try {
                        JSONObject m = new JSONObject(new String(o.plaintext, StandardCharsets.UTF_8));
                        Msg msg = new Msg(cid, o.fromPublicId, m.optString("from", "someone"), m.optString("addr", ""),
                                m.optString("body", ""), m.optString("media", ""), m.optString("mime", ""), m.optLong("ts", 0), o.valid);
                        msg.stableId = m.optString("id", "");
                        out.add(msg);
                    } catch (Exception ignored) {}
                }
                cb.onMessages(out);
            }
            @Override public void onError(String m) { cb.onMessages(new ArrayList<>()); }
        });
    }

    /** Build the message JSON to seal. {@code fromAddr} is my own tip address so the peer can reply. */
    static JSONObject compose(String fromHandle, String fromAddr, String body, String mediaRef, String mediaMime, long ts) {
        JSONObject m = new JSONObject();
        try {
            m.put("v", 1); m.put("from", fromHandle); m.put("addr", fromAddr == null ? "" : fromAddr);
            m.put("body", body == null ? "" : body); m.put("ts", ts);
            if (mediaRef != null && !mediaRef.isEmpty()) { m.put("media", mediaRef); m.put("mime", mediaMime == null ? "" : mediaMime); }
        } catch (Exception ignored) {}
        return m;
    }

    private static String state99(JSONObject coin) {
        JSONArray st = coin.optJSONArray("state");
        if (st != null) for (int k = 0; k < st.length(); k++) {
            JSONObject s = st.optJSONObject(k);
            if (s != null && s.optInt("port", -1) == 99) return s.optString("data", "");
        }
        return "";
    }

    private MinimaMail() {}
}
