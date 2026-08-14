package com.eurobuddha.salon;

import java.util.regex.Pattern;

/**
 * Validators for values that get concatenated into space-delimited Minima node commands
 * ({@code send amount:.. address:.. tokenid:.. state:..}). Minima parses commands as
 * {@code param:value} split on whitespace, so ANY externally-sourced value — a tip/DM
 * address read from a stranger's hosted profile.json, a tokenid, an amount — that contains
 * whitespace or command metacharacters can inject extra parameters (e.g. a second
 * {@code address:}, a {@code burn:}) and drain funds. Everything remote is checked here
 * before it can reach a command string; on failure the caller refuses to send.
 */
final class Args {

    // 0x-hex (covers 0x00 = MINIMA and 32-byte ids/addresses) OR an Mx-address.
    private static final Pattern ADDR = Pattern.compile("^(0x[0-9A-Fa-f]{2,80}|Mx[0-9A-Za-z]{20,120})$");
    private static final Pattern HEX  = Pattern.compile("^0x[0-9A-Fa-f]{2,80}$");
    private static final Pattern DEC  = Pattern.compile("^[0-9]{1,20}(\\.[0-9]{1,20})?$");

    /** A Minima receive address (0x… or Mx…), no whitespace/metacharacters. */
    static boolean isAddr(String s) { return s != null && ADDR.matcher(s).matches(); }

    /** A 0x-hex token id (incl. 0x00). */
    static boolean isTokenId(String s) { return s != null && HEX.matcher(s).matches(); }

    /** A plain decimal amount — no exponent, sign, NaN, or Infinity. */
    static boolean isDecimal(String s) { return s != null && DEC.matcher(s).matches(); }

    /** True if the free text is safe to embed inside a JSON metadata value passed to a
     *  command (rejects command/JSON-structure breakers; quotes are JSON-escaped upstream). */
    static boolean isSafeMeta(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{' || c == '}' || c == '\n' || c == '\r' || c == '\t' || c < 0x20) return false;
        }
        return true;
    }

    private Args() {}
}
