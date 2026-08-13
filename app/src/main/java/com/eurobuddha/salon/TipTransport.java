package com.eurobuddha.salon;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Native-value tips. A tip is a real Minima {@code send} of MINIMA or mxUSD to the
 * recipient's published tip address, with the sender's handle + an optional note
 * stamped into coin state (state[1]=handle, state[2]=note) so the recipient's app
 * can show "X tipped you". Mirrors apks/atomix/.../comms/CommsTransport.sendPayment;
 * accepts on status||pending and never gates on istransaction (async mining).
 */
final class TipTransport {

    static final String MINIMA = "0x00";
    static final String MXUSD  = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    interface Cb { void onSent(String txpowid); void onFailed(String message); }

    static void tip(NodeApi node, String toAddress, String amount, String tokenid,
                    String fromHandle, String note, Cb cb) {
        try {
            JSONObject state = new JSONObject();
            state.put("1", "0x" + Hex.to((fromHandle == null ? "" : fromHandle).getBytes(StandardCharsets.UTF_8)));
            if (note != null && !note.isEmpty()) state.put("2", "0x" + Hex.to(note.getBytes(StandardCharsets.UTF_8)));
            String cmd = "send amount:" + amount + " address:" + toAddress + " tokenid:" + tokenid + " state:" + state;
            node.cmd(cmd, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    if (j.optBoolean("status", false) || j.optBoolean("pending", false)) {
                        JSONObject r = j.optJSONObject("response");
                        cb.onSent(r != null ? r.optString("txpowid", "") : "");
                    } else {
                        cb.onFailed(j.optString("error", "the node rejected the tip"));
                    }
                }
                @Override public void onError(String m) { cb.onFailed(m); }
            });
        } catch (Exception e) { cb.onFailed(e.getMessage()); }
    }

    /** "MINIMA" | "mxUSD" for a tokenid. */
    static String label(String tokenid) { return MXUSD.equalsIgnoreCase(tokenid) ? "mxUSD" : "MINIMA"; }

    private TipTransport() {}
}
