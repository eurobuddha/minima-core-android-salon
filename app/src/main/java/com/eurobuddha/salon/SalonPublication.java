package com.eurobuddha.salon;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A durable submission guard plus read-only confirmation, using Salon's registry query.
 * Matching an existing fresh pointer is enough: the hosted page itself is mutable.
 * A timeout NEVER grants permission to send another announcement. */
final class SalonPublication {
    enum Stage { CHECKING, ANNOUNCING, WAITING, CONFIRMED, UNKNOWN, FAILED, REJECTED }
    private final SharedPreferences journal;
    private final String token, url, handle;
    private final NodeApi node;
    private final BooleanSupplier current;
    private final Consumer<Stage> listener;
    private final Handler timer = new Handler(Looper.getMainLooper());
    private boolean stopped, active;
    private int reads;

    SalonPublication(Context context, NodeApi node, String token, String url, String handle,
            BooleanSupplier current, Consumer<Stage> listener) {
        journal = prefs(context); this.node=node; this.token=token; this.url=url; this.handle=handle;
        this.current=current; this.listener=listener;
    }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("salon_publication", Context.MODE_PRIVATE); }
    static boolean pending(Context c, String token) { return prefs(c).contains(token); }
    static String pendingUrl(Context c, String token) { return prefs(c).getString(token, ""); }
    boolean active() { return active && !stopped; }
    void stop() { stopped=true; active=false; timer.removeCallbacksAndMessages(null); }
    private boolean valid() { if (stopped) return false; if (!current.getAsBoolean()) { stop(); return false; } return true; }
    private void emit(Stage stage) { if (valid()) listener.accept(stage); }
    void start(boolean allowSubmit) {
        if (active || stopped) return;
        active=true; reads=0; emit(Stage.CHECKING);
        check(allowSubmit && !journal.contains(token));
    }
    private void check(boolean allowSubmit) {
        if (!valid()) return;
        try { node.cmd("coins address:" + SalonRegistry.SALON_ADDRESS + " order:desc depth:1000", new NodeApi.Cb() {
            boolean answered;
            private boolean take() { if (answered || !valid()) return false; answered=true; return true; }
            public void onResult(JSONObject reply) {
                if (!take()) return;
                try {
                    if (reply == null || !reply.optBoolean("status") || !(reply.opt("response") instanceof JSONArray)) throw new IllegalArgumentException();
                    if (listed(reply.getJSONArray("response"), token, url)) {
                        if (!journal.edit().remove(token).commit()) { finish(Stage.UNKNOWN); return; }
                        finish(Stage.CONFIRMED);
                    } else if (allowSubmit) submit();
                    else again(false);
                } catch (Exception unavailable) { again(allowSubmit); }
            }
            public void onError(String ignored) { if (take()) again(allowSubmit); }
        }); } catch(RuntimeException unavailable) { again(allowSubmit); }
    }
    static boolean listed(JSONArray coins, String token, String url) {
        for (int i=0; i<coins.length(); i++) {
            JSONObject coin=coins.optJSONObject(i);
            if (coin==null || coin.optBoolean("spent") || !"0x00".equalsIgnoreCase(coin.optString("tokenid"))) continue;
            // Reuse the proven state parser: nodes return both object and port-array forms.
            if (token.equalsIgnoreCase(NftProof.state(coin,0)) && SalonRegistry.hex(url).equalsIgnoreCase(NftProof.state(coin,1))) return true;
        }
        return false;
    }
    private void submit() {
        if (!valid()) return;
        // Same save-before-post rule as Salon identity claims / Atelier transfers.
        if (!journal.edit().putString(token,url).commit()) { journal.edit().remove(token).commit();finish(Stage.FAILED); return; }
        emit(Stage.ANNOUNCING);
        try { SalonRegistry.announce(node,token,url,handle,new SalonRegistry.Announced() {
            boolean answered;
            public void rejected(String detail) {
                if(answered || !valid()) return;answered=true;
                if(!journal.edit().remove(token).commit()) { finish(Stage.UNKNOWN);return; }
                finish(Stage.REJECTED);
            }
            public void done(boolean ok, String detail) {
                if (answered || !valid()) return; answered=true;
                // A lost reply can hide a successful send. In either case only read again.
                emit(Stage.WAITING); check(false);
            }
        }); } catch(RuntimeException unavailable) { finish(Stage.UNKNOWN); }
    }
    private void again(boolean allowSubmit) {
        if (!valid()) return;
        if (++reads >= 20) { finish(Stage.UNKNOWN); return; }
        emit(journal.contains(token)?Stage.WAITING:Stage.CHECKING); timer.postDelayed(()->check(allowSubmit),15000);
    }
    private void finish(Stage stage) { active=false; emit(stage); }
}
