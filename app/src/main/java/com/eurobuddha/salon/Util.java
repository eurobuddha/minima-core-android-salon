package com.eurobuddha.salon;

import org.json.JSONObject;

import java.net.URLEncoder;

public final class Util {
    public static final String MINIMA_TOKENID = "0x00";

    private Util() {}

    public static boolean isValidAddress(String a) {
        return a != null && a.matches("^(0x[0-9a-fA-F]{64}|Mx[A-Za-z0-9]{40,118})$");
    }

    public static String shorten(String s) {
        if (s == null) return "";
        if (s.length() <= 18) return s;
        return s.substring(0, 8) + ".." + s.substring(s.length() - 6);
    }

    public static String tokenName(Object token) {
        if (token instanceof String) return (String) token;
        if (token instanceof JSONObject) {
            Object name = ((JSONObject) token).opt("name");
            if (name instanceof JSONObject) return ((JSONObject) name).optString("name", "Token");
            if (name instanceof String && !((String) name).isEmpty()) return (String) name;
        }
        return "Token";
    }

    /** URLs ride the tokencreate command line outside JSON — no spaces, quotes or semicolons. */
    public static boolean validCmdUrl(String u) {
        if (u == null || u.isEmpty()) return true;
        return !u.contains(" ") && !u.contains("\"") && !u.contains("'") && !u.contains(";");
    }

    public static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    /** Compact relative time ("now", "5m", "3h", "2d", "4w", then "12 Mar"). Accepts a
     *  timestamp in seconds or milliseconds. Empty for a missing/zero timestamp. */
    public static String ago(long ts) {
        if (ts <= 0) return "";
        long t = ts < 1_000_000_000_000L ? ts * 1000L : ts;   // seconds → ms
        long d = System.currentTimeMillis() - t; if (d < 0) d = 0;
        long s = d / 1000, m = s / 60, h = m / 60, days = h / 24, w = days / 7;
        if (s < 60) return "now";
        if (m < 60) return m + "m";
        if (h < 24) return h + "h";
        if (days < 7) return days + "d";
        if (w < 5) return w + "w";
        return new java.text.SimpleDateFormat("d MMM", java.util.Locale.US).format(new java.util.Date(t));
    }
}
