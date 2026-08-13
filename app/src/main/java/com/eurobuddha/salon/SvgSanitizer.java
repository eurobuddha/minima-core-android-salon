package com.eurobuddha.salon;

import java.util.regex.Pattern;

/**
 * Sanitize SVG source before it is sealed on-chain. Every renderer in the
 * family draws SVG via &lt;img&gt;/androidsvg (no script execution), but the
 * sealed record outlives today's renderers — strip active content so the
 * artifact is inert everywhere, forever.
 */
public final class SvgSanitizer {

    private static final Pattern SCRIPT = Pattern.compile("<script[\\s\\S]*?</script\\s*>|<script[^>]*/\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOREIGN = Pattern.compile("<foreignObject[\\s\\S]*?</foreignObject\\s*>|<foreignObject[^>]*/\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_ATTR = Pattern.compile("\\son[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JS_URL = Pattern.compile("(href|xlink:href)\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\2", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL_REF = Pattern.compile("(href|xlink:href)\\s*=\\s*([\"'])(?!#)[^\"']*\\2", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCTYPE_ENTITY = Pattern.compile("<!DOCTYPE[\\s\\S]*?>|<!ENTITY[\\s\\S]*?>", Pattern.CASE_INSENSITIVE);

    private SvgSanitizer() {}

    /** True when the payload looks like an SVG document. */
    public static boolean isSvg(String text) {
        if (text == null) return false;
        String t = text.trim().toLowerCase();
        return t.startsWith("<svg") || (t.startsWith("<?xml") && t.contains("<svg"));
    }

    /**
     * Returns inert SVG source, or null when the input is not an SVG.
     * Strips scripts, event handlers, foreignObject, doctype/entities and
     * every non-local href — #local references (gradients, defs) survive.
     */
    public static String sanitize(String svg) {
        if (!isSvg(svg)) return null;
        String s = svg;
        s = DOCTYPE_ENTITY.matcher(s).replaceAll("");
        s = SCRIPT.matcher(s).replaceAll("");
        s = FOREIGN.matcher(s).replaceAll("");
        s = EVENT_ATTR.matcher(s).replaceAll("");
        s = JS_URL.matcher(s).replaceAll("");
        s = EXTERNAL_REF.matcher(s).replaceAll("");
        return s.trim();
    }
}
