package com.eurobuddha.salon;

import android.content.Context;
import android.content.SharedPreferences;

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
}
