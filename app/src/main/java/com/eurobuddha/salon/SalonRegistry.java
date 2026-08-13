package com.eurobuddha.salon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The town square — a well-known shared chain address every Salon posts a pointer
 * to. To ANNOUNCE, send a dust coin to SALON_ADDRESS whose state carries
 * {tokenid, profileURL, handle}. To BROWSE, list every coin at that address and
 * read its state — globally readable on any node, no server, no gatekeeper
 * (the family directory idiom: dust coin + data in coin state, queried by
 * `coins address:`). Content stays editable because the pointer is stable but the
 * profile.json it points at is a file its owner hosts and overwrites freely.
 *
 * A dust beacon is only reliably discoverable for a bounded window (~a day) — the
 * chain prunes old coins. {@link #keepAlive} is the pandapools re-announce mesh
 * ported here: on app open, re-post any FADED pointer (your own and a few others
 * you can see), so the whole square stays alive collectively while anyone is
 * active. Freshness is decided by re-checking the chain, never a stored timestamp.
 */
final class SalonRegistry {

    static final String SALON_ADDRESS = "0x53414C4F4E";   // "SALON"
    private static final String DUST = "0.0000000001";     // tiny announce coin

    // Bounded windows (pandapools PoolCovenant parity). Invariant: SCAN > FRESH, so a
    // replacement beacon confirms into the discovery window before the old one leaves it.
    private static final int SCAN_DEPTH = 1500;   // discovery read window
    private static final int FRESH_DEPTH = 1000;  // still-discoverable window; older = faded
    private static final int MAX_REANNOUNCE_PER_RUN = 6;

    // Guards against double-posting an entry within one app session; a new process
    // starts empty, and only faded entries are ever added.
    private static final Set<String> ANNOUNCED = Collections.synchronizedSet(new HashSet<>());

    private SalonRegistry() {}

    interface Announced { void done(boolean ok, String message); }
    interface Listed { void done(List<Entry> entries); }
    interface Done { void done(int count); }

    static final class Entry {
        final String tokenid, url, handle;
        Entry(String tokenid, String url, String handle) { this.tokenid = tokenid; this.url = url; this.handle = handle; }
    }

    /** Post/refresh this Salon on the square. url + handle are hex-encoded so
     *  arbitrary text survives the command + state round-trip cleanly. A plain
     *  send (funding coins only) — no token signature, so re-posting a stranger's
     *  public pointer is safe. */
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

    /** Every Salon on the square (discovery window), newest-first, deduped by
     *  tokenid (latest wins). */
    static void list(NodeApi node, Listed cb) {
        scanEntries(node, SCAN_DEPTH, cb);
    }

    /**
     * Re-announce faded pointers (gossip mesh). Scans the fresh window to see what's
     * still discoverable, the discovery window for candidates, and re-posts any that
     * have faded — your own first, then follows, then others — shuffled within tiers
     * and capped, so many nodes don't all re-post the same entries.
     */
    static void keepAlive(NodeApi node, String myTokenid, String myUrl, String myHandle, JSONArray follows, Done cb) {
        scanEntries(node, FRESH_DEPTH, freshList -> {
            final Set<String> fresh = new HashSet<>();
            for (Entry e : freshList) fresh.add(e.tokenid);
            scanEntries(node, SCAN_DEPTH, candidates -> {
                // Own entry is always a candidate from local values, even if its beacon
                // has pruned away entirely and appears in neither scan.
                Map<String, Entry> byId = new LinkedHashMap<>();
                if (myTokenid != null && !myTokenid.isEmpty())
                    byId.put(myTokenid, new Entry(myTokenid, myUrl == null ? "" : myUrl, myHandle == null ? "" : myHandle));
                for (Entry e : candidates) if (!byId.containsKey(e.tokenid)) byId.put(e.tokenid, e);

                final Set<String> followIds = new HashSet<>();
                if (follows != null) for (int i = 0; i < follows.length(); i++) {
                    JSONObject f = follows.optJSONObject(i);
                    if (f != null) { String t = f.optString("tokenid", ""); if (!t.isEmpty()) followIds.add(t); }
                }

                List<Entry> mine = new ArrayList<>(), fol = new ArrayList<>(), others = new ArrayList<>();
                for (Entry e : byId.values()) {
                    if (fresh.contains(e.tokenid)) continue;       // still discoverable — leave it
                    if (ANNOUNCED.contains(e.tokenid)) continue;   // already re-posted this session
                    if (e.tokenid.equals(myTokenid)) mine.add(e);
                    else if (followIds.contains(e.tokenid)) fol.add(e);
                    else others.add(e);
                }
                Collections.shuffle(fol);
                Collections.shuffle(others);

                List<Entry> queue = new ArrayList<>();
                queue.addAll(mine); queue.addAll(fol); queue.addAll(others);
                if (queue.size() > MAX_REANNOUNCE_PER_RUN) queue = new ArrayList<>(queue.subList(0, MAX_REANNOUNCE_PER_RUN));
                reannounceNext(node, queue, 0, cb);
            });
        });
    }

    /** Post the queue one at a time (each send does a little PoW) so we never hammer the node. */
    private static void reannounceNext(NodeApi node, List<Entry> queue, int i, Done cb) {
        if (i >= queue.size()) { if (cb != null) cb.done(i); return; }
        Entry e = queue.get(i);
        ANNOUNCED.add(e.tokenid);
        announce(node, e.tokenid, e.url, e.handle, (ok, msg) -> reannounceNext(node, queue, i + 1, cb));
    }

    /** Read a depth-bounded window at the sentinel and parse it to deduped entries. */
    private static void scanEntries(NodeApi node, int depth, Listed cb) {
        node.cmd("coins address:" + SALON_ADDRESS + " order:desc depth:" + depth, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { cb.done(parseEntries(json)); }
            @Override public void onError(String m) { cb.done(new ArrayList<>()); }
        });
    }

    /** coins-response → entries, newest-first, deduped by tokenid (latest wins). */
    private static List<Entry> parseEntries(JSONObject json) {
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
        return out;
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
