package com.eurobuddha.salon;

import org.json.JSONArray;
import org.json.JSONObject;

/** Read the complete identity lookup before deciding that a mint is needed.
 * Uses StudioData's status/row checks and the core balance TokenMeta object parser. */
final class IdentityBalance {
    private IdentityBalance() {}
    static JSONObject find(JSONObject reply, String handle) {
        for (JSONObject row : all(reply))
            if (handle == null || handle.equals(row.optJSONObject("token").optString("handle"))) return row;
        return null;
    }

    /** Use the same complete lookup for adoption and spare-identity cleanup. */
    private static java.util.List<JSONObject> all(JSONObject reply) {
        if (reply == null || !Boolean.TRUE.equals(reply.opt("status"))) throw invalid();
        JSONArray rows = reply.optJSONArray("response");
        if (rows == null) throw invalid();
        java.util.List<JSONObject> found = new java.util.ArrayList<>();
        for (int i=0; i<rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !(row.opt("tokenid") instanceof String)
                    || !Args.isTokenId((String) row.opt("tokenid"))) throw invalid();
            Object token = row.opt("token");
            if (!(token instanceof String) && !(token instanceof JSONObject)) throw invalid();
            JSONObject meta = asObject(token);
            if (meta == null || !"1".equals(meta.optString("salon"))) continue;
            if (!(meta.opt("handle") instanceof String) || meta.optString("handle").isEmpty()) throw invalid();
            try {
                // Normalize serialized metadata without changing the reply.
                found.add(new JSONObject(row.toString()).put("token", meta));
            } catch (org.json.JSONException malformed) { throw invalid(); }
        }
        return found;
    }
    static java.util.List<String> spares(JSONObject reply, String keep) {
        if (!Args.isTokenId(keep) || new java.math.BigInteger(keep.substring(2), 16).signum() == 0) throw invalid();
        java.util.Map<String, String> ids = new java.util.LinkedHashMap<>();
        for (JSONObject row : all(reply)) {
            String id = row.optString("tokenid");
            if (new java.math.BigInteger(id.substring(2), 16).signum() == 0) throw invalid();
            if (!id.equalsIgnoreCase(keep)) ids.putIfAbsent(id.toLowerCase(java.util.Locale.ROOT), id);
        }
        return new java.util.ArrayList<>(ids.values());
    }

    private static JSONObject asObject(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return (JSONObject) value;
        String text = value.toString().trim();
        if (text.startsWith("{")) {
            try { return new JSONObject(text); }
            catch (org.json.JSONException ignored) { }
        }
        return null;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Identity lookup unavailable");
    }
}
