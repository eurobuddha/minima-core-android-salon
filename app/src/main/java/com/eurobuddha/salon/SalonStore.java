package com.eurobuddha.salon;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Local state for The Salon: the user's own identity + profile draft, kept in a
 * private SharedPreferences file (backup-excluded, like HostingStore). The
 * authoritative identity is the on-chain signed token; the profile CONTENT is the
 * hosted profile.json. This is just the device-side cache/draft of both.
 *
 * identity JSON shape:
 *   { handle, name, bio, avatar, banner, tokenid, profileUrl, webvalidate }
 */
final class SalonStore {

    private static final String PREFS = "salon_identity";
    private static final String KEY = "me";

    private SalonStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONObject me(Context c) {
        try {
            String s = prefs(c).getString(KEY, "");
            return s.isEmpty() ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) { return new JSONObject(); }
    }

    static void save(Context c, JSONObject me) {
        prefs(c).edit().putString(KEY, me.toString()).apply();
    }

    static boolean hasIdentity(Context c) {
        JSONObject m = me(c);
        return m.optString("tokenid", "").length() > 0 && m.optString("handle", "").length() > 0;
    }

    static String get(Context c, String k) { return me(c).optString(k, ""); }

    static void put(Context c, String k, String v) {
        JSONObject m = me(c);
        try { m.put(k, v == null ? "" : v); } catch (Exception ignored) {}
        save(c, m);
    }

    /* ---- rich content arrays (links / gallery / posts) live inside "me" ---- */

    static JSONArray arr(Context c, String k) {
        JSONArray a = me(c).optJSONArray(k);
        return a == null ? new JSONArray() : a;
    }

    static void setArr(Context c, String k, JSONArray a) {
        JSONObject m = me(c);
        try { m.put(k, a == null ? new JSONArray() : a); } catch (Exception ignored) {}
        save(c, m);
    }

    /* ---- follows: a device-side list of {tokenid, handle, url} ---- */

    static JSONArray follows(Context c) {
        return arr(c, "follows");
    }

    static boolean isFollowing(Context c, String tokenid) {
        JSONArray f = follows(c);
        for (int i = 0; i < f.length(); i++) {
            JSONObject o = f.optJSONObject(i);
            if (o != null && tokenid.equals(o.optString("tokenid"))) return true;
        }
        return false;
    }

    static void follow(Context c, String tokenid, String handle, String url) {
        if (tokenid == null || tokenid.isEmpty() || isFollowing(c, tokenid)) return;
        JSONArray f = follows(c);
        try {
            JSONObject o = new JSONObject();
            o.put("tokenid", tokenid); o.put("handle", handle == null ? "" : handle); o.put("url", url == null ? "" : url);
            f.put(o);
        } catch (Exception ignored) {}
        setArr(c, "follows", f);
    }

    static void unfollow(Context c, String tokenid) {
        JSONArray f = follows(c), next = new JSONArray();
        for (int i = 0; i < f.length(); i++) {
            JSONObject o = f.optJSONObject(i);
            if (o != null && !tokenid.equals(o.optString("tokenid"))) next.put(o);
        }
        setArr(c, "follows", next);
    }
}
