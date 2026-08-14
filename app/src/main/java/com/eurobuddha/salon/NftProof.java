package com.eurobuddha.salon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * Trustless proof that a profile owner HOLDS an on-chain asset — the Axe S3 mechanism,
 * adapted for a public profile. No faking: a hosted list is never trusted; the VIEWER's
 * own node independently verifies each showcased item.
 *
 * Owner produces per-asset: {tokenid, coinid, coinproof (coinexport MMR blob), script,
 * publickey, sig} where sig = sign(coin's key, the owner's Salon tokenid) — binding the
 * coin holder to THIS profile so a stranger can't reuse someone else's valid proof.
 *
 * Viewer verifies (all on their own node): coincheck(coinproof) → the coin exists,
 * unspent, holds tokenid X; runscript(script) → that script hashes to the coin's address
 * (so `publickey` really controls it); verify(salonTokenid, publickey, sig) → the holder
 * signed THIS profile's id. All pass ⇒ "verified holding". Live: if the owner sells the
 * asset the coin is spent and coincheck fails, so the badge vanishes.
 *
 * v1 works for assets held at a plain wallet address (script RETURN SIGNEDBY(pk)) —
 * standard NFTs/tokens incl. signed identity tokens. (StateNFT collection items sit at a
 * covenant script, not SIGNEDBY(holder), so they're out of scope here.)
 */
final class NftProof {

    interface Gen { void ok(JSONObject item); void fail(String message); }
    interface Verify { void result(boolean verified, String reason); }

    /** bindHex = hex of the owner's Salon tokenid (identity to bind the holding to). */
    static void generate(final NodeApi node, final String tokenid, final String bindHex, final Gen cb) {
        node.cmd("coins relevant:true sendable:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject r) {
                JSONArray arr = r.optJSONArray("response");
                if (arr == null || arr.length() == 0) { cb.fail("You don't hold a spendable coin of this asset."); return; }
                JSONObject coin = arr.optJSONObject(0);
                final String coinid = coin.optString("coinid", ""), address = coin.optString("address", "");
                if (coinid.isEmpty() || address.isEmpty()) { cb.fail("coin has no id/address"); return; }
                node.cmd("coinexport coinid:" + coinid, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject e) {
                        JSONObject er = e.optJSONObject("response");
                        final String proof = er == null ? "" : er.optString("data", "");
                        if (proof.isEmpty()) { cb.fail("could not export coin proof"); return; }
                        node.cmd("scripts address:" + address, new NodeApi.Cb() {
                            @Override public void onResult(JSONObject s) {
                                JSONObject sr = firstObj(s.opt("response"));
                                final String script = sr == null ? "" : sr.optString("script", "");
                                final String pk = sr == null ? "" : sr.optString("publickey", "");
                                if (script.isEmpty() || pk.isEmpty()) { cb.fail("address is not a simple wallet address (can't prove holding)"); return; }
                                node.cmd("sign publickey:" + pk + " data:" + bindHex, new NodeApi.Cb() {
                                    @Override public void onResult(JSONObject sg) {
                                        if (!sg.optBoolean("status", false)) { cb.fail("signing failed (is your node unlocked?)"); return; }
                                        String sig = sg.opt("response") instanceof String ? sg.optString("response") : "";
                                        if (sig.isEmpty()) { cb.fail("no signature returned"); return; }
                                        try {
                                            JSONObject item = new JSONObject();
                                            item.put("tokenid", tokenid); item.put("coinid", coinid);
                                            item.put("coinproof", proof); item.put("script", script);
                                            item.put("publickey", pk); item.put("sig", sig);
                                            cb.ok(item);
                                        } catch (Exception ex) { cb.fail(ex.getMessage()); }
                                    }
                                    @Override public void onError(String m) { cb.fail(m); }
                                });
                            }
                            @Override public void onError(String m) { cb.fail(m); }
                        });
                    }
                    @Override public void onError(String m) { cb.fail(m); }
                });
            }
            @Override public void onError(String m) { cb.fail(m); }
        });
    }

    /** Verify a showcase item against {@code bindHex} (hex of the viewed profile's tokenid). */
    static void verify(final NodeApi node, final JSONObject item, final String bindHex, final Verify cb) {
        final String tokenid = item.optString("tokenid"), proof = item.optString("coinproof"),
                script = item.optString("script"), pk = item.optString("publickey"), sig = item.optString("sig");
        if (proof.isEmpty() || script.isEmpty() || pk.isEmpty() || sig.isEmpty()) { cb.result(false, "incomplete proof"); return; }
        node.cmd("coincheck data:" + proof, new NodeApi.Cb() {
            @Override public void onResult(JSONObject c) {
                JSONObject cr = c.optJSONObject("response");
                JSONObject coin = cr == null ? null : cr.optJSONObject("coin");
                if (cr == null || !cr.optBoolean("valid", false) || coin == null || !tokenid.equalsIgnoreCase(coin.optString("tokenid"))) { cb.result(false, "coin spent, not found, or wrong token"); return; }
                final String coinAddr = coin.optString("address", "");
                // the named pubkey must appear in the script that controls the coin
                if (!script.toLowerCase().contains(pk.toLowerCase().replaceFirst("^0x", ""))) { cb.result(false, "key doesn't control the coin"); return; }
                node.cmd("runscript script:\"" + script + "\"", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject rs) {
                        JSONObject rr = rs.optJSONObject("response");
                        JSONObject clean = rr == null ? null : rr.optJSONObject("clean");
                        String computed = clean == null ? "" : clean.optString("address", "");
                        if (computed.isEmpty() || !computed.equalsIgnoreCase(coinAddr)) { cb.result(false, "script/address mismatch"); return; }
                        node.cmd("verify data:" + bindHex + " publickey:" + pk + " signature:" + sig, new NodeApi.Cb() {
                            @Override public void onResult(JSONObject v) { boolean ok = v.optBoolean("status", false); cb.result(ok, ok ? "verified holding" : "signature not valid for this profile"); }
                            @Override public void onError(String m) { cb.result(false, "verify call failed"); }
                        });
                    }
                    @Override public void onError(String m) { cb.result(false, "script check failed"); }
                });
            }
            @Override public void onError(String m) { cb.result(false, "coin check failed (node busy?)"); }
        });
    }

    static String hexOf(String s) {
        StringBuilder b = new StringBuilder("0x");
        for (byte x : (s == null ? "" : s).getBytes(StandardCharsets.UTF_8)) b.append(String.format("%02X", x));
        return b.toString();
    }

    private static JSONObject firstObj(Object resp) {
        if (resp instanceof JSONObject) return (JSONObject) resp;
        if (resp instanceof JSONArray && ((JSONArray) resp).length() > 0) return ((JSONArray) resp).optJSONObject(0);
        return null;
    }

    private NftProof() {}
}
