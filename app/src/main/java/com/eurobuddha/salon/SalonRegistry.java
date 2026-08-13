package com.eurobuddha.salon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The town square — a well-known shared chain address every Salon posts a pointer
 * to. To ANNOUNCE, send a dust coin to SALON_ADDRESS whose state carries
 * {tokenid, profileURL, handle}. To BROWSE, list every coin at that address and
 * read its state — globally readable on any node, no server, no gatekeeper
 * (the family directory idiom: dust coin + data in coin state, queried by
 * `coins address:`). Content stays editable because the pointer is stable but the
 * profile.json it points at is a file its owner hosts and overwrites freely.
 */
final class SalonRegistry {

    static final String SALON_ADDRESS = "0x53414C4F4E";   // "SALON"
    private static final String DUST = "0.0000000001";     // tiny announce coin

    private SalonRegistry() {}

    interface Announced { void done(boolean ok, String message); }
    interface Listed { void done(List<Entry> entries); }

    static final class Entry {
        final String tokenid, url, handle;
        Entry(String tokenid, String url, String handle) { this.tokenid = tokenid; this.url = url; this.handle = handle; }
    }

    /** Post/refresh this Salon on the square. url + handle are hex-encoded so
     *  arbitrary text survives the command + state round-trip cleanly. */
    static void announce(NodeApi node, String tokenid, String url, String handle, Announced cb) {
        String state = "{\"0\":\"" + tokenid + "\",\"1\":\"" + hex(url) + "\",\"2\":\"" + hex(handle) + "\"}";
        node.cmd("send address:" + SALON_ADDRESS + " amount:" + DUST + " state:" + state, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (json.optBoolean("status", false)) cb.done(true, "Published to the Salon.");
                else cb.done(false, json.optString("error", "publish failed"));
            }
            @Override public void onError(String m) { cb.done(false, m); }
        });
    }

    /** Every Salon on the square, newest-first, deduped by tokenid (latest wins). */
    static void list(NodeApi node, Listed cb) {
        node.cmd("coins address:" + SALON_ADDRESS + " order:desc", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                List<Entry> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                JSONArray arr = json.optJSONArray("response");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c == null) continue;
                        String tid = "", urlH = "", hH = "";
                        JSONArray st = c.optJSONArray("state");
                        if (st != null) {
                            for (int k = 0; k < st.length(); k++) {
                                JSONObject s = st.optJSONObject(k);
                                if (s == null) continue;
                                int p = s.optInt("port", -1);
                                String d = s.optString("data", "");
                                if (p == 0) tid = d; else if (p == 1) urlH = d; else if (p == 2) hH = d;
                            }
                        }
                        if (tid.isEmpty() || seen.contains(tid)) continue;
                        seen.add(tid);
                        out.add(new Entry(tid, unhex(urlH), unhex(hH)));
                    }
                }
                cb.done(out);
            }
            @Override public void onError(String m) { cb.done(new ArrayList<>()); }
        });
    }

    static String hex(String s) {
        if (s == null) return "0x";
        StringBuilder b = new StringBuilder("0x");
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) b.append(String.format("%02X", x));
        return b.toString();
    }

    static String unhex(String h) {
        if (h == null) return "";
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
        if (h.length() % 2 != 0) return "";
        try {
            byte[] out = new byte[h.length() / 2];
            for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }
}
