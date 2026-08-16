package com.eurobuddha.salon;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

/**
 * "Katalog" design system — Swiss auction catalogue.
 * Paper ground, hard ink rules, vermilion stamps, zero rounding,
 * offset block shadows, uppercase lot numbers.
 */
public final class Design {
    private static Typeface sSans, sMono;
    private static boolean sReducedMotion = false;

    private Design() {}

    public static void load(Context c) {
        if (sSans == null) { try { sSans = ResourcesCompat.getFont(c, R.font.inter); } catch (Exception ignored) {} }
        if (sMono == null) { try { sMono = ResourcesCompat.getFont(c, R.font.jetbrains_mono); } catch (Exception ignored) {} }
        try {
            sReducedMotion = Settings.Global.getFloat(c.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception ignored) {}
    }

    /* ---- palette ---- */
    public static int PAPER()  { return 0xFFF2F1EC; }   // ground
    public static int CARD()   { return 0xFFFCFBF7; }   // raised surface
    public static int INK()    { return 0xFF111111; }   // text + rules
    public static int DIM()    { return 0xFF4F4E49; }   // secondary text — ~7.3:1 on PAPER (was 6B6A64, too close to the greys at 12sp)
    public static int SOFT()   { return 0xFFDEDDD6; }   // tracks, idle rules
    public static int ACCENT() { return 0xFFE63312; }   // vermilion stamp
    public static int PLATE1() { return 0xFFF5D547; }   // yellow plate
    public static int PLATE2() { return 0xFF2B6CB0; }   // blue plate
    public static int WHITE()  { return 0xFFFFFFFF; }

    public static boolean reducedMotion() { return sReducedMotion; }

    /* ---- type ---- */
    public static Typeface sans()     { return sSans != null ? sSans : Typeface.SANS_SERIF; }
    public static Typeface sansBold() { return Typeface.create(sans(), Typeface.BOLD); }
    public static Typeface mono()     { return sMono != null ? sMono : Typeface.MONOSPACE; }
    public static Typeface monoBold() { return Typeface.create(mono(), Typeface.BOLD); }

    public static int dp(Context c, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /* ---- drawables ---- */
    public static GradientDrawable rect(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(0);
        return d;
    }

    public static GradientDrawable ruled(Context c, int fill, int strokeColor, float strokeDp) {
        GradientDrawable d = rect(fill);
        d.setStroke(Math.max(2, dp(c, Math.round(strokeDp))), strokeColor);
        return d;
    }

    public static GradientDrawable dashed(Context c, int fill, int strokeColor) {
        GradientDrawable d = rect(fill);
        d.setStroke(Math.max(2, dp(c, 1)), strokeColor, dp(c, 4), dp(c, 3));
        return d;
    }

    /** Card with the Katalog offset block shadow: ink slab peeking bottom-right. */
    public static Drawable blockShadow(Context c, int fill) {
        int off = dp(c, 3);
        LayerDrawable l = new LayerDrawable(new Drawable[]{ rect(INK()), ruled(c, fill, INK(), 1.5f) });
        l.setLayerInset(0, off, off, 0, 0);
        l.setLayerInset(1, 0, 0, off, off);
        return l;
    }

    public static Drawable ripple(Drawable base) {
        return new RippleDrawable(ColorStateList.valueOf(0x22111111), base, null);
    }

    /* ---- text builders ---- */
    public static TextView text(Context c, String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(tf);
        return t;
    }

    /** Display headline: heavy caps, tracked. */
    public static TextView display(Context c, String s, float sp) {
        TextView t = text(c, s == null ? "" : s.toUpperCase(), sp, INK(), sansBold());
        t.setLetterSpacing(0.08f);
        return t;
    }

    /** Vermilion lot label: LOT 007, № 2, etc. */
    public static TextView lot(Context c, String s) {
        TextView t = text(c, s == null ? "" : s.toUpperCase(), 10, ACCENT(), sansBold());
        t.setLetterSpacing(0.2f);
        return t;
    }

    /** Section kicker in ink caps. */
    public static TextView kicker(Context c, String s) {
        TextView t = text(c, s == null ? "" : s.toUpperCase(), 11, INK(), sansBold());
        t.setLetterSpacing(0.18f);
        return t;
    }

    public static TextView body(Context c, String s) { return text(c, s, 13.5f, INK(), sans()); }
    public static TextView note(Context c, String s) { return text(c, s, 12, DIM(), sans()); }

    /** Boxed mono hash chip, tap-to-copy affordance added by caller. */
    public static TextView hash(Context c, String s) {
        TextView t = text(c, s, 10.5f, INK(), mono());
        t.setBackground(ruled(c, CARD(), INK(), 1));
        int x = dp(c, 8), y = dp(c, 4);
        t.setPadding(x, y, x, y);
        return t;
    }

    /* ---- chips & pills ---- */
    public static TextView chip(Context c, String s, boolean on) {
        TextView t = text(c, s.toUpperCase(), 10, on ? PAPER() : INK(), sansBold());
        t.setLetterSpacing(0.1f);
        t.setGravity(Gravity.CENTER);
        int x = dp(c, 12), y = dp(c, 7);
        t.setPadding(x, y, x, y);
        t.setBackground(ripple(on ? rect(INK()) : ruled(c, 0x00000000, INK(), 1.5f)));
        t.setClickable(true);
        return t;
    }

    public static final int PILL_LIVE = 0, PILL_DONE = 1, PILL_MINE = 2, PILL_DIM = 3, PILL_ERR = 4;

    public static TextView pill(Context c, String s, int kind) {
        TextView t = text(c, s.toUpperCase(), 8.5f, 0, sansBold());
        t.setLetterSpacing(0.12f);
        int x = dp(c, 7), y = dp(c, 3);
        t.setPadding(x, y, x, y);
        switch (kind) {
            case PILL_LIVE: t.setTextColor(ACCENT()); t.setBackground(dashedAccent(c)); break;
            case PILL_DONE: t.setTextColor(PAPER());  t.setBackground(rect(INK())); break;
            case PILL_MINE: t.setTextColor(WHITE());  t.setBackground(rect(ACCENT())); break;
            case PILL_ERR:  t.setTextColor(WHITE());  t.setBackground(rect(ACCENT())); break;
            default:        t.setTextColor(DIM());    t.setBackground(dashed(c, 0x00000000, DIM())); break;
        }
        return t;
    }

    private static GradientDrawable dashedAccent(Context c) {
        GradientDrawable d = rect(0x00000000);
        d.setStroke(Math.max(2, dp(c, 1)), ACCENT(), dp(c, 4), dp(c, 3));
        return d;
    }

    /* ---- buttons ---- */
    public static TextView button(Context c, String label, boolean filled) {
        TextView b = text(c, label.toUpperCase(), 12, filled ? WHITE() : INK(), sansBold());
        b.setLetterSpacing(0.12f);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(filled ? blockShadow(c, ACCENT()) : ruled(c, 0x00000000, INK(), 1.5f)));
        b.setClickable(true);
        return b;
    }

    public static TextView inkButton(Context c, String label) {
        TextView b = text(c, label.toUpperCase(), 12, PAPER(), sansBold());
        b.setLetterSpacing(0.12f);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(rect(INK())));
        b.setClickable(true);
        return b;
    }

    /* ---- structural ---- */
    public static View rule(Context c, int hDp) {
        View v = new View(c);
        v.setBackgroundColor(INK());
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(2, dp(c, hDp))));
        return v;
    }

    public static View softRule(Context c) {
        View v = new View(c);
        v.setBackgroundColor(SOFT());
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1))));
        return v;
    }

    /** Square progress meter: soft track, vermilion fill. */
    public static LinearLayout meter(Context c, float pct) {
        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setBackgroundColor(SOFT());
        float p = Math.max(0f, Math.min(1f, pct));
        View fill = new View(c);
        fill.setBackgroundColor(ACCENT());
        outer.addView(fill, new LinearLayout.LayoutParams(0, -1, Math.max(0.001f, p)));
        View rest = new View(c);
        outer.addView(rest, new LinearLayout.LayoutParams(0, -1, Math.max(0.001f, 1f - p)));
        return outer;
    }

    public static void pressable(final View v) {
        if (reducedMotion()) return;
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().translationX(dp(view.getContext(), 1)).translationY(dp(view.getContext(), 1)).setDuration(60).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().translationX(0f).translationY(0f).setDuration(90).start();
                    break;
            }
            return false;
        });
    }
}
