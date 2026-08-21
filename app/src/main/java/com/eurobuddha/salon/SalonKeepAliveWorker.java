package com.eurobuddha.salon;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Background beacon keep-alive: runs one {@link SalonRegistry#keepAlive} pass while the app is
 * CLOSED, so the Salon stays on the square past the ~14h beacon fade instead of dropping off
 * Discovery until the next manual open (the failure that hid the S23, 2026-08-18).
 *
 * Two triggers, both funnelled through the same 4h due-clock:
 *  - {@link SalonNotifyReceiver} enqueues a one-shot on the node's per-block NEWBLOCK broadcast
 *    (delivered even while our process is dead — the primary trigger, no permissions needed);
 *  - a 6h periodic fallback (scheduled by MainActivity) covers NEWBLOCK ever going quiet.
 *
 * Deliberately NOT a foreground service: openly documents the Android 14/15 dataSync trap
 * (~6h/day budget then ForegroundServiceDidNotStop crash-loop), and Salon's cadence is hours,
 * not pandapools' 15-minute covenant deadlines. If Samsung Doze still starves this, the
 * escalation path is pandapools' exact-alarm + specialUse-FGS stack.
 *
 * Accepted limitation: App info -> Force stop puts the app in stopped state — Android drops
 * both the NEWBLOCK broadcast and scheduled jobs until the next manual launch. Nothing
 * survives that (pandapools' stack included). Swiping from recents is NOT force-stop.
 *
 * Threading: doWork() runs on a WorkManager thread; every NodeApi callback lands on the main
 * looper, so the pass is driven there while this thread blocks on a latch. NodeApi must be
 * built with the APPLICATION context (a receiver context throws on registerReceiver); pairing
 * uids persist in the SDK's own prefs, and a successful command reply re-marks enabled, so no
 * pairing wait is needed — a dead node just means two 30s scan timeouts and an empty pass.
 */
public class SalonKeepAliveWorker extends Worker {

    static final String TAG = "SalonKeepAlive";
    static final String WORK_ONESHOT = "salon-keepalive";
    static final String WORK_PERIODIC = "salon-keepalive-periodic";
    /** Minimum gap between passes (foreground or background — one shared clock). The beacon
     *  only actually re-posts once it has faded past FRESH_DEPTH (~14h), so passes are cheap. */
    static final long DUE_MS = 4 * 60 * 60 * 1000L;
    /** Background posts per pass: 2 scans (30s timeout each) + 3 sends (180s worst case each)
     *  stays inside WorkManager's 10-minute hard stop. Foreground keeps the full cap of 6. */
    private static final int MAX_BG_POSTS = 3;
    private static final long LATCH_SECONDS = 8 * 60;

    private static final String PREFS = "salon_keepalive";
    private static final String KEY_LAST = "lastPassMs";

    public SalonKeepAliveWorker(@NonNull Context ctx, @NonNull WorkerParameters params) { super(ctx, params); }

    static long lastPass(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L); }

    /** Persisted (not in-memory): the enqueuing receiver, the worker, and the Activity may all
     *  live in different process incarnations and must share one throttle clock. */
    static void markPass(Context c) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply(); }

    /** Settings toggle — absent means ON, so existing identities get keep-alive on update. */
    static boolean bgEnabled(Context c) { return !"off".equals(SalonStore.get(c, "keepalivebg")); }

    static boolean due(Context c) { return System.currentTimeMillis() - lastPass(c) >= DUE_MS; }

    @NonNull @Override public Result doWork() {
        Context ctx = getApplicationContext();
        if (MainActivity.FOREGROUND) { android.util.Log.d(TAG, "skip: foreground"); return Result.success(); }
        if (!bgEnabled(ctx) || !SalonStore.hasIdentity(ctx)) { android.util.Log.d(TAG, "skip: disabled or no identity"); return Result.success(); }
        if (!due(ctx)) { android.util.Log.d(TAG, "skip: not due (last pass " + ((System.currentTimeMillis() - lastPass(ctx)) / 60000) + "m ago)"); return Result.success(); }
        NodeApi node = new NodeApi(ctx, enabled -> {});
        CountDownLatch done = new CountDownLatch(1);
        try {
            SalonRegistry.keepAlive(node, SalonStore.get(ctx, "tokenid"), SalonStore.get(ctx, "profileUrl"),
                    SalonStore.get(ctx, "handle"), SalonStore.follows(ctx), MAX_BG_POSTS,
                    n -> { android.util.Log.d(TAG, "pass done, posted " + n); done.countDown(); });
            if (!done.await(LATCH_SECONDS, TimeUnit.SECONDS))
                android.util.Log.w(TAG, "pass timed out after " + LATCH_SECONDS + "s (node silent?)");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "pass failed (contained)", t);
        } finally {
            // Mark on completion, never at enqueue — a Doze-deferred job that never ran must
            // not suppress future attempts.
            markPass(ctx);
            node.onDestroy();
        }
        return Result.success();   // never retry-storm; the next block/period re-checks anyway
    }
}
