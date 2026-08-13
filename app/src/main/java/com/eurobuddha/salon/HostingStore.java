package com.eurobuddha.salon;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Profile CRUD on the app's SharedPreferences (LocalStore pattern, kept
 *  separate so mint bookkeeping and hosting config never contend). Secret
 *  fields arrive here already Crypt-wrapped by the editor UI. */
final class HostingStore {

    private static final String KEY = "hosting_profiles";
    /** Secret field names per backend — wrapped at rest, redacted in logs. */
    static final String[] SECRET_FIELDS = { "password", "privateKey", "jwt", "token" };

    private HostingStore() {}

    /** Own prefs file (NOT statenft_suite) so backup rules can exclude the
     *  credentials without dropping collection state. */
    static final String PREFS = "statenft_hosting";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<Hosting.Profile> list(Context c) {
        List<Hosting.Profile> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(new Hosting.Profile(o));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static void upsert(Context c, Hosting.Profile p) {
        List<Hosting.Profile> all = list(c);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(p.id())) { all.set(i, p); replaced = true; break; }
        }
        if (!replaced) all.add(p);
        save(c, all);
    }

    static void remove(Context c, String id) {
        List<Hosting.Profile> all = list(c);
        List<Hosting.Profile> next = new ArrayList<>();
        for (Hosting.Profile p : all) if (!p.id().equals(id)) next.add(p);
        save(c, next);
    }

    static void setDefault(Context c, String id) {
        List<Hosting.Profile> all = list(c);
        for (Hosting.Profile p : all) Hosting.put(p.j, "def", p.id().equals(id));
        save(c, all);
    }

    static Hosting.Profile getDefault(Context c) {
        List<Hosting.Profile> all = list(c);
        for (Hosting.Profile p : all) if (p.isDefault()) return p;
        return all.isEmpty() ? null : all.get(0);
    }

    private static void save(Context c, List<Hosting.Profile> all) {
        JSONArray arr = new JSONArray();
        for (Hosting.Profile p : all) arr.put(p.j);
        prefs(c).edit().putString(KEY, arr.toString()).apply();
    }
}
