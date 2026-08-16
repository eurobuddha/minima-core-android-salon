package com.eurobuddha.salon;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Salon — a decentralised, media-rich social page. Your identity is a 1/1
 * signed token; your page is a profile.json (with images, video and music, all
 * hosted URLs) on your own server, edited freely. A shared on-chain address is
 * the town square: post a pointer, browse everyone, follow, and pull a feed.
 */
public class MainActivity extends AppCompatActivity {

    private NodeApi node;
    private final ExecutorService io = Executors.newFixedThreadPool(3);

    // Persistent audio: the player and its docked mini-bar outlive screen changes.
    private MediaPlayer audio;
    private final Handler playback = new Handler(Looper.getMainLooper());
    private LinearLayout miniPlayer;
    private TextView miniTitle, miniPlay, miniTime;
    private SeekBar miniBar;
    private boolean miniSeeking = false;

    private enum Screen { FEED, DISCOVER, HOME, VIEW, EDIT, ONBOARD, SETTINGS, HOSTING, HOSTING_EDIT, MESSAGES, THREAD }
    private Screen screen = Screen.HOME;
    private String threadPeer = "";   // peer msgpk for the open THREAD screen
    // Reply state, TAGGED to the thread it belongs to (replyPeer) so a reply
    // started in one chat can never leak into another's composer/DM. Empty = not replying.
    private String replyBody = "", replyFrom = "", replyPeer = "";
    // In-progress compose text, preserved across the frequent thread re-renders
    // (inbound DMs / acks) so a half-typed message isn't wiped. Cleared on peer change / send.
    private String threadDraft = "";
    private int threadRenderedCount = -1;   // last rendered TOTAL count; auto-scroll only when it grows
    private static final int THREAD_WINDOW_STEP = 200;
    private int threadWindow = THREAD_WINDOW_STEP;   // how many recent messages to render; "Load earlier" grows it

    private LinearLayout appbar, navRow, body;
    private int mLastKb = 0;   // last real keyboard height, latched through spurious 0-insets
    private static final String XPORT_PREF = "xport_";   // per-contact transport override
    /** msgIds currently being transmitted — stops concurrent outbox triggers from
     *  dispatching the same message twice (a double coin-spend / double delivery). */
    private final java.util.Set<String> mInFlight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** coinid → last on-chain post attempt (ms). A `send` the node accepted keeps
     *  mining and only TIMES OUT (at NodeApi WRITE_TIMEOUT, 180s) — the coin is still
     *  live. Gating a chain re-post on the time since the LAST attempt (not compose)
     *  stops the outbox double-posting a dust coin that's still in flight. */
    private final java.util.Map<String, Long> mLastChainAttempt =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** One tip in flight at a time. Tips move REAL value and have no node-side
     *  idempotency, so guard the whole check-balance→send round-trip. */
    private volatile boolean mTipInFlight = false;
    private ScrollView scroll;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe;
    private FrameLayout rootFrame;
    private TextView nodeChip;
    private boolean nodeUp = false;
    private boolean wasNodeUp = false;      // to re-render node-gated screens on connect
    private String expandedNft = null;      // tokenid of the showcase holding currently expanded
    // Per-tokenid caches so a re-render (expand/collapse, NEWBLOCK) does NOT re-hammer
    // the node: each holding's verify outcome + live edition icon is resolved once per
    // session. "Verify again" forces a fresh check. Cleared on profile switch.
    private final java.util.Map<String, String> mVerifyText = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> mVerifyColor = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> mLiveIcon = new java.util.concurrent.ConcurrentHashMap<>();
    private String pubkey = "";
    private boolean adoptChecked = false;
    private boolean reannouncedThisSession = false;

    // Minima's provably-unspendable "RETURN FALSE" graveyard — burning a coin
    // here destroys it forever (same address Atelier's StateNft.buryCommands uses).
    private static final String GRAVEYARD = "0xABA005476D2B3CD7F251B9783E64C124C9670BB358695F04D91B2057BB64CB49";

    private Hosting.Profile hostEdit;
    private TextView claimBtn; private boolean claiming = false;
    private JSONObject viewProfile;        // profile being viewed (someone else)
    private SalonRegistry.Entry viewEntry; // registry entry for the viewed profile

    /* ---------------- lifecycle ---------------- */

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Maxima transport handshake: register (wakes it), subscribe our DM
        // channel, cache our addresses. Idempotent, fire-and-forget - the app
        // works fully without Maxima (on-chain DMs), this just upgrades it.
        MaximaLink.connect(this);

        NostrKeys.init(getApplicationContext());   // Blossom uploads sign off the io executor, no Context there
        // Warm the derived-pubkey cache off the main thread — first derivation does a
        // Keystore decrypt + EC scalar-mult, and the Blossom editor renders it inline.
        io.execute(() -> { try { NostrKeys.pubkeyHex(); } catch (Throwable ignored) { } });

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Design.PAPER());
        LinearLayout chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);

        appbar = new LinearLayout(this); appbar.setOrientation(LinearLayout.VERTICAL);
        appbar.setBackgroundColor(Design.PAPER());
        chrome.addView(appbar, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(10), dp(16), dp(28));
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        swipe = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this);
        swipe.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        swipe.setColorSchemeColors(Design.ACCENT());
        swipe.setOnRefreshListener(() -> { mProfileCache.clear(); mRegistryCache = null; render(); swipe.setRefreshing(false); });   // pull-to-refresh = fetch latest
        chrome.addView(swipe, new LinearLayout.LayoutParams(-1, 0, 1));

        buildMiniPlayer();
        chrome.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));

        navRow = new LinearLayout(this); navRow.setOrientation(LinearLayout.VERTICAL);
        navRow.setBackgroundColor(Design.PAPER());
        chrome.addView(navRow, new LinearLayout.LayoutParams(-1, -2));

        rootFrame.addView(chrome, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootFrame);

        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(getWindow(), rootFrame);
        wic.setAppearanceLightStatusBars(true); wic.setAppearanceLightNavigationBars(true);
        final LinearLayout chromeRef = chrome;
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeVis = insets.isVisible(WindowInsetsCompat.Type.ime());
            // The keyboard inset arrives correctly (e.g. 972) but is sometimes
            // immediately followed by a spurious ime.bottom=0 dispatch while the
            // keyboard is STILL up. Latch on the visibility flag: hold the last
            // real height through the spurious 0 so the UI doesn't drop back down.
            int kb;
            if (imeVis) {
                int now = Math.max(0, ime.bottom - sys.bottom);
                if (now > 0) mLastKb = now;
                kb = mLastKb;
            } else {
                kb = 0; mLastKb = 0;
            }
            appbar.setPadding(0, sys.top, 0, 0);
            navRow.setPadding(0, 0, 0, sys.bottom);
            // LIFT THE WHOLE UI above the keyboard. Padding the scroll's INTERIOR
            // didn't work: with edge-to-edge (setDecorFitsSystemWindows=false) the
            // window doesn't resize for the IME on Android 15, so the ScrollView's
            // on-screen bottom edge still sits UNDER the keyboard and the field
            // scrolls to a spot the keyboard covers. Padding chrome's bottom by the
            // keyboard height physically raises the scroll (and nav/mini-player)
            // above the keyboard; then the focused field is scrolled into view.
            chromeRef.setPadding(0, 0, 0, kb);
            scroll.setPadding(0, 0, 0, 0);
            if (kb > 0) {
                final View f = getCurrentFocus();
                if (f != null) {
                    f.post(() -> f.requestRectangleOnScreen(
                            new android.graphics.Rect(0, 0, f.getWidth(), f.getHeight()), false));
                }
            }
            return insets;
        });

        node = new NodeApi(this, enabled -> runOnUiThread(() -> { nodeUp = enabled; onNode(); }));
        startPairingRetry();   // re-broadcast REGISTER while unpaired (recovers if Salon starts before Minima Core, e.g. after reboot)
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
        if (SalonStore.hasIdentity(this)) screen = Screen.HOME;
        render();
        refreshUnread();   // seed the nav badge without a UI-thread DB query

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                switch (screen) {   // walk UP the logical stack instead of exiting the app
                    case THREAD:       go(Screen.MESSAGES); break;
                    case VIEW:         go(Screen.DISCOVER); break;
                    case EDIT:         go(Screen.HOME); break;
                    case HOSTING:      go(Screen.SETTINGS); break;
                    case HOSTING_EDIT: go(Screen.HOSTING); break;
                    default:           setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true);
                }
            }
        });
    }

    private void onNode() {
        if (nodeChip != null) nodeChip.setText(nodeUp ? "connected" : "no node");
        if (!nodeUp) startPairingRetry();   // keep re-registering after a disconnect
        if (nodeUp && pubkey.isEmpty()) fetchPubkey();
        if (nodeUp && !adoptChecked && !claiming && !SalonStore.hasIdentity(this)) {
            adoptChecked = true;
            node.cmd("balance", new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) { JSONObject t = findAnySalonToken(j); if (t != null) runOnUiThread(() -> adoptFromToken(t)); }
                @Override public void onError(String m) {}
            });
        }
        // One-time-per-session setup (relay TTL, mail key, first inbox scan).
        if (nodeUp && !reannouncedThisSession && SalonStore.hasIdentity(this)) {
            reannouncedThisSession = true;
            touchOwnRelay();   // refresh the 7-day TTL on any relay-hosted content
            ensureMailKey();
            ensureInboxAndScan();
        }
        // Gossip mesh: re-post any FADED pointer (own + a few others) so the square stays
        // alive. Must run REPEATEDLY (on every connect + on a foreground timer), not once —
        // a beacon prunes ~a day, so an app open once and left running has to keep renewing
        // it. keepAlive no-ops unless something has actually faded (on-chain check).
        if (nodeUp && SalonStore.hasIdentity(this)) reannounceNow();
        // The first render() runs at onCreate before the node connects; node-gated
        // content (the holdings showcase, the tip/message bar) was drawn in its
        // "no node" state. Re-render once, on the false->true transition, so it refreshes.
        if (nodeUp && !wasNodeUp && (screen == Screen.HOME || screen == Screen.VIEW || screen == Screen.DISCOVER || screen == Screen.MESSAGES)) render();
        wasNodeUp = nodeUp;
    }

    /** Scan the inbox (tips + DMs) — but only once the tip address is captured. On a fresh
     *  identity getaddress is async, so we scan INSIDE its callback (fixes the race where a
     *  new device never scanned because tipaddr was still empty). */
    private void ensureInboxAndScan() {
        String addr = SalonStore.get(this, "tipaddr");
        if (!addr.isEmpty()) { scanTips(); scanMail(); return; }
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject r) {
                JSONObject resp = r.optJSONObject("response");
                String a = resp == null ? "" : resp.optString("address", "");
                if (!a.isEmpty()) runOnUiThread(() -> { SalonStore.put(MainActivity.this, "tipaddr", a); scanTips(); scanMail(); });
            }
            @Override public void onError(String m) {}
        });
    }

    /** Derive our app-side messaging identity once (libsodium, no node/Maxima) and publish
     *  its public id as msgpk so others can seal DMs to us. */
    private void ensureMailKey() {
        if (!SalonStore.get(this, "msgpk").isEmpty()) return;
        io.execute(() -> {
            String pk = SalonComms.publicId(MainActivity.this);   // generates+stores the seed if absent
            runOnUiThread(() -> SalonStore.put(MainActivity.this, "msgpk", pk));
        });
    }

    /** Scan on-chain mail at our address; store new DMs + refresh the badge/thread. */
    private void scanMail() {
        final String addr = SalonStore.get(this, "tipaddr");
        if (addr.isEmpty()) return;
        MinimaMail.scan(node, SalonComms.crypto(this), addr, msgs -> io.execute(() -> {   // DB writes off the UI thread
            MailDb db = MailDb.get(MainActivity.this);
            int newCount = 0; String lastFrom = "", lastBody = "";
            for (MinimaMail.Msg m : msgs) {
                if (m.coinid.isEmpty()) continue;
                if (!m.valid) continue;   // anonymous seal — drop messages whose sender signature doesn't verify (anti-impersonation)
                String preview = !m.body.isEmpty() ? m.body : (!m.mediaRef.isEmpty() ? "📎 media" : "");
                // Dedup on the sender's stable app id when present (agrees with the
                // Maxima path + the NOTIFY intake), so one logical message is one
                // bubble regardless of which transport(s) carried it.
                String dedupId = m.stableId == null || m.stableId.isEmpty()
                        ? m.coinid : "id-" + m.fromPublicId + "-" + m.stableId;
                boolean isNew = db.insert(dedupId, m.fromPublicId, false, m.body, m.mediaRef, m.mediaMime, m.ts, m.valid);
                if (m.replyBody != null && !m.replyBody.isEmpty()) db.setReply(dedupId, m.replyBody, m.replyFrom);
                db.setMxAddr(m.fromPublicId, m.peerMx);   // learn their Maxima address from an on-chain DM too (sticky, no-op if absent)
                if (isNew) {
                    db.upsertContact(m.fromPublicId, m.fromHandle, "", m.fromAddr, preview, m.ts, !m.fromPublicId.equals(threadPeer));
                    newCount++; lastFrom = m.fromHandle; lastBody = preview;
                }
            }
            final int fc = newCount; final String lf = lastFrom, lb = lastBody; final int unread = db.totalUnread();
            runOnUiThread(() -> {
                unreadCache = unread;
                if (fc > 0) {
                    if (screen == Screen.MESSAGES || screen == Screen.THREAD) render();
                    else { buildNav(); toast("💬 " + (fc == 1 ? "@" + lf.replaceFirst("^@", "") + ": " + lb : fc + " new messages")); }
                } else buildNav();
            });
        }));
    }

    private int unreadCache = 0;
    /** Refresh the cached unread count off the UI thread and repaint the nav badge. */
    private void refreshUnread() {
        io.execute(() -> { int u = MailDb.get(MainActivity.this).totalUnread(); runOnUiThread(() -> { unreadCache = u; if (navRow != null) buildNav(); }); });
    }

    /** Detect inbound tips at our address and announce new ones. Baselines silently
     *  on first run so old coins don't spam; thereafter new coinids → "X tipped you". */
    private void scanTips() {
        final String addr = SalonStore.get(this, "tipaddr");
        if (addr.isEmpty()) return;
        node.cmd("coinnotify action:add address:" + addr, new NodeApi.Cb() { public void onResult(JSONObject j) {} public void onError(String m) {} });
        node.cmd("coins address:" + addr + " order:desc depth:200", new NodeApi.Cb() {   // bounded: the tip address is public and could be dust-flooded
            @Override public void onResult(JSONObject j) {
                final JSONArray arr = j.optJSONArray("response"); if (arr == null) return;
                runOnUiThread(() -> {
                    JSONArray seen = SalonStore.arr(MainActivity.this, "tipseen");
                    java.util.HashSet<String> seenSet = new java.util.HashSet<>();
                    for (int i = 0; i < seen.length(); i++) seenSet.add(seen.optString(i));
                    boolean baselined = "1".equals(SalonStore.get(MainActivity.this, "tipbaselined"));
                    java.util.List<String> fresh = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i); if (c == null) continue;
                        String cid = c.optString("coinid", ""); if (cid.isEmpty() || seenSet.contains(cid)) continue;
                        seenSet.add(cid); seen.put(cid);
                        if (baselined) fresh.add(describeTip(c));
                    }
                    SalonStore.setArr(MainActivity.this, "tipseen", capArr(seen, 500));
                    if (!baselined) SalonStore.put(MainActivity.this, "tipbaselined", "1");
                    for (String d : fresh) toast(d);
                });
            }
            @Override public void onError(String m) {}
        });
    }

    private String describeTip(JSONObject coin) {
        String amount = coin.optString("tokenamount", "");
        if (amount.isEmpty()) amount = coin.optString("amount", "?");
        String tokenid = coin.optString("tokenid", TipTransport.MINIMA);
        String from = "someone", note = "";
        JSONArray st = coin.optJSONArray("state");
        if (st != null) for (int k = 0; k < st.length(); k++) {
            JSONObject s = st.optJSONObject(k); if (s == null) continue;
            int p = s.optInt("port", -1); String v = SalonRegistry.unhex(s.optString("data", ""));
            if (p == 1 && !v.isEmpty()) from = v; else if (p == 2) note = v;
        }
        return "💰 @" + from.replaceFirst("^@", "") + " tipped you " + amount + " " + TipTransport.label(tokenid)
                + (note.isEmpty() ? "" : " — " + note);
    }

    private static boolean arrEmpty(JSONArray a) { return a == null || a.length() == 0; }
    private static boolean emptyProfile(JSONObject p) {
        return p.optString("about", "").isEmpty() && arrEmpty(p.optJSONArray("links"))
                && arrEmpty(p.optJSONArray("gallery")) && arrEmpty(p.optJSONArray("posts")) && arrEmpty(p.optJSONArray("nfts"));
    }

    /** Keep only the most-recent {@code max} entries of a persisted seen-id list (unbounded growth guard). */
    private static JSONArray capArr(JSONArray a, int max) {
        if (a == null || a.length() <= max) return a;
        JSONArray out = new JSONArray();
        for (int i = a.length() - max; i < a.length(); i++) out.put(a.opt(i));
        return out;
    }

    /* ---------------- tipping UI ---------------- */

    private void tipDialog(String handle, String toAddr) {
        if (!nodeUp) { toast("Connect the node to tip."); return; }
        final String[] tokenid = { TipTransport.MXUSD };   // default dollar-pegged
        LinearLayout box = dialogBox();
        final TextView curBtn = Design.button(this, "Currency: mxUSD", false);
        box.addView(curBtn, lph(46, 0, 2, 0, 8));
        final EditText amount = field(box, "Amount", "", false, "1");
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        final LinearLayout presets = row();
        for (String amt : new String[]{"1", "5", "20"}) {
            TextView pb = Design.button(this, "$" + amt, false);
            pb.setOnClickListener(v -> amount.setText(amt));
            presets.addView(pb, weight(40, 0, amt.equals("20") ? 0 : 6));
        }
        box.addView(presets, lp(0, 6, 0, 4));
        final TextView bal = Design.note(this, "Checking your balance…");
        box.addView(bal, lp(0, 0, 0, 4));
        final Runnable refreshBal = () -> node.cmd("balance tokenid:" + tokenid[0], new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                String s = "0"; try { JSONArray a = j.optJSONArray("response"); if (a != null && a.length() > 0) s = a.optJSONObject(0).optString("sendable", "0"); } catch (Exception ignored) {}
                final String have = s;
                runOnUiThread(() -> bal.setText("Available: " + have + " " + TipTransport.label(tokenid[0])));
            }
            @Override public void onError(String m) {}
        });
        refreshBal.run();
        final EditText note = field(box, "Note (optional)", "", false, "nice work!");
        curBtn.setOnClickListener(v -> {
            tokenid[0] = tokenid[0].equals(TipTransport.MXUSD) ? TipTransport.MINIMA : TipTransport.MXUSD;
            curBtn.setText("Currency: " + TipTransport.label(tokenid[0]));
            presets.setVisibility(tokenid[0].equals(TipTransport.MXUSD) ? View.VISIBLE : View.GONE);
            refreshBal.run();
        });
        new android.app.AlertDialog.Builder(this).setTitle("Send a tip to @" + handle).setView(box)
                .setPositiveButton("Send", (d, w) -> sendTip(handle, toAddr, tokenid[0], text(amount).trim(), text(note).trim()))
                .setNegativeButton("Cancel", null).show();
    }

    private void sendTip(String handle, String toAddr, String tokenid, String amount, String note) {
        if (!Args.isDecimal(amount)) { toast("Enter a valid amount."); return; }
        final java.math.BigDecimal amt;
        try { amt = new java.math.BigDecimal(amount); } catch (Exception e) { toast("Enter a valid amount."); return; }
        if (amt.signum() <= 0) { toast("Enter an amount."); return; }
        if (mTipInFlight) { toast("A tip is already sending — one moment."); return; }
        mTipInFlight = true;
        toast("Checking balance…");
        node.cmd("balance tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                String sendable = "0";
                try { JSONArray a = j.optJSONArray("response"); if (a != null && a.length() > 0) sendable = a.optJSONObject(0).optString("sendable", "0"); } catch (Exception ignored) {}
                java.math.BigDecimal haveBd; try { haveBd = new java.math.BigDecimal(sendable); } catch (Exception e) { haveBd = java.math.BigDecimal.ZERO; }
                final java.math.BigDecimal have = haveBd;
                runOnUiThread(() -> {
                    if (amt.compareTo(have) > 0) { mTipInFlight = false; toast("Not enough " + TipTransport.label(tokenid) + " (you have " + have.toPlainString() + ")"); return; }
                    toast("Sending tip…");
                    TipTransport.tip(node, toAddr, amount, tokenid, "@" + SalonStore.get(MainActivity.this, "handle"), note, new TipTransport.Cb() {
                        @Override public void onSent(String txpowid) { runOnUiThread(() -> { mTipInFlight = false; toast("Tip sent to @" + handle + " — mining ⛏"); }); }
                        @Override public void onFailed(String msg) { runOnUiThread(() -> { mTipInFlight = false; toast("Tip failed: " + msg); }); }
                    });
                });
            }
            @Override public void onError(String m) { runOnUiThread(() -> { mTipInFlight = false; toast("Balance check failed: " + m); }); }
        });
    }

    /* ================= Messages (DMs over Minima Mail) ================= */

    private void openThread(String peerpk, String handle, String avatar, String addr) {
        if (peerpk == null || peerpk.isEmpty()) { toast("This account can't receive messages yet."); return; }
        // Switching to a different peer: drop any in-progress reply and draft so
        // one conversation's private text can never carry into another's.
        if (!peerpk.equals(threadPeer)) { replyBody = ""; replyFrom = ""; replyPeer = ""; threadDraft = ""; threadRenderedCount = -1; threadWindow = THREAD_WINDOW_STEP; }
        MailDb db = MailDb.get(this);
        MailDb.Contact ex = db.contact(peerpk);
        db.upsertContact(peerpk, handle, avatar, addr, ex != null ? ex.lastbody : "", ex != null ? ex.lastts : 0, false);
        threadPeer = peerpk;
        db.clearUnread(peerpk);
        refreshUnread();
        go(Screen.THREAD);
    }

    private void renderMessages() {
        masthead("Messages");
        if (!SalonStore.hasIdentity(this)) { body.addView(Design.note(this, "Claim your Salon first to send messages."), lp(0, 0, 0, 12)); return; }
        List<MailDb.Contact> cs = MailDb.get(this).contacts();
        if (cs.isEmpty()) {
            LinearLayout c = card();
            c.addView(Design.note(this, "No messages yet. Open someone in Discover and tap Message to start a private, end-to-end-encrypted chat — sent over the chain, no server, no one can read it but you two."));
            body.addView(c, lp(0, 0, 0, 12));
            return;
        }
        for (MailDb.Contact ct : cs) {
            LinearLayout c = card(); c.setClickable(true); Design.pressable(c);
            final MailDb.Contact fct = ct;
            c.setOnClickListener(v -> openThread(fct.peerpk, fct.handle, fct.avatar, fct.addr));
            LinearLayout r = row();
            r.addView(avatarView(ct.avatar, ct.handle == null ? "?" : ct.handle, 44));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(12), 0, dp(8), 0);
            col.addView(Design.text(this, "@" + (ct.handle == null ? "someone" : ct.handle.replaceFirst("^@", "")), 15, Design.INK(), Design.sansBold()));
            TextView prev = Design.text(this, ct.lastbody == null ? "" : ct.lastbody, 12.5f, Design.DIM(), Design.sans()); prev.setMaxLines(1); prev.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(prev);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            if (ct.unread > 0) r.addView(Design.pill(this, String.valueOf(ct.unread), Design.PILL_MINE));
            c.addView(r);
            body.addView(c, lp(0, 0, 0, 10));
        }
    }

    private void renderThread() {
        if (threadPeer.isEmpty()) { go(Screen.MESSAGES); return; }
        MailDb db = MailDb.get(this);
        MailDb.Contact ct = db.contact(threadPeer);
        String handle = ct != null && ct.handle != null ? ct.handle.replaceFirst("^@", "") : "chat";
        masthead("@" + handle);
        db.clearUnread(threadPeer); refreshUnread();
        LinearLayout bar = row();
        bar.addView(btn("← Messages", false, () -> go(Screen.MESSAGES)), weight(46, 0, 0));
        body.addView(bar, lp(0, 0, 0, 8));
        // Which transport carries messages to THIS contact — tap to override.
        String xport = SalonStore.get(this, XPORT_PREF + threadPeer);
        boolean hasMxAddr = ct != null && ct.mxaddr != null && !ct.mxaddr.isEmpty();
        boolean usingMaxima = !"chain".equals(xport) && hasMxAddr && MaximaLink.isReady(this);
        String forced = "maxima".equals(xport) || "chain".equals(xport) ? " · forced" : "";
        TextView chip = Design.pill(this,
                (usingMaxima ? "⚡ Maxima · off-chain" : "⛓ Minima · on-chain") + forced + "  ▾",
                usingMaxima ? Design.PILL_MINE : Design.PILL_DIM);
        chip.setOnClickListener(v -> transportDialog());
        LinearLayout tr = row();
        tr.addView(chip, new LinearLayout.LayoutParams(-2, -2));
        body.addView(tr, lp(0, 0, 0, 8));
        final String peerHandle = handle;
        // Windowed: render only the most-recent threadWindow messages so a long thread
        // doesn't rebuild hundreds of View bubbles (and hold them all in heap) on every
        // ack/inbound re-render. "Load earlier" grows the window on demand.
        int total = db.messageCount(threadPeer);
        List<MailDb.Message> msgs = db.messages(threadPeer, threadWindow);
        if (msgs.isEmpty()) body.addView(Design.note(this, "No messages yet — say hi. Everything here is end-to-end encrypted and sent over the chain. Long-press a message to reply."), lp(0, 0, 0, 10));
        if (total > msgs.size()) {
            body.addView(btn("↑ Load earlier (" + (total - msgs.size()) + " more)", false,
                    () -> { threadWindow += THREAD_WINDOW_STEP; render(); }), lp(0, 0, 0, 6));
        }
        for (MailDb.Message m : msgs) {
            View bv = bubble(m);
            final MailDb.Message fm = m;
            bv.setOnLongClickListener(v -> { startReply(fm, peerHandle); return true; });
            body.addView(bv);
        }
        LinearLayout comp = card();
        // Reply banner — the quoted parent, with a cancel, shown above the field.
        // Only for a reply that belongs to THIS thread (never another peer's).
        if (!replyBody.isEmpty() && threadPeer.equals(replyPeer)) {
            LinearLayout rb = row(); rb.setBackground(Design.ruled(this, Design.CARD(), Design.ACCENT(), 1)); rb.setPadding(dp(10), dp(7), dp(8), dp(7));
            LinearLayout rcol = new LinearLayout(this); rcol.setOrientation(LinearLayout.VERTICAL);
            rcol.addView(Design.text(this, "↩ Replying to " + replyFrom, 10f, Design.ACCENT(), Design.sansBold()));
            TextView rsnip = Design.text(this, replyBody, 12f, Design.DIM(), Design.sans()); rsnip.setMaxLines(1); rsnip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            rcol.addView(rsnip);
            rb.addView(rcol, new LinearLayout.LayoutParams(0, -2, 1));
            TextView x = Design.text(this, "✕", 16, Design.DIM(), Design.sansBold()); x.setPadding(dp(10), 0, dp(4), 0); x.setClickable(true);
            x.setOnClickListener(v -> { replyBody = ""; replyFrom = ""; render(); });
            rb.addView(x);
            comp.addView(rb, lp(0, 0, 0, 8));
        }
        final EditText input = fieldMulti(comp, "Message", threadDraft);   // restore any preserved draft
        input.addTextChangedListener(watch(s -> threadDraft = s));          // keep the draft current across re-renders
        LinearLayout crow = row();
        crow.addView(btn("📎", false, this::attachDmMedia), new LinearLayout.LayoutParams(dp(54), dp(46)));
        crow.addView(btn("Send", true, () -> sendDm(text(input), "", "")), weight(46, 6, 0));
        comp.addView(crow, lp(0, 6, 0, 0));
        body.addView(comp, lp(0, 8, 0, 0));
        // Auto-scroll to the newest only when the message set actually grew (fresh
        // open or a new message) — not on an ack re-render, so we don't yank a user
        // who's reading back through history.
        boolean grew = total != threadRenderedCount;   // a NEW message (total up), not "load earlier" (window up)
        threadRenderedCount = total;
        if (grew) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Begin a quoted reply to {@code m}; the banner + send stamp the snapshot. */
    private void startReply(MailDb.Message m, String peerHandle) {
        String b = m.body != null && !m.body.isEmpty() ? m.body : (m.media != null && !m.media.isEmpty() ? "📎 media" : "");
        if (b.isEmpty()) return;
        replyBody = b;
        replyFrom = m.mine ? "You" : "@" + (peerHandle == null ? "" : peerHandle.replaceFirst("^@", ""));
        replyPeer = threadPeer;   // this reply belongs to the current thread only
        render();
    }

    private View bubble(MailDb.Message m) {
        LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Design.ruled(this, m.mine ? Design.ACCENT() : Design.CARD(), Design.INK(), 1));
        b.setPadding(dp(12), dp(9), dp(12), dp(9));
        // Quoted parent (denormalised snapshot carried with the reply).
        if (m.replyBody != null && !m.replyBody.isEmpty()) {
            LinearLayout q = new LinearLayout(this); q.setOrientation(LinearLayout.VERTICAL);
            int accent = m.mine ? Design.PAPER() : Design.ACCENT();
            q.setBackground(Design.ruled(this, m.mine ? Design.ACCENT() : Design.PAPER(), accent, 1));
            q.setPadding(dp(8), dp(5), dp(8), dp(6));
            q.addView(Design.text(this, "↩ " + (m.replyFrom == null || m.replyFrom.isEmpty() ? "reply" : m.replyFrom), 9.5f, accent, Design.sansBold()));
            TextView qs = Design.text(this, m.replyBody, 12f, m.mine ? Design.PAPER() : Design.DIM(), Design.sans()); qs.setMaxLines(2); qs.setEllipsize(android.text.TextUtils.TruncateAt.END);
            q.addView(qs);
            b.addView(q, lp(0, 0, 0, 6));
        }
        if (m.body != null && !m.body.isEmpty())
            b.addView(Design.text(this, m.body, 14.5f, m.mine ? Design.PAPER() : Design.INK(), Design.sans()));
        if (m.media != null && !m.media.isEmpty()) {
            JSONObject mm = new JSONObject();
            try { mm.put("type", m.mime != null && m.mime.contains("video") ? "video" : m.mime != null && m.mime.contains("audio") ? "audio" : "image"); mm.put("url", m.media); mm.put("caption", ""); } catch (Exception ignored) {}
            b.addView(mediaCard(mm), lp(0, m.body != null && !m.body.isEmpty() ? 6 : 0, 0, 0));
        }
        // Show delivery state on my own bubbles: an unconfirmed send reads
        // "sending…" (it's PENDING in the outbox) so nothing looks delivered
        // when it isn't; a confirmed one shows the timestamp.
        String meta = m.mine && m.status == MailDb.PENDING ? "sending…" : Util.ago(m.ts);
        if (!meta.isEmpty()) b.addView(Design.text(this, meta, 9.5f, m.mine ? Design.PAPER() : Design.DIM(), Design.mono()), lp(0, 3, 0, 0));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.setMargins(m.mine ? dp(52) : 0, 0, m.mine ? 0 : dp(52), dp(6));
        b.setLayoutParams(blp);
        return b;
    }

    private void sendDm(String text, String mediaRef, String mediaMime) {
        if (threadPeer.isEmpty()) return;
        final String body = text == null ? "" : text.trim();
        if (body.isEmpty() && (mediaRef == null || mediaRef.isEmpty())) { toast("Type a message."); return; }
        MailDb db = MailDb.get(this);
        final MailDb.Contact ct = db.contact(threadPeer);
        if (ct == null) { toast("No contact yet."); return; }

        // Transport choice. Chain needs an on-chain address; Maxima needs an
        // mxaddr. A Maxima-only contact (no coin addr) is fully messageable —
        // the old guard rejected it, defeating the whole point.
        //   auto (default) → Maxima when we know their address, else chain
        //   maxima         → force Maxima (needs their address)
        //   chain          → force on-chain
        String xport = SalonStore.get(this, XPORT_PREF + threadPeer);
        boolean hasMx = ct.mxaddr != null && !ct.mxaddr.isEmpty();
        boolean hasAddr = ct.addr != null && !ct.addr.isEmpty();
        boolean forceChain = "chain".equals(xport);
        boolean forceMaxima = "maxima".equals(xport);
        if (forceMaxima && !hasMx) {
            toast("No Maxima address for this contact yet — they teach it to you when they message you, or open their profile.");
            return;
        }
        boolean viaChain = forceChain || (!forceMaxima && !hasMx);
        if (viaChain && !hasAddr) { toast("No delivery address for this contact yet."); return; }

        long ts = System.currentTimeMillis() / 1000;
        String myHandle = "@" + SalonStore.get(this, "handle");
        String myAddr = SalonStore.get(this, "tipaddr");
        JSONObject msg = MinimaMail.compose(myHandle, myAddr, body, mediaRef, mediaMime, ts);
        // Stamp MY Maxima address into every DM so the peer learns how to reply
        // over Maxima — makes the transport two-way after a single message.
        try { String myMx = MaximaLink.myAddresses(this); if (!myMx.isEmpty()) msg.put("mxaddr", myMx); } catch (Exception ignored) {}
        // Carry the quoted-reply snapshot (if replying). replyFrom is from MY side
        // ("You" = my message, else the peer's) — flip it for the wire so the peer
        // reads it from theirs: my message → my handle; their message → "You".
        final String outReplyBody = threadPeer.equals(replyPeer) ? replyBody : "";   // never send another thread's quote
        final String outReplyFrom = outReplyBody.isEmpty() ? "" : (replyFrom.equals("You") ? myHandle : "You");
        if (!outReplyBody.isEmpty()) { try { msg.put("replybody", outReplyBody); msg.put("replyfrom", outReplyFrom); } catch (Exception ignored) {} }
        final String msgId = "me-" + System.currentTimeMillis() + "-" + Math.abs((body + threadPeer).hashCode());
        // Stable app-level id so retries of the SAME message dedup on the peer
        // (the transport msgid changes each attempt; this doesn't).
        try { msg.put("id", msgId); } catch (Exception ignored) {}
        // Insert PENDING: an outgoing message is only marked delivered once the
        // transport confirms (Maxima ack / on-chain post). Until then the bubble
        // shows "sending…" and the outbox retries it — never a silent loss.
        db.insert(msgId, threadPeer, true, body, mediaRef, mediaMime, ts, true, MailDb.PENDING);
        if (!outReplyBody.isEmpty()) db.setReply(msgId, replyBody, replyFrom);   // my local copy keeps MY-side labels
        db.upsertContact(threadPeer, ct.handle, ct.avatar, ct.addr, body.isEmpty() ? "📎 media" : body, ts, false);
        replyBody = ""; replyFrom = ""; replyPeer = ""; threadDraft = "";   // consumed — clear before the re-render
        render();

        if (viaChain) { coinSendDm(ct, msg, msgId); return; }

        // Maxima path (hasMx, auto or forced). Never downgraded to chain.
        if (!MaximaLink.isReady(this)) {
            // Left PENDING; the outbox flushes it when Maxima connects (onResume /
            // heartbeat). Not silently dropped, not put on the public ledger.
            toast("Maxima connecting — message queued.");
            return;
        }
        sendOverMaxima(ct.peerpk, ct.mxaddr, msg, msgId);
    }

    /** Seal + send a composed DM over Maxima; flip the row to SENT on confirm,
     *  leave it PENDING (for the outbox) on failure. */
    private void sendOverMaxima(final String peerpk, String mxaddr, JSONObject msg, final String msgId) {
        if (!mInFlight.add(msgId)) return;   // already being sent by another trigger
        String sealedHex;
        try {
            sealedHex = SalonComms.crypto(this).seal(peerpk,
                    msg.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { sealedHex = null; }
        if (sealedHex == null) {   // stays PENDING; retried
            mInFlight.remove(msgId);
            runOnUiThread(() -> toast("Couldn't seal the message."));
            return;
        }
        MaximaLink.sendDm(this, mxaddr, sealedHex, new MaximaLink.SendCb() {
            @Override public void onSent(String status, boolean pending) {
                MailDb.get(MainActivity.this).setStatus(msgId, MailDb.SENT);
                mInFlight.remove(msgId);
                runOnUiThread(() -> {
                    if (screen == Screen.THREAD) render();
                    toast(pending ? "Sent ✓ (held until they're online)" : "Sent ✓");
                });
            }
            @Override public void onFailed(String error) {
                // Stays PENDING in the outbox; retried when Maxima reconnects.
                mInFlight.remove(msgId);
                runOnUiThread(() -> toast("Queued — will retry over Maxima."));
            }
        });
    }

    /** Re-attempt every outgoing message not yet confirmed delivered. Called when
     *  the app comes forward and on the foreground heartbeat, so a message typed
     *  while Maxima was down (or a send that timed out) is not lost. */
    private void retryOutbox() {
        // Off the UI thread: the loop reads the DB and libsodium-seals each message
        // (the same heavy work SalonNotifyReceiver offloads). sendOverMaxima /
        // coinSendDm are thread-safe; their toasts/render hop back to the UI thread.
        io.execute(() -> {
        java.util.List<MailDb.Message> pend = MailDb.get(this).pendingOutbox();
        if (pend.isEmpty()) return;
        String myHandle = "@" + SalonStore.get(this, "handle");
        String myAddr = SalonStore.get(this, "tipaddr");
        String myMx = MaximaLink.myAddresses(this);
        for (MailDb.Message m : pend) {
            MailDb.Contact ct = MailDb.get(this).contact(m.peerpk);
            if (ct == null) continue;
            JSONObject msg = MinimaMail.compose(myHandle, myAddr,
                    m.body == null ? "" : m.body, m.media == null ? "" : m.media,
                    m.mime == null ? "" : m.mime, m.ts);
            try { if (!myMx.isEmpty()) msg.put("mxaddr", myMx); } catch (Exception ignored) {}
            // Same stable id as the first attempt → the peer dedups the retry.
            try { msg.put("id", m.coinid); } catch (Exception ignored) {}
            // Preserve the quoted reply on retry (flip MY-side label for the wire).
            if (m.replyBody != null && !m.replyBody.isEmpty()) {
                String rf = "You".equals(m.replyFrom) ? myHandle : "You";
                try { msg.put("replybody", m.replyBody); msg.put("replyfrom", rf); } catch (Exception ignored) {}
            }
            String xport = SalonStore.get(this, XPORT_PREF + m.peerpk);
            boolean hasMx = ct.mxaddr != null && !ct.mxaddr.isEmpty();
            boolean forceChain = "chain".equals(xport);
            if (!forceChain && hasMx && MaximaLink.isReady(this)) {
                // Idempotent: the peer dedups by the stable id, so retry any time.
                sendOverMaxima(ct.peerpk, ct.mxaddr, msg, m.coinid);
            } else if ((forceChain || !hasMx) && nodeUp && ct.addr != null && !ct.addr.isEmpty()) {
                // Re-post on-chain only after waiting out the node write timeout (180s)
                // since the LAST attempt — a send that timed out may still be mining a
                // live coin, and re-posting inside that window double-spends the dust.
                long lastMs = mLastChainAttempt.getOrDefault(m.coinid, m.ts * 1000L);
                if (System.currentTimeMillis() - lastMs > 240_000L) coinSendDm(ct, msg, m.coinid);
            }
            // else: no transport ready yet (or too fresh to safely re-post) — stays PENDING.
        }
        });
    }

    /** Let the user force the transport for THIS contact, or leave it automatic. */
    private void transportDialog() {
        final MailDb.Contact ct = MailDb.get(this).contact(threadPeer);
        boolean hasMx = ct != null && ct.mxaddr != null && !ct.mxaddr.isEmpty();
        final String[] labels = {
                "Automatic — Maxima when possible, else on-chain",
                "Prefer Maxima — off-chain, instant" + (hasMx ? "" : "  (no address yet)"),
                "On-chain only — Minima dust coin"
        };
        final String[] vals = {"auto", "maxima", "chain"};
        String cur = SalonStore.get(this, XPORT_PREF + threadPeer);
        int sel = "maxima".equals(cur) ? 1 : "chain".equals(cur) ? 2 : 0;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Message transport")
                .setSingleChoiceItems(labels, sel, (d, which) -> {
                    SalonStore.put(this, XPORT_PREF + threadPeer, vals[which]);
                    d.dismiss();
                    if ("maxima".equals(vals[which]) && !hasMx)
                        toast("No Maxima address for this contact yet — it's learned when they message you, or open their profile.");
                    render();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** The classic path: a 0.001 dust coin with the sealed DM in state[99]. */
    private void coinSendDm(final MailDb.Contact ct, JSONObject msg, final String msgId) {
        if (!mInFlight.add(msgId)) return;   // already being posted by another trigger
        mLastChainAttempt.put(msgId, System.currentTimeMillis());   // start the re-post cooldown
        MinimaMail.send(node, SalonComms.crypto(this), ct.addr, ct.peerpk, msg, new MinimaMail.Cb() {
            @Override public void onSent(String txpowid) {
                MailDb.get(MainActivity.this).setStatus(msgId, MailDb.SENT);
                mInFlight.remove(msgId);
                runOnUiThread(() -> { if (screen == Screen.THREAD) render(); toast("Sent ⛏ (mining)"); });
            }
            @Override public void onFailed(String m) {
                // Stays PENDING in the outbox; retried when the node is ready.
                mInFlight.remove(msgId);
                runOnUiThread(() -> toast("Send failed — queued: " + m));
            }
        });
    }

    private void attachDmMedia() {
        if (threadPeer.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "Choose photo"), PICK_DM_IMG);
    }

    /* ---------------- messaging key backup / restore ---------------- */

    private void backupMsgKey() {
        io.execute(() -> {
            final String seed = SalonComms.exportSeed(this);
            runOnUiThread(() -> {
                if (seed.isEmpty()) { toast("No messaging key yet."); return; }
                LinearLayout box = dialogBox();
                box.addView(Design.note(this, "This is your MESSAGING KEY. Anyone with it can read your DMs and impersonate you — store it safely (a password manager). Paste it on a new device or after a reinstall to keep the same inbox."), lp(0, 2, 0, 8));
                TextView t = Design.text(this, seed, 12, Design.INK(), Design.mono()); copyOnTap(t, seed); box.addView(t);
                new android.app.AlertDialog.Builder(this).setTitle("Back up messaging key").setView(box)
                        .setPositiveButton("Save file", (d, w) -> saveTextFile("salon-messaging-key.txt", seed))
                        .setNeutralButton("Copy", (d, w) -> { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("salon-msgkey", seed)); toast("Copied — store it safely."); })
                        .setNegativeButton("Close", null).show();
            });
        });
    }

    /** Save text to a file the user picks (system Save-As, no storage permission). */
    private void saveTextFile(String suggestedName, String content) {
        pendingSaveText = content;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, suggestedName);
        try { startActivityForResult(i, SAVE_TEXT); }
        catch (Exception e) { pendingSaveText = null; toast("No file app to save with."); }
    }

    /** A full account backup file: messaging key + hosting logins (+ identity/content
     *  draft). Secrets are stored DECRYPTED here (Crypt is per-install and useless after
     *  a reinstall) and re-wrapped with this device's key on restore. Guard the file. */
    private String buildBackupJson() {
        JSONObject b = new JSONObject();
        try {
            b.put("salonBackup", 1);
            b.put("created", System.currentTimeMillis() / 1000);
            JSONObject me = new JSONObject(SalonStore.me(this).toString());
            me.remove("msgseed");   // device-bound; exported separately as plaintext
            b.put("me", me);
            String seed = SalonComms.exportSeed(this);
            if (!seed.isEmpty()) b.put("msgseed", seed);
            JSONArray hosts = new JSONArray();
            for (Hosting.Profile p : HostingStore.list(this)) {
                JSONObject copy = new JSONObject(p.j.toString());
                JSONObject cfg = copy.optJSONObject(copy.optString("type"));
                if (cfg != null) for (String sf : HostingStore.SECRET_FIELDS) {
                    String enc = cfg.optString(sf, "");
                    if (!enc.isEmpty()) cfg.put(sf, Crypt.decrypt(enc));
                }
                hosts.put(copy);
            }
            b.put("hosting", hosts);
        } catch (Exception ignored) {}
        return b.toString();
    }

    private void restoreFromFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(i, OPEN_BACKUP); }
        catch (Exception e) { toast("No file app to open with."); }
    }

    /** Restore an account backup: re-wrap hosting secrets with this device's Crypt key,
     *  import the messaging seed, and (only if empty) seed the identity/content draft. */
    private void restoreFromBackup(String json) {
        try {
            JSONObject b = new JSONObject(json);
            if (b.optInt("salonBackup", 0) != 1) { runOnUiThread(() -> toast("Not a Salon backup file.")); return; }
            JSONObject me = b.optJSONObject("me");
            if (me != null && !SalonStore.hasIdentity(this)) {   // don't clobber a live identity
                JSONObject keepMsg = SalonStore.me(this);
                if (keepMsg.has("msgseed")) try { me.put("msgseed", keepMsg.optString("msgseed")); } catch (Exception ignored) {}
                SalonStore.save(this, me);
            }
            int hostN = 0;
            JSONArray hosts = b.optJSONArray("hosting");
            if (hosts != null) for (int i = 0; i < hosts.length(); i++) {
                JSONObject o = hosts.optJSONObject(i); if (o == null) continue;
                JSONObject cfg = o.optJSONObject(o.optString("type"));
                if (cfg != null) for (String sf : HostingStore.SECRET_FIELDS) {
                    String plain = cfg.optString(sf, "");
                    if (!plain.isEmpty()) cfg.put(sf, Crypt.encrypt(plain));
                }
                HostingStore.upsert(this, new Hosting.Profile(o));
                hostN++;
            }
            boolean msgOk = false, seedKept = false;
            String seed = b.optString("msgseed", "");
            if (!seed.isEmpty()) {
                boolean activeInbox = !MailDb.get(this).contacts().isEmpty();
                if (activeInbox && !seed.equalsIgnoreCase(SalonComms.exportSeed(this))) {
                    seedKept = true;   // never silently replace a key that has a live inbox
                } else if (SalonComms.importSeed(this, seed)) {
                    SalonStore.put(this, "msgpk", SalonComms.publicId(this)); msgOk = true;
                }
            }
            final int hn = hostN; final boolean mo = msgOk, sk = seedKept;
            runOnUiThread(() -> { toast("Restored " + hn + " destination" + (hn == 1 ? "" : "s") + (mo ? " + messaging key." : ".")
                    + (sk ? " Kept your current messaging key (use Settings → Restore messaging key to change it)." : "")); render(); });
        } catch (Exception e) { runOnUiThread(() -> toast("Restore failed: " + e.getMessage())); }
    }

    private void restoreMsgKey() {
        LinearLayout box = dialogBox();
        final EditText f = field(box, "Paste messaging key (hex)", "", false, "");
        showDialog("Restore messaging key", box, "Restore", () -> io.execute(() -> {
            final boolean ok = SalonComms.importSeed(this, text(f));
            runOnUiThread(() -> { if (ok) { SalonStore.put(this, "msgpk", SalonComms.publicId(this)); toast("Restored. Re-publish your profile so others use the new key."); render(); } else toast("Invalid key."); });
        }));
    }

    /** GET-touch the owner's own relay blobs so their 7-day TTL resets each session. */
    private void touchOwnRelay() {
        final String pu = SalonStore.get(this, "profileUrl");
        if (!RelayResolver.isRelayRef(pu)) return;
        io.execute(() -> {
            try {
                JSONObject p = RelayResolver.resolveJson(pu);   // GET refreshes profile.json chunks
                touchRef(p.optString("avatar", "")); touchRef(p.optString("banner", ""));
                touchArr(p.optJSONArray("gallery"), "url");
                touchArr(p.optJSONArray("posts"), "media");
            } catch (Exception ignored) {}
        });
    }
    private void touchRef(String u) { if (RelayResolver.isRelayRef(u)) RelayResolver.touch(u); }
    private void touchArr(JSONArray a, String key) { if (a == null) return; for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); if (o != null) touchRef(o.optString(key, "")); } }

    private final android.os.Handler pairHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable pairTick = new Runnable() {
        @Override public void run() {
            if (node != null && !node.isEnabled()) { node.reRegister(); pairHandler.postDelayed(this, 5000); }
        }
    };
    private void startPairingRetry() { pairHandler.removeCallbacks(pairTick); pairHandler.postDelayed(pairTick, 3000); }

    // Foreground gossip-mesh renewal: re-run keepAlive on every connect/resume AND on a
    // timer while the app is open, so a beacon that prunes (~a day) is renewed before it
    // goes dark. keepAlive itself only POSTS when a beacon has actually faded (on-chain
    // check), so this is cheap. Throttled so a burst of connects doesn't re-scan repeatedly.
    private final android.os.Handler reannHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private long lastReannounceMs = 0;
    private static final long REANN_THROTTLE_MS = 4 * 60 * 1000L;    // don't re-scan more than ~every 4 min
    private static final long REANN_INTERVAL_MS = 20 * 60 * 1000L;   // check every ~20 min while foreground
    private final Runnable reannTick = new Runnable() {
        @Override public void run() { reannounceNow(); retryOutbox(); reannHandler.postDelayed(this, REANN_INTERVAL_MS); }
    };
    private void reannounceNow() {
        if (!nodeUp || !SalonStore.hasIdentity(this)) return;
        long now = System.currentTimeMillis();
        if (now - lastReannounceMs < REANN_THROTTLE_MS) return;
        lastReannounceMs = now;
        SalonRegistry.keepAlive(node, SalonStore.get(this, "tokenid"), SalonStore.get(this, "profileUrl"),
                SalonStore.get(this, "handle"), SalonStore.follows(this), n -> {});
    }

    @Override protected void onResume() {
        super.onResume();
        reannHandler.removeCallbacks(reannTick);
        reannHandler.post(reannTick);   // renew now (if faded) + reschedule while foreground
        if (node != null && !node.isEnabled()) startPairingRetry();   // resume pairing retries only if still unpaired
        // While we're foreground, let the shared inbox path (Maxima deliveries and
        // background coin NOTIFY, both static in SalonNotifyReceiver.intakeDm) poke
        // the open tab. Only the on-chain scanMail rendered itself, so a Maxima DM
        // toasted/notified but never appeared in the Messages tab until you left
        // and came back. Now any transport repaints it live.
        sInbox = () -> runOnUiThread(() -> {
            refreshUnread();
            retryOutbox();   // an inbound message proves a transport is alive — flush
            if (screen == Screen.MESSAGES || screen == Screen.THREAD) render();
        });
        // Also flush when Maxima finishes (re)connecting, so a message queued
        // while it was down sends the moment it comes up.
        sMaximaReady = () -> runOnUiThread(this::retryOutbox);
        refreshUnread();   // catch anything delivered while we were away
        retryOutbox();     // flush anything queued while we were backgrounded / offline
        if (screen == Screen.MESSAGES || screen == Screen.THREAD) render();
    }

    @Override protected void onPause() {
        super.onPause();
        reannHandler.removeCallbacks(reannTick);   // no background beaconing — foreground only
        pairHandler.removeCallbacks(pairTick);     // stop the 5s SDK-rebuild loop while backgrounded (restarted on resume if still unpaired)
        sInbox = null; sMaximaReady = null;   // don't hold the activity while backgrounded
    }

    /** Set while resumed; SalonNotifyReceiver.intakeDm pokes it after a new DM
     *  arrives over ANY transport, so the open Messages/Thread tab repaints. */
    private static volatile Runnable sInbox;
    /** Set while resumed; MaximaLink pokes it when the transport (re)connects, so
     *  the outbox flushes messages queued while Maxima was down. */
    private static volatile Runnable sMaximaReady;

    /** Called by the shared DM intake when a NEW message lands. No-op if no
     *  activity is foreground (the notification already covers that case). */
    static void onInboxChanged() {
        Runnable r = sInbox;
        if (r != null) {
            try { r.run(); } catch (Exception ignored) { }
        }
    }

    /** Called by MaximaLink when the transport becomes ready. Flushes the outbox. */
    static void onMaximaReady() {
        Runnable r = sMaximaReady;
        if (r != null) {
            try { r.run(); } catch (Exception ignored) { }
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        pairHandler.removeCallbacks(pairTick);
        reannHandler.removeCallbacks(reannTick);
        io.shutdownNow();
        stopAudio();
        if (node != null) node.onDestroy();   // release the MinimaAPI receiver + cancel pending IPC timeouts
    }

    /* ---------------- chrome + nav ---------------- */

    private void buildNav() {
        navRow.removeAllViews();
        navRow.addView(Design.rule(this, 2), new LinearLayout.LayoutParams(-1, dp(2)));
        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(navTab("Feed", screen == Screen.FEED, () -> go(Screen.FEED)), weight1());
        tabs.addView(navTab("Discover", screen == Screen.DISCOVER || screen == Screen.VIEW, () -> go(Screen.DISCOVER)), weight1());
        tabs.addView(navTab("Salon", screen == Screen.HOME || screen == Screen.ONBOARD || screen == Screen.EDIT, () -> go(Screen.HOME)), weight1());
        tabs.addView(navTabBadge("Messages", screen == Screen.MESSAGES || screen == Screen.THREAD, unreadCache, () -> go(Screen.MESSAGES)), weight1());   // cached; refreshed off the UI thread
        tabs.addView(navTab("Settings", screen == Screen.SETTINGS || screen == Screen.HOSTING || screen == Screen.HOSTING_EDIT, () -> go(Screen.SETTINGS)), weight1());
        navRow.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
    }

    private View navTab(String label, boolean active, Runnable click) {
        TextView t = Design.text(this, label.toUpperCase(), 10f, active ? Design.INK() : Design.DIM(), Design.sansBold());
        t.setLetterSpacing(0.04f); t.setGravity(Gravity.CENTER); t.setPadding(0, dp(12), 0, dp(12));
        if (active) t.setBackgroundColor(0x14000000);
        t.setOnClickListener(v -> click.run());
        return t;
    }

    private View navTabBadge(String label, boolean active, int unread, Runnable click) {
        View base = navTab(label, active, click);
        if (unread <= 0) return base;
        FrameLayout f = new FrameLayout(this);
        f.addView(base, new FrameLayout.LayoutParams(-1, -2));
        TextView badge = Design.pill(this, unread > 9 ? "9+" : String.valueOf(unread), Design.PILL_MINE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        blp.topMargin = dp(5); blp.leftMargin = dp(34);
        f.addView(badge, blp);
        return f;
    }

    private void masthead(String title) {
        appbar.removeAllViews();
        LinearLayout pad = new LinearLayout(this); pad.setOrientation(LinearLayout.HORIZONTAL); pad.setGravity(Gravity.CENTER_VERTICAL);
        pad.setPadding(dp(16), dp(10), dp(16), dp(8));
        pad.addView(Design.display(this, title, 22), new LinearLayout.LayoutParams(0, -2, 1));
        TextView ver = Design.text(this, "№ " + BuildConfig.VERSION_NAME, 10.5f, Design.DIM(), Design.mono());
        ver.setPadding(0, 0, dp(8), 0); pad.addView(ver);
        nodeChip = Design.pill(this, nodeUp ? "connected" : "no node", Design.PILL_DIM); pad.addView(nodeChip);
        appbar.addView(pad);
        appbar.addView(Design.rule(this, 2), new LinearLayout.LayoutParams(-1, dp(2)));
    }

    private void go(Screen s) {
        if (screen == Screen.EDIT && s != Screen.EDIT) commitEditFields();   // preserve the draft when leaving Edit
        screen = s; render();
    }

    private int renderEpoch = 0;   // bumped every render(); async painters bail if it moved (stale screen)
    private void render() {
        if (screen == Screen.EDIT) commitEditFields();   // an in-Edit action rebuilding the screen must not lose typed text
        renderEpoch++;
        if (swipe != null) swipe.setEnabled(screen == Screen.FEED || screen == Screen.DISCOVER);   // pull-to-refresh where it makes sense
        buildNav(); body.removeAllViews();
        switch (screen) {
            case FEED:         renderFeed(); break;
            case DISCOVER:     renderDiscover(); break;
            case HOME:         renderHome(); break;
            case VIEW:         renderView(); break;
            case EDIT:         renderEdit(); break;
            case ONBOARD:      renderOnboard(); break;
            case SETTINGS:     renderSettings(); break;
            case HOSTING:      renderHosting(); break;
            case HOSTING_EDIT: renderHostingEdit(); break;
            case MESSAGES:     renderMessages(); break;
            case THREAD:       renderThread(); break;
        }
    }

    /* ================= windowed list (bounded scrolling section) ================= */

    /** A RecyclerView that grows with its content up to a maximum height, then
     *  scrolls internally — so a section (e.g. the profile's Posts) can hold an
     *  unbounded number of items without stretching the whole page. Only the
     *  visible window is ever bound, so memory stays bounded however long it grows. */
    private class BoundedRecycler extends RecyclerView {
        private final int maxHpx;
        BoundedRecycler(Context c, int maxHpx) {
            super(c);
            this.maxHpx = maxHpx;
            setLayoutManager(new LinearLayoutManager(c));
            setVerticalScrollBarEnabled(true);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            // Wrap content up to maxHpx; beyond that, scroll inside this view.
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHpx, MeasureSpec.AT_MOST));
        }
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
            // The outer ScrollView is not a nested-scrolling parent, so it would
            // steal every vertical drag. While this list actually has something to
            // scroll, claim the gesture so it scrolls internally; release on lift so
            // dragging over a non-scrolling window still pages the whole screen.
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                boolean scrollable = canScrollVertically(1) || canScrollVertically(-1);
                if (scrollable && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            }
            return super.dispatchTouchEvent(e);
        }
    }

    /** A ScrollView that grows with its content up to a maximum height, then scrolls
     *  internally — for heterogeneous sections (Gallery grid+carousel+list, Holdings
     *  rows) that don't map cleanly onto an adapter. Same gesture-claim as
     *  {@link BoundedRecycler} so the outer ScrollView doesn't steal the drag. */
    private class BoundedScroll extends ScrollView {
        private final int maxHpx;
        BoundedScroll(Context c, int maxHpx) {
            super(c);
            this.maxHpx = maxHpx;
            setVerticalScrollBarEnabled(true);
            setClipToPadding(false);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHpx, MeasureSpec.AT_MOST));
        }
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) {
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                boolean scrollable = canScrollVertically(1) || canScrollVertically(-1);
                if (scrollable && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            }
            return super.dispatchTouchEvent(e);
        }
    }

    /** Wrap {@code content} in a fixed-height internal scroll if it would run long. */
    private View boundedBox(View content, int maxHdp) {
        BoundedScroll bs = new BoundedScroll(this, dp(maxHdp));
        bs.addView(content, new FrameLayout.LayoutParams(-1, -2));
        return bs;
    }

    /** A RecyclerView adapter that builds each row imperatively (the app has no XML
     *  layouts). onBind rebuilds the row into a reused FrameLayout — heterogeneous
     *  row heights, but only the visible window is ever bound. */
    private abstract class ImpAdapter<T> extends RecyclerView.Adapter<ImpVH> {
        final List<T> items = new ArrayList<>();
        void set(List<T> data) { items.clear(); if (data != null) items.addAll(data); notifyDataSetChanged(); }
        @Override public ImpVH onCreateViewHolder(ViewGroup p, int vt) {
            FrameLayout f = new FrameLayout(MainActivity.this);
            f.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
            return new ImpVH(f);
        }
        @Override public int getItemCount() { return items.size(); }
        @Override public void onBindViewHolder(ImpVH h, int pos) {
            FrameLayout f = (FrameLayout) h.itemView;
            f.removeAllViews();
            f.addView(bind(items.get(pos), pos));
        }
        abstract View bind(T item, int pos);
    }
    private static class ImpVH extends RecyclerView.ViewHolder { ImpVH(View v) { super(v); } }

    /* ================= profile model ================= */

    /** The public profile.json = identity + rich content, from the local draft. */
    private JSONObject buildProfileJson() {
        JSONObject me = SalonStore.me(this), p = new JSONObject();
        putJson(p, "v", "1");
        for (String k : new String[]{"handle","name","bio","about","avatar","banner","tokenid","webvalidate","tipaddr","msgpk"})
            putJson(p, k, me.optString(k, ""));
        // Our Maxima addresses (CSV): how peers reach us for instant off-chain
        // DMs. Absent = this Salon has no Maxima yet; peers fall back on-chain.
        String mx = MaximaLink.myAddresses(this);
        if (!mx.isEmpty()) putJson(p, "mxaddr", mx);
        putJson(p, "updated", Long.toString(System.currentTimeMillis() / 1000));
        try {
            p.put("links", me.optJSONArray("links") == null ? new JSONArray() : me.optJSONArray("links"));
            p.put("gallery", me.optJSONArray("gallery") == null ? new JSONArray() : me.optJSONArray("gallery"));
            p.put("posts", me.optJSONArray("posts") == null ? new JSONArray() : me.optJSONArray("posts"));
            p.put("nfts", me.optJSONArray("nfts") == null ? new JSONArray() : me.optJSONArray("nfts"));
        } catch (Exception ignored) {}
        return p;
    }

    /** Re-host profile.json (+ the public web renderer). Refreshes each holding's
     *  published editions FIRST so viewers get the full StateNFT collection. */
    private void hostProfile(TextView status, Runnable done) {
        refreshEditionsThen(() -> doHostProfile(status, done));
    }

    private void doHostProfile(TextView status, Runnable done) {
        Hosting.Profile def = HostingStore.getDefault(this);
        if (def == null) { if (status != null) status.setText("Set hosting first."); return; }
        String handle = SalonStore.get(this, "handle");
        if (status != null) status.setText("Publishing your page…");
        JSONObject profile = buildProfileJson();
        io.execute(() -> {
            try (Hosting.Uploader up = Hosting.forProfile(def)) {
                String url = up.putFile(profile.toString().getBytes("UTF-8"), handle + "/profile.json", "application/json");
                // The encrypted relay isn't a web host, and on content-addressed Blossom
                // the renderer's relative fetch('./profile.json') can never resolve —
                // skip the browser renderer for both.
                if (!Hosting.TYPE_RELAY.equals(def.type()) && !Hosting.TYPE_BLOSSOM.equals(def.type()))
                    try { up.putFile(SALON_HTML.getBytes("UTF-8"), handle + "/index.html", "text/html"); } catch (Exception ignore) {}
                Hosting.verifyUrl(url, def);
                mProfileCache.remove(url);   // your edit is live — don't serve the stale cached copy
                runOnUiThread(() -> {
                    SalonStore.put(this, "profileUrl", url);
                    if (status != null) status.setText("Live.");
                    if (done != null) done.run();
                });
            } catch (Exception e) { runOnUiThread(() -> { if (status != null) status.setText("Publish failed: " + e.getMessage()); }); }
        });
    }

    // ---- Maxima mesh budget: a Maxima-hosted profile shares a small, LRU-evicted relay
    //      shelf with everyone else, so cap its TOTAL footprint. Server hosts are unlimited. ----
    static final long MAX_PROFILE_MESH_BYTES = 24L * 1024 * 1024;

    /** Plaintext bytes a mesh ({@code mx1:}) ref occupies, read from its embedded manifest
     *  {@code size}; 0 for a non-mesh ref (http/relay1) or anything unparseable. */
    private static long meshRefSize(String ref) {
        if (!MaximaLink.isMediaRef(ref)) return 0;
        try {
            byte[] raw = android.util.Base64.decode(ref.substring(MaximaLink.MEDIA_PREFIX.length()),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
            JSONObject m = new JSONObject(new String(raw, "UTF-8"));
            return Math.max(0, Long.parseLong(m.optString("size", "0")));
        } catch (Exception e) { return 0; }
    }

    /** Total plaintext bytes THIS profile currently hosts on the Maxima mesh (avatar, banner,
     *  every gallery item, every post's media). Derived on demand — no separate bookkeeping. */
    private long meshUsageBytes() {
        JSONObject p = buildProfileJson();
        long t = meshRefSize(p.optString("avatar")) + meshRefSize(p.optString("banner"));
        JSONArray g = p.optJSONArray("gallery");
        if (g != null) for (int i = 0; i < g.length(); i++) { JSONObject o = g.optJSONObject(i); if (o != null) t += meshRefSize(o.optString("url")); }
        JSONArray po = p.optJSONArray("posts");
        if (po != null) for (int i = 0; i < po.length(); i++) { JSONObject o = po.optJSONObject(i); if (o != null) t += meshRefSize(o.optString("media")); }
        return t;
    }

    /** Human "12.3 MB" / "8 MB". */
    private static String mibOf(long b) {
        double m = b / (1024.0 * 1024.0);
        return (m >= 10 ? Math.round(m) + "" : (Math.round(m * 10) / 10.0) + "") + " MB";
    }

    /* ================= FEED ================= */

    private void renderFeed() {
        masthead("Feed");
        if (!SalonStore.hasIdentity(this)) { renderOnboard(); return; }
        JSONArray follows = SalonStore.follows(this);
        if (follows.length() == 0) {
            LinearLayout c = card();
            c.addView(Design.note(this, "Your feed is quiet. Find people in Discover and follow them — their posts land here."));
            c.addView(btn("Go to Discover", true, () -> go(Screen.DISCOVER)), lph(46, 0, 10, 0, 0));
            body.addView(c, lp(0, 0, 0, 12));
            return;
        }
        TextView status = Design.note(this, "Pulling posts from " + follows.length() + " salon(s)…");
        body.addView(status, lp(0, 0, 0, 8));
        final List<JSONObject> posts = Collections.synchronizedList(new ArrayList<>());
        final int[] pending = { follows.length() }, fails = { 0 };
        final int ep = renderEpoch;
        for (int i = 0; i < follows.length(); i++) {
            JSONObject f = follows.optJSONObject(i);
            if (f == null) { pending[0]--; continue; }
            final String author = f.optString("handle"), url = f.optString("url"), tid = f.optString("tokenid");
            io.execute(() -> {
                JSONObject prof = httpGetJson(url);
                if (prof != null) {
                    String avatar = prof.optString("avatar", "");
                    JSONArray ps = prof.optJSONArray("posts");
                    if (ps != null) for (int k = 0; k < ps.length(); k++) {
                        JSONObject post = ps.optJSONObject(k);
                        if (post == null) continue;
                        try { post.put("_author", author.isEmpty() ? prof.optString("handle") : author); post.put("_avatar", avatar); post.put("_tid", tid); } catch (Exception ignored) {}
                        posts.add(post);
                    }
                } else fails[0]++;
                runOnUiThread(() -> { if (--pending[0] == 0 && ep == renderEpoch) paintFeed(posts, status, fails[0]); });
            });
        }
    }

    private void paintFeed(List<JSONObject> posts, TextView status, int fails) {
        Collections.sort(posts, (a, b) -> Long.compare(b.optLong("ts", 0), a.optLong("ts", 0)));
        body.removeView(status);
        if (posts.isEmpty()) {
            if (fails > 0) {
                LinearLayout c = card();
                c.addView(Design.note(this, "Couldn't reach " + fails + " of the salons you follow — they may be offline."));
                c.addView(btn("Try again", true, this::render), lph(46, 0, 10, 0, 0));
                body.addView(c, lp(0, 0, 0, 12));
            } else body.addView(Design.note(this, "No posts yet from anyone you follow."), lp(0, 0, 0, 12));
            return;
        }
        for (JSONObject post : posts) {
            LinearLayout c = card();
            LinearLayout head = row();
            head.addView(avatarView(post.optString("_avatar"), post.optString("_author"), 34));
            TextView who = Design.text(this, "@" + post.optString("_author"), 13, Design.ACCENT(), Design.sansBold());
            who.setPadding(dp(9), 0, 0, 0);
            who.setOnClickListener(v -> openTokenProfile(post.optString("_tid")));
            head.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
            String when = Util.ago(post.optLong("ts", 0));
            if (!when.isEmpty()) head.addView(Design.text(this, when, 10.5f, Design.DIM(), Design.mono()));
            c.addView(head, lp(0, 0, 0, 8));
            if (!post.optString("text").isEmpty()) c.addView(Design.body(this, post.optString("text")), lp(0, 0, 0, 6));
            addPostMedia(c, post);
            body.addView(c, lp(0, 0, 0, 12));
        }
    }

    /* ================= DISCOVER ================= */

    private void renderDiscover() {
        masthead("Discover");
        LinearLayout head = card();
        head.addView(Design.note(this, "Everyone on the Salon — read straight off the chain's town square (" + SalonRegistry.SALON_ADDRESS + "). No server, no directory company."));
        body.addView(head, lp(0, 0, 0, 12));
        // Explicit refresh (pull-to-refresh also works) — re-reads the square.
        body.addView(btn("↻ Refresh the square", false, () -> { toast("Refreshing…"); render(); }), lph(44, 0, 0, 0, 10));
        TextView status = Design.note(this, "Reading the square…");
        body.addView(status, lp(0, 0, 0, 8));
        if (!nodeUp) { status.setText("Waiting for Minima Core."); return; }
        final int ep = renderEpoch;
        registryList(rawEntries -> runOnUiThread(() -> {
            if (ep != renderEpoch) return;   // user navigated away before the square loaded
            body.removeView(status);
            java.util.List<SalonRegistry.Entry> entries = new java.util.ArrayList<>(rawEntries);
            // Always show yourself first — your beacon may still be confirming after a re-announce,
            // so the square never looks empty to you while your identity exists locally.
            String myTid = SalonStore.get(this, "tokenid");
            if (!myTid.isEmpty()) {
                boolean present = false;
                for (SalonRegistry.Entry e : entries) if (myTid.equals(e.tokenid)) { present = true; break; }
                if (!present) entries.add(0, new SalonRegistry.Entry(myTid, SalonStore.get(this, "profileUrl"), SalonStore.get(this, "handle")));
            }
            if (entries.isEmpty()) { body.addView(Design.note(this, "The square is empty — be the first. Publish your Salon from My Salon."), lp(0, 0, 0, 12)); return; }
            for (SalonRegistry.Entry e : entries) {
                LinearLayout c = card(); c.setClickable(true); Design.pressable(c);
                c.setOnClickListener(v -> openProfile(e));
                LinearLayout r = row();
                final FrameLayout avSlot = new FrameLayout(this);
                avSlot.addView(avatarView("", e.handle, 48));
                r.addView(avSlot, new LinearLayout.LayoutParams(dp(48), dp(48)));
                LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(12), 0, 0, 0);
                final TextView nameTv = Design.text(this, e.handle.isEmpty() ? "…" : e.handle, 16, Design.INK(), Design.sansBold());
                final TextView handleTv = Design.text(this, "@" + e.handle, 12, Design.ACCENT(), Design.mono());
                final TextView bioTv = Design.text(this, "", 12.5f, Design.DIM(), Design.sans()); bioTv.setMaxLines(2); bioTv.setVisibility(View.GONE);
                col.addView(nameTv); col.addView(handleTv); col.addView(bioTv);
                r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                if (SalonStore.isFollowing(this, e.tokenid)) r.addView(Design.pill(this, "following", Design.PILL_DONE));
                c.addView(r);
                body.addView(c, lp(0, 0, 0, 10));
                // enrich the card from the hosted profile: real avatar, display name, bio
                io.execute(() -> { JSONObject prof = httpGetJson(e.url); if (prof == null) return; runOnUiThread(() -> {
                    String nm = prof.optString("name", ""), av = prof.optString("avatar", ""), bio = prof.optString("bio", ""), wv = prof.optString("webvalidate", "");
                    if (!nm.isEmpty()) nameTv.setText(nm);
                    avSlot.removeAllViews(); avSlot.addView(badgedAvatar(av, e.handle, e.tokenid, wv, 48));
                    if (!bio.isEmpty()) { bioTv.setText(bio); bioTv.setVisibility(View.VISIBLE); }
                }); });
            }
        }));
    }

    private boolean viewFailed = false;
    private void openProfile(SalonRegistry.Entry e) {
        // A holding expanded on one profile must not carry its open state to the next.
        if (viewEntry == null || !viewEntry.tokenid.equals(e.tokenid)) expandedNft = null;
        viewEntry = e; viewProfile = null; viewFailed = false;
        go(Screen.VIEW);
        io.execute(() -> { JSONObject p = httpGetJson(e.url); runOnUiThread(() -> { viewProfile = p; viewFailed = (p == null); if (screen == Screen.VIEW) render(); }); });
    }

    private void openTokenProfile(String tokenid) {
        registryList(entries -> {
            for (SalonRegistry.Entry e : entries) if (e.tokenid.equals(tokenid)) { runOnUiThread(() -> openProfile(e)); return; }
            runOnUiThread(() -> toast("That salon isn't on the square yet."));
        });
    }

    /* ================= VIEW someone else ================= */

    private void renderView() {
        masthead("Salon");
        if (viewEntry == null) { go(Screen.DISCOVER); return; }
        LinearLayout bar = row();
        bar.addView(btn("← Discover", false, () -> go(Screen.DISCOVER)), weight(40, 0, 4));
        boolean following = SalonStore.isFollowing(this, viewEntry.tokenid);
        bar.addView(btn(following ? "Unfollow" : "Follow", !following, () -> {
            if (following) SalonStore.unfollow(this, viewEntry.tokenid);
            else SalonStore.follow(this, viewEntry.tokenid, viewEntry.handle, viewEntry.url);
            render();
        }), weight(40, 4, 0));
        body.addView(bar, lp(0, 0, 0, 8));
        if (viewProfile == null) {
            if (viewFailed) {
                LinearLayout c = card();
                c.addView(Design.note(this, "Couldn't reach @" + viewEntry.handle + "'s page — their host may be offline."));
                LinearLayout er = row();
                er.addView(btn("Try again", true, () -> openProfile(viewEntry)), weight(48, 0, 4));
                er.addView(btn("Open in browser", false, () -> openUrl(viewEntry.url)), weight(48, 4, 0));
                c.addView(er, lp(0, 10, 0, 0));
                body.addView(c, lp(0, 0, 0, 12));
            } else body.addView(Design.note(this, "Loading @" + viewEntry.handle + "'s page…"), lp(0, 0, 0, 12));
            return;
        }
        String tipAddr = viewProfile.optString("tipaddr", "");
        String peerMsgpk = viewProfile.optString("msgpk", "");
        String peerMx = viewProfile.optString("mxaddr", "");
        boolean canMsg = !tipAddr.isEmpty() && !peerMsgpk.isEmpty();
        if (!tipAddr.isEmpty()) {
            LinearLayout arow = row();
            if (canMsg) arow.addView(btn("💬 Message", true, () -> {
                // Learn/refresh their Maxima address from their page. Sticky: an empty
                // value (stale fetch, or they're not advertising one right now) is a
                // no-op, so we never lose an address we already knew.
                MailDb.get(this).setMxAddr(peerMsgpk, peerMx);
                openThread(peerMsgpk, viewEntry.handle, viewProfile.optString("avatar", ""), tipAddr);
            }), weight(46, 0, 4));
            arow.addView(btn("💰 Tip", false, () -> tipDialog(viewEntry.handle, tipAddr)), weight(46, canMsg ? 4 : 0, 0));
            body.addView(arow, lp(0, 0, 0, 10));
        }
        renderProfilePage(viewProfile, false);
    }

    /* ================= HOME / My Salon ================= */

    private void renderHome() {
        if (!SalonStore.hasIdentity(this)) { masthead("The Salon"); renderOnboard(); return; }
        masthead("My Salon");
        renderProfilePage(buildProfileJson(), true);
    }

    /** The one renderer for any Salon — yours (mine=true) or someone else's. */
    private void renderProfilePage(JSONObject p, boolean mine) {
        LinearLayout headCard = card();
        String banner = p.optString("banner", "");
        if (!banner.isEmpty()) {
            ImageView bn = new ImageView(this); bn.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ImageLoader.loadFull(this, banner, bn);
            headCard.addView(bn, new LinearLayout.LayoutParams(-1, dp(130)));
        }
        LinearLayout idrow = row();
        idrow.addView(badgedAvatar(p.optString("avatar"), p.optString("handle"), p.optString("tokenid"), p.optString("webvalidate"), 66));
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(12), 0, 0, 0);
        col.addView(Design.text(this, p.optString("name"), 19, Design.INK(), Design.sansBold()));
        TextView h = Design.text(this, "@" + p.optString("handle"), 13, Design.ACCENT(), Design.mono());
        copyOnTap(h, "@" + p.optString("handle")); col.addView(h);
        idrow.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        headCard.addView(idrow, lp(0, banner.isEmpty() ? 0 : 10, 0, 6));
        if (!p.optString("bio").isEmpty()) headCard.addView(Design.body(this, p.optString("bio")), lp(0, 4, 0, 2));
        // Stats — the at-a-glance social summary. Counts come straight off the
        // profile; "following" is only meaningful (and known) on your own page.
        LinearLayout stats = row(); boolean anyStat = false;
        int nPosts = arrLen(p, "posts"), nHold = arrLen(p, "nfts"), nGal = arrLen(p, "gallery");
        if (nPosts > 0) { stats.addView(statPill(nPosts, "posts")); anyStat = true; }
        if (nHold  > 0) { stats.addView(statPill(nHold,  nHold == 1 ? "holding" : "holdings")); anyStat = true; }
        if (nGal   > 0) { stats.addView(statPill(nGal,   "media")); anyStat = true; }
        if (mine) { int nFol = SalonStore.follows(this).length(); if (nFol > 0) { stats.addView(statPill(nFol, "following")); anyStat = true; } }
        if (anyStat) {
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false); hs.addView(stats);   // never clip on narrow screens
            headCard.addView(hs, lp(0, 8, 0, 2));
        }
        // Freshness: when the page was last published, so you can tell if a Salon
        // has changed since you last looked (shown for your own page too).
        long upd = p.optLong("updated", 0);
        if (upd > 0) {
            String ago = Util.ago(upd);
            String freshness = ago.isEmpty() || ago.equalsIgnoreCase("now") ? "Updated just now" : "Updated " + ago + " ago";
            headCard.addView(Design.text(this, freshness, 10.5f, Design.DIM(), Design.mono()), lp(0, 6, 0, 0));
        }
        // web-validation shield (optional webvalidate URL in profile)
        final LinearLayout shieldSlot = new LinearLayout(this);
        headCard.addView(shieldSlot);
        String wv = p.optString("webvalidate", "");
        if (!wv.isEmpty()) {
            final String host = urlHost(wv);
            Boolean st = WebValidate.status(p.optString("tokenid"));
            if (Boolean.TRUE.equals(st)) shieldSlot.addView(verifiedBadge(host));
            WebValidate.ensure(this, p.optString("tokenid"), wv, () -> { shieldSlot.removeAllViews(); if (Boolean.TRUE.equals(WebValidate.status(p.optString("tokenid")))) shieldSlot.addView(verifiedBadge(host)); });
        }
        body.addView(headCard, lp(0, 0, 0, 12));

        if (mine) {
            LinearLayout actions = row();
            actions.addView(btn("Edit my page", true, () -> go(Screen.EDIT)), weight(46, 0, 4));
            actions.addView(btn("Publish", false, () -> publishSalon(null)), weight(46, 4, 0));
            body.addView(actions, lp(0, 0, 0, 8));
            String myUrl = SalonStore.get(this, "profileUrl");
            if (!myUrl.isEmpty()) {
                LinearLayout share = row();
                share.addView(btn("Share my page", false, () -> sharePage(myUrl)), weight(46, 0, 4));
                if (myUrl.startsWith("http")) share.addView(btn("Open in browser", false, () -> openUrl(myUrl)), weight(46, 4, 0));
                body.addView(share, lp(0, 0, 0, 12));
            }
        }

        if (!p.optString("about").isEmpty()) {
            LinearLayout c = card(); c.addView(Design.lot(this, "About"));
            c.addView(Design.body(this, p.optString("about")), lp(0, 6, 0, 0));
            body.addView(c, lp(0, 0, 0, 12));
        }

        JSONArray links = p.optJSONArray("links");
        if (links != null && links.length() > 0) {
            LinearLayout c = card(); c.addView(Design.lot(this, "Links"));
            for (int i = 0; i < links.length(); i++) {
                JSONObject l = links.optJSONObject(i); if (l == null) continue;
                c.addView(linkRow(l.optString("label", l.optString("url")), l.optString("url")));
            }
            body.addView(c, lp(0, 0, 0, 12));
        }

        JSONArray gal = p.optJSONArray("gallery");
        if (gal != null && gal.length() > 0) {
            LinearLayout c = card(); c.addView(Design.lot(this, "Gallery"));
            // Bound the gallery to a scroll window so a large collection doesn't
            // stretch the page. Short galleries measure under the cap and just show.
            LinearLayout inner = new LinearLayout(this); inner.setOrientation(LinearLayout.VERTICAL);
            renderGallery(inner, gal);
            c.addView(boundedBox(inner, 560), lp(0, 2, 0, 0));
            body.addView(c, lp(0, 0, 0, 12));
        }

        JSONArray posts = p.optJSONArray("posts");
        if (posts != null && posts.length() > 0) {
            LinearLayout c = card();
            LinearLayout ph = row();
            ph.addView(Design.lot(this, "Posts"), new LinearLayout.LayoutParams(0, -2, 1));
            ph.addView(Design.text(this, String.valueOf(posts.length()), 11f, Design.DIM(), Design.mono()));
            c.addView(ph);
            // Newest first.
            final List<JSONObject> plist = new ArrayList<>();
            for (int i = posts.length() - 1; i >= 0; i--) { JSONObject po = posts.optJSONObject(i); if (po != null) plist.add(po); }
            // A short page renders inline; once it would run long, the Posts section
            // becomes a fixed-height window with its own scrollbar so the page as a
            // whole never gets unruly, no matter how many posts accumulate.
            final int INLINE_MAX = 4;
            if (plist.size() <= INLINE_MAX) {
                for (int i = 0; i < plist.size(); i++) c.addView(buildProfilePost(plist.get(i), i > 0));
            } else {
                BoundedRecycler rv = new BoundedRecycler(this, dp(480));
                ImpAdapter<JSONObject> ad = new ImpAdapter<JSONObject>() {
                    @Override View bind(JSONObject post, int pos) { return buildProfilePost(post, pos > 0); }
                };
                rv.setAdapter(ad); ad.set(plist);
                c.addView(rv, lp(0, 6, 0, 0));
                c.addView(Design.text(this, "▲ scroll within — showing all " + plist.size() + " posts", 9.5f, Design.DIM(), Design.mono()), lp(0, 6, 0, 0));
            }
            body.addView(c, lp(0, 0, 0, 12));
        }

        JSONArray nfts = p.optJSONArray("nfts");
        // Bind proofs to the ON-CHAIN discovered tokenid when viewing others, not the
        // profile's self-declared one — else a profile could paste someone else's valid proofs.
        String bindTid = (!mine && viewEntry != null) ? viewEntry.tokenid : p.optString("tokenid");
        if (nfts != null && nfts.length() > 0) renderNftShowcase(nfts, bindTid);

        if (mine && emptyProfile(p)) {
            LinearLayout nud = card(); nud.setBackground(Design.dashed(this, Design.CARD(), Design.DIM()));
            nud.addView(Design.note(this, "Your page is empty. Add a bio, photos, video, music and posts — then publish to share it."));
            nud.addView(btn("Edit your page", true, () -> go(Screen.EDIT)), lph(46, 0, 10, 0, 0));
            body.addView(nud, lp(0, 0, 0, 12));
        }

        if (mine) {
            LinearLayout meta = card(); meta.addView(Design.lot(this, "Your page"));
            copyRow(meta, "Identity token", p.optString("tokenid"));
            meta.addView(linkRow("Profile URL", SalonStore.get(this, "profileUrl")));
            body.addView(meta, lp(0, 0, 0, 12));
        }
    }

    private int arrLen(JSONObject p, String key) { JSONArray a = p.optJSONArray(key); return a == null ? 0 : a.length(); }

    /** A number + label chip for the profile stats row (e.g. "12 POSTS"). */
    private View statPill(int n, String label) {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.HORIZONTAL); p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1));
        p.setPadding(dp(9), dp(5), dp(9), dp(6));
        p.addView(Design.text(this, String.valueOf(n), 13, Design.INK(), Design.sansBold()));
        TextView l = Design.text(this, label.toUpperCase(), 9f, Design.DIM(), Design.sansBold()); l.setLetterSpacing(0.08f); l.setPadding(dp(4), 0, 0, 0);
        p.addView(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(8);
        p.setLayoutParams(lp);
        return p;
    }

    /** One post block for the profile Posts section (inline or windowed row binder). */
    private View buildProfilePost(JSONObject post, boolean divider) {
        LinearLayout pc = new LinearLayout(this); pc.setOrientation(LinearLayout.VERTICAL);
        pc.setPadding(0, dp(8), 0, dp(8));
        if (divider) pc.addView(Design.rule(this, 1), new LinearLayout.LayoutParams(-1, dp(1)));
        String when = Util.ago(post.optLong("ts", 0));
        if (!when.isEmpty()) pc.addView(Design.text(this, when, 10f, Design.DIM(), Design.mono()), lp(0, 6, 0, 0));
        if (!post.optString("text").isEmpty()) pc.addView(Design.body(this, post.optString("text")), lp(0, when.isEmpty() ? 6 : 2, 0, 4));
        addPostMedia(pc, post);
        return pc;
    }

    /** A renderable icon for a showcased holding, or null. Resolves the stored
     *  metadata icon via {@link IconResolver} (unwraps &lt;artimage&gt;/base64/data:/http/
     *  ipfs), else the first PUBLISHED edition plate ({@code editions[0].url} — already
     *  a data: URI for embedded StateNFTs, so this backfills existing listings for the
     *  owner AND remote viewers). null means only a live coin-state read (owner) can
     *  produce one — see {@link #liveFillNftImage}. */
    private String nftIcon(JSONObject n) {
        String icon = IconResolver.resolve(n.optString("image"));
        if (icon != null) return icon;
        JSONArray eds = n.optJSONArray("editions");
        if (eds != null && eds.length() > 0) {
            JSONObject e0 = eds.optJSONObject(0);
            String u = e0 == null ? "" : e0.optString("url");
            if (!u.isEmpty()) return u;
        }
        return null;
    }

    /** Owner-only fallback: pull the embedded plate from the first held coin's
     *  state[1] and fill {@code iv}. For legacy holdings stored with no icon and no
     *  captured editions; reuses the working {@link #enumerateEditions} pipeline. */
    private void liveFillNftImage(final ImageView iv, final String tid, final boolean full) {
        if (!nodeUp || tid == null || tid.isEmpty()) return;
        final String cachedUrl = mLiveIcon.get(tid);
        if (cachedUrl != null) {   // resolved this session already — no repeat edition scan on re-render
            if (full) ImageLoader.loadFull(this, cachedUrl, iv); else ImageLoader.loadOver(this, cachedUrl, iv);
            return;
        }
        enumerateEditions(tid, editions -> {
            if (editions == null || editions.isEmpty()) return;
            final String u = editions.get(0).optString("url");
            if (u.isEmpty()) return;
            mLiveIcon.put(tid, u);
            runOnUiThread(() -> { if (full) ImageLoader.loadFull(this, u, iv); else ImageLoader.loadOver(this, u, iv); });
        });
    }

    /** Render showcased holdings; the VIEWER's node verifies each live. Tap a row to
     *  expand its details + a manual re-verify. */
    private void renderNftShowcase(JSONArray nfts, String profileTokenid) {
        LinearLayout c = card();
        LinearLayout hh = row();
        hh.addView(Design.lot(this, "Holdings"), new LinearLayout.LayoutParams(0, -2, 1));
        hh.addView(Design.text(this, String.valueOf(nfts.length()), 11f, Design.DIM(), Design.mono()));
        c.addView(hh);
        // Rows go into their own container so a long collection can be a bounded
        // scroll window — but only while nothing is expanded, so opening a holding
        // still shows its full artwork and editions at full size.
        final LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        final String bindHex = NftProof.hexOf(profileTokenid);
        for (int i = 0; i < nfts.length(); i++) {
            final JSONObject n = nfts.optJSONObject(i); if (n == null) continue;
            final String tid = n.optString("tokenid");
            final boolean open = tid != null && tid.equals(expandedNft);
            LinearLayout r = row(); r.setPadding(0, dp(8), 0, dp(8)); r.setClickable(true);
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackgroundColor(0xFF141310);
            String iconSrc = nftIcon(n);
            if (iconSrc != null) ImageLoader.loadOver(this, iconSrc, iv);
            else liveFillNftImage(iv, tid, false);   // embedded plate lives in coin state[1] — pull it live (owner)
            r.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(12), 0, dp(8), 0);
            col.addView(Design.text(this, n.optString("name", Util.shorten(tid)), 15, Design.INK(), Design.sansBold()));
            final TextView badge = Design.text(this, "verifying on your node…", 11f, Design.DIM(), Design.mono());
            col.addView(badge);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(Design.text(this, open ? "▾" : "▸", 15, Design.DIM(), Design.mono()));
            r.setOnClickListener(v -> { expandedNft = open ? null : tid; render(); });
            rows.addView(r);
            verifyInto(n, bindHex, badge);

            if (open) {   // expanded: full main icon (tap→fullscreen), ids, verify, tappable editions gallery
                LinearLayout d = new LinearLayout(this); d.setOrientation(LinearLayout.VERTICAL);
                d.setPadding(dp(4), dp(2), dp(4), dp(10));
                final String iconUrl = nftIcon(n);
                ImageView hero = new ImageView(this); hero.setScaleType(ImageView.ScaleType.FIT_CENTER); hero.setBackgroundColor(0xFF141310);
                d.addView(hero, new LinearLayout.LayoutParams(-1, dp(300)));
                if (iconUrl != null) {
                    ImageLoader.loadFull(this, iconUrl, hero); hero.setClickable(true); hero.setOnClickListener(v -> openImage(iconUrl));
                    d.addView(Design.text(this, "tap the artwork to view it full-screen", 10.5f, Design.DIM(), Design.mono()), lp(0, 4, 0, 2));
                } else {
                    liveFillNftImage(hero, tid, true);   // embedded plate from coin state[1] (owner)
                }
                copyRow(d, "Token id", tid);
                if (!n.optString("coinid").isEmpty()) copyRow(d, "Coin id", n.optString("coinid"));
                final TextView vline = Design.text(this, "verifying on your node…", 12f, Design.DIM(), Design.mono());
                d.addView(vline, lp(0, 8, 0, 6));
                d.addView(btn("Verify again", false, () -> verifyInto(n, bindHex, vline, true)), lph(44, 0, 4, 0, 0));
                rows.addView(d);
                verifyInto(n, bindHex, vline);
                // Editions: prefer the PUBLISHED set the owner captured at prove
                // time (so a remote VIEWER sees the full suite, not just the top
                // icon). Fall back to a live scan of THIS node for the owner's own
                // page (or legacy items published before editions were captured).
                JSONArray pubEds = n.optJSONArray("editions");
                if (pubEds != null && pubEds.length() > 0) {
                    java.util.List<JSONObject> eds = new java.util.ArrayList<>();
                    for (int j = 0; j < pubEds.length(); j++) { JSONObject e = pubEds.optJSONObject(j); if (e != null) eds.add(e); }
                    renderEditionTiles(d, eds, eds.size() + " STATE IMAGE" + (eds.size() == 1 ? "" : "S") + " IN THIS COLLECTION · TAP TO ENLARGE");
                } else {
                    renderEditionArt(d, tid);   // live gallery of what this node holds
                }
            }
        }
        // Browsing many holdings → bounded scroll window; expanded → full height.
        if (nfts.length() > 3 && expandedNft == null) c.addView(boundedBox(rows, 520), lp(0, 2, 0, 0));
        else c.addView(rows);
        body.addView(c, lp(0, 0, 0, 12));
    }

    /** Verify a showcase holding on THIS device's node; write the outcome (with the
     *  failing-stage reason) into {@code tv}. Green on success, vermilion on failure. */
    private final java.util.Set<String> proofRefreshed = new java.util.HashSet<>();

    private void verifyInto(final JSONObject n, final String bindHex, final TextView tv) {
        verifyInto(n, bindHex, tv, false);
    }

    /** @param force bypass the session cache and re-run the node check ("Verify again"). */
    private void verifyInto(final JSONObject n, final String bindHex, final TextView tv, boolean force) {
        if (!nodeUp) { tv.setText("connect a node to verify"); tv.setTextColor(Design.DIM()); return; }
        final String tid = n.optString("tokenid");
        // Key by tid AND the binding profile: the same NFT on a different profile binds
        // to a different tokenid, so its frozen proof verdict differs — never share it.
        final String vkey = tid + "|" + (bindHex == null ? "" : bindHex);
        if (!force && mVerifyText.containsKey(vkey)) {   // resolved this session — paint from cache, no node call
            tv.setText(mVerifyText.get(vkey)); tv.setTextColor(mVerifyColor.get(vkey));
            return;
        }
        tv.setText("verifying on your node…"); tv.setTextColor(Design.DIM());
        // 1) LIVE check first (Axe-S3 style): if THIS node holds an unspent coin of the token,
        //    it's verified now — this is you viewing your own page, and it never goes stale like
        //    the frozen coinexport proof (which reports valid=false once the MMR has grown).
        //    The "do I hold it?" fast-path is ONLY sound on YOUR OWN page: if you hold
        //    the token you ARE the owner. On a STRANGER's page it would paint YOUR
        //    holding as THEIRS (any shared/fungible/multi-holder token you also hold)
        //    and skip the identity binding — so strangers always go through the
        //    trustless bound proof, which ties the coin to the profile's tokenid.
        boolean ownPage = bindHex != null
                && bindHex.equalsIgnoreCase(NftProof.hexOf(SalonStore.get(this, "tokenid")));
        if (ownPage) {
            NftProof.holds(node, tid, (held, coin) -> runOnUiThread(() -> {
                if (held) {
                    setVerify(vkey, tv, "✓ VERIFIED HOLDING", 0xFF1F7A3F);
                    maybeRefreshProof(n, bindHex);   // regenerate the stored proof so the PUBLISHED one stops aging out
                    return;
                }
                verifyBound(n, bindHex, tv);   // own page but not currently held → frozen proof
            }));
        } else {
            verifyBound(n, bindHex, tv);       // stranger: only the trustless identity-bound proof
        }
    }

    /** Paint a verify outcome into {@code tv} AND cache it under {@code vkey} (tid|bindHex)
     *  so a re-render repaints from the cache instead of re-calling the node. */
    private void setVerify(String vkey, TextView tv, String text, int color) {
        mVerifyText.put(vkey, text); mVerifyColor.put(vkey, color);
        tv.setText(text); tv.setTextColor(color);
    }

    /** The trustless frozen proof: the coin is bound to the profile's tokenid by a
     *  signature, so a stranger cannot replay someone else's holding onto their page. */
    private void verifyBound(final JSONObject n, final String bindHex, final TextView tv) {
        final String vkey = n.optString("tokenid") + "|" + (bindHex == null ? "" : bindHex);
        NftProof.verify(node, n, bindHex, (ok, reason, stateImg) -> runOnUiThread(() -> {
            setVerify(vkey, tv, ok ? "✓ VERIFIED HOLDING" : "✕ " + reason, ok ? 0xFF1F7A3F : 0xFFB4462A);
        }));
    }

    /** Owner still holds the coin → regenerate the coinexport proof (once per session per token)
     *  and update the stored showcase item, so the next Publish carries a FRESH proof that
     *  strangers' nodes can verify. Silent; the live check already showed the green tick. */
    private void maybeRefreshProof(final JSONObject n, final String bindHex) {
        final String tid = n.optString("tokenid");
        if (tid.isEmpty() || bindHex == null || bindHex.isEmpty() || !proofRefreshed.add(tid)) return;
        NftProof.generate(node, tid, bindHex, new NftProof.Gen() {
            @Override public void ok(JSONObject item) {
                try { item.put("name", n.optString("name")); item.put("image", n.optString("image")); } catch (Exception ignored) {}
                // Refresh the published editions too, so an already-proven holding
                // (added before editions were captured) gains the full suite the
                // next time the owner opens their own page, and stays current.
                captureEditions(tid, editions -> {
                    try { item.put("editions", editions); } catch (Exception ignored) {}
                    JSONArray arr = SalonStore.arr(MainActivity.this, "nfts");
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null && tid.equalsIgnoreCase(o.optString("tokenid"))) { try { arr.put(i, item); } catch (Exception ignored) {} SalonStore.setArr(MainActivity.this, "nfts", arr); break; }
                    }
                });
            }
            @Override public void fail(String message) {}
        });
    }

    private interface EditionsCb { void ready(java.util.List<JSONObject> editions); }

    /** Enumerate the StateNFT editions THIS node holds for a token, as {url,caption}
     *  tiles. Handles BOTH modes: embedded state art, and url-mode
     *  ({@code base + index + ext}) collections like "gallery bibeau". base/ext from
     *  the token metadata (balance); index from each coin's state port 0. */
    private void enumerateEditions(final String tokenid, final EditionsCb cb) {
        if (!nodeUp || tokenid == null || tokenid.isEmpty()) { cb.ready(java.util.Collections.emptyList()); return; }
        node.cmd("balance tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject bal) {
                String base = "", ext = ".png";
                JSONArray br = bal.optJSONArray("response");
                JSONObject e0 = br == null || br.length() == 0 ? null : br.optJSONObject(0);
                JSONObject tk = e0 == null ? null : e0.optJSONObject("token");
                JSONObject src = tk != null && tk.opt("name") instanceof JSONObject ? tk.optJSONObject("name") : tk;
                if (src != null && tk != null) {
                    base = firstNonEmpty(src.optString("base", ""), tk.optString("base", ""));
                    ext = firstNonEmpty(src.optString("ext", ""), tk.optString("ext", ""), ".png");
                }
                final String fbase = base, fext = ext;
                node.cmd("coins relevant:true tokenid:" + tokenid, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject r) {
                        JSONArray arr = r.optJSONArray("response");
                        final java.util.List<JSONObject> editions = new java.util.ArrayList<>();
                        final java.util.HashSet<String> seen = new java.util.HashSet<>();
                        if (arr != null) for (int i = 0; i < arr.length(); i++) {
                            JSONObject coin = arr.optJSONObject(i); if (coin == null) continue;
                            String u = NftProof.editionImageUrl(coin, fbase, fext);
                            if (u.isEmpty() || !seen.add(u)) continue;
                            String idx = NftProof.state(coin, 0);
                            try { JSONObject it = new JSONObject(); it.put("url", u);
                                it.put("caption", idx != null && idx.matches("[0-9]+") ? "Edition #" + idx : "Edition"); editions.add(it); }
                            catch (Exception ignored) {}
                        }
                        cb.ready(editions);
                    }
                    @Override public void onError(String m) { cb.ready(java.util.Collections.emptyList()); }
                });
            }
            @Override public void onError(String m) { cb.ready(java.util.Collections.emptyList()); }
        });
    }

    /** A 2-up grid of edition tiles; each taps into a fullscreen swipeable carousel. */
    private void renderEditionTiles(final LinearLayout parent, final java.util.List<JSONObject> editions, final String label) {
        if (editions.isEmpty()) return;
        parent.addView(Design.text(this, label, 9f, Design.DIM(), Design.sansBold()), lp(0, 12, 0, 6));
        LinearLayout g = null;
        for (int i = 0; i < editions.size(); i++) {
            final int idx = i;
            if (i % 2 == 0) { g = row(); parent.addView(g, lp(0, 0, 0, 6)); }
            ImageView t = new ImageView(this); t.setScaleType(ImageView.ScaleType.CENTER_CROP); t.setBackgroundColor(0xFF141310);
            t.setClickable(true); t.setOnClickListener(v -> openCarousel(editions, idx));
            ImageLoader.loadOver(this, editions.get(i).optString("url"), t);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(168), 1); tp.setMargins(0, 0, i % 2 == 0 ? dp(6) : 0, 0);
            g.addView(t, tp);
        }
        if (editions.size() % 2 == 1) g.addView(new View(this), new LinearLayout.LayoutParams(0, dp(168), 1));
    }

    /** Live editions THIS node holds — the owner viewing their own page. Silent for a
     *  viewer who holds none; a stranger's view uses the PUBLISHED editions instead. */
    private void renderEditionArt(final LinearLayout parent, final String tokenid) {
        enumerateEditions(tokenid, editions -> {
            if (editions.isEmpty()) return;
            runOnUiThread(() -> renderEditionTiles(parent, editions,
                    editions.size() + " STATE IMAGE" + (editions.size() == 1 ? "" : "S") + " YOU HOLD · TAP TO ENLARGE"));
        });
    }

    /** An edition image url small enough to publish in profile.json (skip embedded
     *  data: URIs that would bloat the page; keep remote refs). */
    private boolean isPublishableEditionUrl(String u) {
        if (u == null || u.length() >= 1024) return false;
        String s = u.trim().toLowerCase();
        return s.startsWith("http://") || s.startsWith("https://") || s.startsWith("ipfs:")
                || s.startsWith("ar://") || s.startsWith("relay1:") || s.startsWith("mx1:");
    }

    private interface JsonArrayCb { void ready(JSONArray editions); }

    /** Enumerate this node's editions of a token and hand back a publishable JSONArray
     *  (remote-url editions only, capped) to store in a showcase item. */
    private void captureEditions(final String tokenid, final JsonArrayCb done) {
        enumerateEditions(tokenid, editions -> {
            JSONArray ed = new JSONArray();
            for (JSONObject e : editions) {
                if (ed.length() >= 120) break;
                if (isPublishableEditionUrl(e.optString("url"))) ed.put(e);
            }
            done.ready(ed);
        });
    }

    /** Before publishing, stamp a FRESH edition list onto every proven holding so a
     *  remote viewer sees the whole collection, not just the icon. Enumerates each
     *  token's editions this node holds, updates the stored nfts, then runs `done`
     *  on the UI thread. This is why editions publish reliably regardless of what
     *  the owner happened to open first. Node-async → callback-chained. */
    private void refreshEditionsThen(final Runnable done) {
        final JSONArray arr = SalonStore.arr(this, "nfts");
        if (!nodeUp || arr.length() == 0) { done.run(); return; }
        final java.util.concurrent.atomic.AtomicInteger pending =
                new java.util.concurrent.atomic.AtomicInteger(arr.length());
        final java.util.concurrent.atomic.AtomicBoolean changed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        // fired guards a SINGLE completion — via all-done OR the timeout below —
        // so the publish is never stalled by a slow node, and a late edition
        // callback can't mutate the array while finish is serialising it.
        final java.util.concurrent.atomic.AtomicBoolean fired =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable finish = () -> {
            if (fired.compareAndSet(false, true)) {
                if (changed.get()) SalonStore.setArr(MainActivity.this, "nfts", arr);
                runOnUiThread(done);
            }
        };
        // Proceed after 12s with whatever we captured; the rest refresh next publish.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(finish, 12_000);
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            final String tid = o == null ? "" : o.optString("tokenid");
            if (tid.isEmpty()) { if (pending.decrementAndGet() == 0) finish.run(); continue; }
            captureEditions(tid, editions -> {
                if (!fired.get() && editions.length() > 0) {
                    try { o.put("editions", editions); changed.set(true); } catch (Exception ignored) {}
                }
                if (pending.decrementAndGet() == 0) finish.run();
            });
        }
    }

    /* ---------------- prove & showcase a holding ---------------- */

    private void pickNftDialog() {
        if (!nodeUp) { toast("Connect the node."); return; }
        toast("Reading your wallet…");
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray arr = j.optJSONArray("response");
                java.util.List<JSONObject> assets = new java.util.ArrayList<>();
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject row = arr.optJSONObject(i); if (row == null) continue;
                    String tid = row.optString("tokenid", ""); if (tid.isEmpty() || tid.equals("0x00")) continue;
                    Object tok = row.opt("token"); String name = "", image = "";
                    if (tok instanceof JSONObject) {
                        JSONObject tm = (JSONObject) tok;
                        Object nm = tm.opt("name");
                        // Atelier/StateNFT metadata is an envelope: the minted JSON (name, url,
                        // icon, mode, …) lives under token.name. Read the icon from there, then
                        // resolve <artimage>/base64/data:/http/ipfs into a renderable string.
                        JSONObject md = nm instanceof JSONObject ? (JSONObject) nm : tm;
                        name = nm instanceof JSONObject ? md.optString("name", "") : tm.optString("name", "");
                        String rawIcon = firstNonEmpty(md.optString("url"), md.optString("icon"), md.optString("image"),
                                tm.optString("url"), tm.optString("icon"), tm.optString("image"));
                        String resolved = IconResolver.resolve(rawIcon);
                        image = resolved != null ? resolved : "";
                    } else if (tok instanceof String) name = (String) tok;
                    if (name.isEmpty()) name = Util.shorten(tid);
                    try { JSONObject a = new JSONObject(); a.put("tokenid", tid); a.put("name", name); a.put("image", image); assets.add(a); } catch (Exception ignored) {}
                }
                runOnUiThread(() -> showAssetPicker(assets));
            }
            @Override public void onError(String m) { runOnUiThread(() -> toast("Balance failed: " + m)); }
        });
    }

    private void showAssetPicker(java.util.List<JSONObject> assets) {
        if (assets.isEmpty()) { toast("No tokens/NFTs in your wallet to prove."); return; }
        CharSequence[] items = new CharSequence[assets.size()];
        for (int i = 0; i < assets.size(); i++) items[i] = assets.get(i).optString("name");
        new android.app.AlertDialog.Builder(this).setTitle("Prove a holding").setItems(items, (d, which) -> proveNft(assets.get(which))).show();
    }

    private void proveNft(JSONObject asset) {
        String myTid = SalonStore.get(this, "tokenid");
        if (myTid.isEmpty()) { toast("Claim your Salon first."); return; }
        toast("Generating on-chain proof…");
        NftProof.generate(node, asset.optString("tokenid"), NftProof.hexOf(myTid), new NftProof.Gen() {
            @Override public void ok(JSONObject item) {
                try { item.put("name", asset.optString("name")); item.put("image", asset.optString("image")); } catch (Exception ignored) {}
                // Capture the editions this node holds so a remote viewer sees the
                // whole collection, not just the top icon. Then store + publish.
                captureEditions(asset.optString("tokenid"), editions -> {
                    try {
                        item.put("editions", editions);
                        // Embedded StateNFT with no small metadata icon: seed the showcase
                        // icon from the first edition's plate (a data: URI) so it renders and
                        // gets baked into profile.json for remote viewers on publish.
                        if (item.optString("image").isEmpty() && editions != null && editions.length() > 0) {
                            JSONObject e0 = editions.optJSONObject(0);
                            if (e0 != null && !e0.optString("url").isEmpty()) item.put("image", e0.optString("url"));
                        }
                    } catch (Exception ignored) {}
                    runOnUiThread(() -> { JSONArray a = SalonStore.arr(MainActivity.this, "nfts"); a.put(item); SalonStore.setArr(MainActivity.this, "nfts", a); toast("Proof added — Save & publish to show it."); render(); });
                });
            }
            @Override public void fail(String msg) { runOnUiThread(() -> toast("Couldn't prove: " + msg)); }
        });
    }

    private String firstNonEmpty(String... xs) { for (String x : xs) if (x != null && !x.isEmpty()) return x; return ""; }

    /* ================= EDIT hub ================= */

    private EditText edName, edBio, edAbout, edAvatar, edBanner, edWebvalidate; private TextView profStatus;

    /** Persist the Edit text fields to the local draft so an in-Edit render() (add link,
     *  upload, delete a row) or navigating away never discards what the user typed. */
    private void commitEditFields() {
        if (edName == null) return;
        SalonStore.put(this, "name", text(edName));
        SalonStore.put(this, "bio", text(edBio));
        SalonStore.put(this, "about", text(edAbout));
        SalonStore.put(this, "avatar", text(edAvatar));
        SalonStore.put(this, "banner", text(edBanner));
        SalonStore.put(this, "webvalidate", text(edWebvalidate));
    }

    private void renderEdit() {
        masthead("Edit page");
        body.addView(btn("← My Salon", false, () -> go(Screen.HOME)), lph(44, 0, 0, 0, 10));
        JSONObject me = SalonStore.me(this);
        LinearLayout who = card(); who.addView(Design.lot(this, "You"));
        edName = field(who, "Display name", me.optString("name"), false, "");
        edBio = field(who, "Bio (one line)", me.optString("bio"), false, "");
        edAbout = fieldMulti(who, "About (long-form)", me.optString("about"));
        EditText name = edName, bio = edBio, about = edAbout;   // aliases for the existing Save handler below
        edAvatar = field(who, "Avatar URL", me.optString("avatar"), false, "https://…");
        who.addView(btn("Upload avatar", false, () -> pickMedia(PICK_AVATAR, "image/*")), lph(42, 0, 4, 0, 6));
        edBanner = field(who, "Banner URL", me.optString("banner"), false, "https://…");
        who.addView(btn("Upload banner", false, () -> pickMedia(PICK_BANNER, "image/*")), lph(42, 0, 4, 0, 2));
        body.addView(who, lp(0, 0, 0, 12));

        // Verification — the anti-impersonation defence (handles aren't unique).
        LinearLayout ver = card(); ver.addView(Design.lot(this, "Verify (prove it's you)"));
        ver.addView(Design.note(this, "Handles aren't unique — anyone can call themselves @" + me.optString("handle") + ". To prove the real you, host a file on YOUR domain containing your token id, then paste its URL. A ✓ badge then shows on your card and page."), lp(0, 6, 0, 4));
        copyRow(ver, "Your token id", me.optString("tokenid"));
        edWebvalidate = field(ver, "Web-verification URL", me.optString("webvalidate"), false, "https://eurobuddha.com/salon.txt");
        body.addView(ver, lp(0, 0, 0, 12));

        // Links
        LinearLayout linksCard = card(); linksCard.addView(Design.lot(this, "Links"));
        JSONArray links = SalonStore.arr(this, "links");
        for (int i = 0; i < links.length(); i++) {
            JSONObject l = links.optJSONObject(i); final int idx = i;
            LinearLayout r = row();
            r.addView(Design.text(this, l.optString("label"), 13, Design.INK(), Design.sansBold()), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(btn("✕", false, () -> { JSONArray a = SalonStore.arr(this, "links"); a = removeAt(a, idx); SalonStore.setArr(this, "links", a); render(); }), new LinearLayout.LayoutParams(dp(44), dp(38)));
            linksCard.addView(r, lp(0, 4, 0, 4));
        }
        linksCard.addView(btn("+ Add link", false, this::addLinkDialog), lph(42, 0, 6, 0, 2));
        body.addView(linksCard, lp(0, 0, 0, 12));

        // Gallery
        LinearLayout galCard = card(); galCard.addView(Design.lot(this, "Gallery — photos, video, music"));
        JSONArray gal = SalonStore.arr(this, "gallery");
        for (int i = 0; i < gal.length(); i++) {
            JSONObject m = gal.optJSONObject(i); if (m == null) continue; final int idx = i;
            String type = m.optString("type", "image"), cap = m.optString("caption", "");
            LinearLayout r = row(); r.setPadding(0, dp(6), 0, dp(6));
            TextView kind = Design.text(this, type.equals("audio") ? "♪" : type.equals("video") ? "▶" : "▣", 18, Design.ACCENT(), Design.sansBold());
            r.addView(kind, new LinearLayout.LayoutParams(dp(28), -2));
            LinearLayout cc = new LinearLayout(this); cc.setOrientation(LinearLayout.VERTICAL); cc.setPadding(dp(8), 0, 0, 0);
            cc.addView(Design.text(this, cap.isEmpty() ? "(untitled)" : cap, 13.5f, Design.INK(), Design.sansBold()));
            cc.addView(Design.text(this, type.toUpperCase(), 9f, Design.DIM(), Design.sansBold()));
            r.addView(cc, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(btn("Rename", false, () -> renameGalleryItem(idx)), new LinearLayout.LayoutParams(dp(88), dp(38)));
            r.addView(btn("✕", false, () -> { JSONArray a = removeAt(SalonStore.arr(this, "gallery"), idx); SalonStore.setArr(this, "gallery", a); render(); }), new LinearLayout.LayoutParams(dp(44), dp(38)));
            galCard.addView(r);
            galCard.addView(Design.rule(this, 1), new LinearLayout.LayoutParams(-1, dp(1)));
        }
        LinearLayout addRow = row();
        addRow.addView(btn("+ Photo", false, () -> pickMedia(PICK_GALLERY_IMG, "image/*")), weight(42, 0, 4));
        addRow.addView(btn("+ Video", false, () -> pickMedia(PICK_GALLERY_VID, "video/*")), weight(42, 4, 4));
        addRow.addView(btn("+ Music", false, () -> pickMedia(PICK_GALLERY_AUD, "audio/*")), weight(42, 4, 0));
        galCard.addView(addRow, lp(0, 6, 0, 0));
        body.addView(galCard, lp(0, 0, 0, 12));

        // Posts
        LinearLayout postCard = card(); postCard.addView(Design.lot(this, "Posts"));
        postCard.addView(btn("+ New post", false, this::newPostDialog), lph(42, 0, 6, 0, 2));
        body.addView(postCard, lp(0, 0, 0, 12));

        // Showcase NFTs — proven holdings (no faking)
        LinearLayout nftCard = card(); nftCard.addView(Design.lot(this, "Showcase — proven holdings"));
        nftCard.addView(Design.note(this, "Prove you HOLD an NFT or token and show it on your page. Viewers' own nodes verify it live — a hosted list can't fake it, and the badge vanishes if you sell."), lp(0, 4, 0, 6));
        JSONArray nfts = SalonStore.arr(this, "nfts");
        for (int i = 0; i < nfts.length(); i++) {
            JSONObject n = nfts.optJSONObject(i); if (n == null) continue; final int idx = i;
            LinearLayout r = row(); r.setPadding(0, dp(6), 0, dp(6));
            r.addView(Design.text(this, n.optString("name", Util.shorten(n.optString("tokenid"))), 13.5f, Design.INK(), Design.sansBold()), new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(btn("✕", false, () -> { SalonStore.setArr(this, "nfts", removeAt(SalonStore.arr(this, "nfts"), idx)); render(); }), new LinearLayout.LayoutParams(dp(44), dp(38)));
            nftCard.addView(r);
        }
        nftCard.addView(btn("+ Prove & add a holding", false, this::pickNftDialog), lph(44, 0, 6, 0, 2));
        body.addView(nftCard, lp(0, 0, 0, 12));

        profStatus = Design.note(this, ""); body.addView(profStatus, lp(0, 4, 0, 0));
        body.addView(btn("Save & publish", true, () -> {
            SalonStore.put(this, "name", text(name)); SalonStore.put(this, "bio", text(bio));
            SalonStore.put(this, "about", text(about));
            SalonStore.put(this, "avatar", text(edAvatar)); SalonStore.put(this, "banner", text(edBanner));
            SalonStore.put(this, "webvalidate", text(edWebvalidate));
            hostProfile(profStatus, () -> { publishSalon(profStatus); toast("Page saved & published."); go(Screen.HOME); });
        }), lph(52, 0, 8, 0, 0));
    }

    private void addLinkDialog() {
        LinearLayout box = dialogBox();
        EditText label = field(box, "Label", "", false, "My blog");
        EditText url = field(box, "URL", "", false, "https://…");
        showDialog("Add link", box, "Add", () -> {
            if (text(url).isEmpty()) return;
            JSONArray a = SalonStore.arr(this, "links");
            try { JSONObject o = new JSONObject(); o.put("label", text(label).isEmpty() ? text(url) : text(label)); o.put("url", text(url)); a.put(o); } catch (Exception ignored) {}
            SalonStore.setArr(this, "links", a); render();
        });
    }

    private void newPostDialog() {
        LinearLayout box = dialogBox();
        EditText textF = fieldMulti(box, "Say something", "");
        showDialog("New post", box, "Post", () -> {
            JSONArray a = SalonStore.arr(this, "posts");
            try { JSONObject o = new JSONObject(); o.put("ts", System.currentTimeMillis() / 1000); o.put("text", text(textF)); a.put(o); } catch (Exception ignored) {}
            SalonStore.setArr(this, "posts", a);
            toast("Posted — hit Save & publish to push it live."); render();
        });
    }

    /* ================= media: pick / upload / play ================= */

    private static final int PICK_AVATAR = 41, PICK_BANNER = 42, PICK_GALLERY_IMG = 43, PICK_GALLERY_VID = 44, PICK_GALLERY_AUD = 45, PICK_DM_IMG = 46, SAVE_TEXT = 51, OPEN_BACKUP = 52;
    private String pendingSaveText;   // content queued for a SAF "Save file" (ACTION_CREATE_DOCUMENT)

    private void pickMedia(int code, String mime) {
        if (HostingStore.getDefault(this) == null) { toast("Set hosting first."); return; }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType(mime);
        startActivityForResult(Intent.createChooser(i, "Choose"), code);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final int r = req;
        if (r == SAVE_TEXT) {   // write the queued text to the user-chosen file
            final String content = pendingSaveText; pendingSaveText = null;
            if (content == null) return;
            io.execute(() -> {
                try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); os.flush();
                    runOnUiThread(() -> toast("Saved. Keep this file safe — it holds your credentials."));
                } catch (Exception e) { runOnUiThread(() -> toast("Save failed: " + e.getMessage())); }
            });
            return;
        }
        if (r == OPEN_BACKUP) {   // read + restore an account backup file
            io.execute(() -> {
                try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192]; int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    restoreFromBackup(new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception e) { runOnUiThread(() -> toast("Couldn't read file: " + e.getMessage())); }
            });
            return;
        }
        if (r == PICK_DM_IMG) {   // DM photo: self-host via Maxima when ready, else the encrypted relay
            toast("Sending photo…");
            io.execute(() -> {
                try {
                    byte[] jpeg = readScaledJpeg(uri, 1400);
                    // Prefer the Maxima mesh (you host it, no central relay). The encrypted-relay
                    // fallback is RETIRED (MaxLite sunset) — fall back to the user's configured
                    // default host instead, and if there's none, ask them to set one up.
                    Hosting.Profile host = MaximaLink.isReady(this)
                            ? Hosting.Profile.fresh(Hosting.TYPE_MAXIMA)
                            : HostingStore.getDefault(this);
                    if (host == null) { runOnUiThread(() -> toast("Set up hosting (or install Maxima) to send photos")); return; }
                    String ref = Hosting.forProfile(host).putFile(jpeg, "dm.jpg", "image/jpeg");
                    runOnUiThread(() -> sendDm("", ref, "image/jpeg"));
                } catch (Exception e) { runOnUiThread(() -> toast("Photo failed: " + e.getMessage())); }
            });
            return;
        }
        toast("Uploading…");   // profStatus is at the bottom of a long scroll — give visible feedback here too
        if (profStatus != null) profStatus.setText("Uploading…");
        io.execute(() -> {
            try {
                String handle = SalonStore.get(this, "handle");
                Hosting.Profile def = HostingStore.getDefault(this);
                byte[] bytes; String ext, mime, kind;
                if (r == PICK_GALLERY_VID) { bytes = readCapped(uri, 60); ext = ".mp4"; mime = "video/mp4"; kind = "video"; }
                else if (r == PICK_GALLERY_AUD) { bytes = readCapped(uri, 25); ext = ".mp3"; mime = "audio/mpeg"; kind = "audio"; }
                else { bytes = readScaledJpeg(uri, r == PICK_AVATAR ? 640 : 1400); ext = ".jpg"; mime = "image/jpeg"; kind = "image"; }
                String rel = handle + "/media/" + kind + "-" + Long.toString(System.currentTimeMillis(), 36) + ext;
                // Maxima mesh only: enforce the per-profile total BEFORE any IPC/publish, so a
                // full profile fails instantly with clear guidance instead of a 90 s round trip.
                if (Hosting.TYPE_MAXIMA.equals(def.type())) {
                    long usage = meshUsageBytes();
                    if (usage + bytes.length > MAX_PROFILE_MESH_BYTES) {
                        final String msg = "Your Maxima profile is at " + mibOf(usage) + " / "
                                + (MAX_PROFILE_MESH_BYTES >> 20) + " MB. Remove some media, or switch this "
                                + "profile to a server host (SFTP/IPFS/GitHub) for unlimited size.";
                        runOnUiThread(() -> { if (profStatus != null) profStatus.setText(msg); toast("Mesh profile full"); });
                        return;
                    }
                }
                final String url;
                try (Hosting.Uploader up = Hosting.forProfile(def)) { url = up.putFile(bytes, rel, mime); }
                runOnUiThread(() -> {
                    if (r == PICK_AVATAR && edAvatar != null) { edAvatar.setText(url); SalonStore.put(this, "avatar", url); render(); }
                    else if (r == PICK_BANNER && edBanner != null) { edBanner.setText(url); SalonStore.put(this, "banner", url); render(); }
                    else promptMediaTitle(kind, url);
                    if (profStatus != null) profStatus.setText("Uploaded.");
                });
            } catch (Exception e) { runOnUiThread(() -> { if (profStatus != null) profStatus.setText("Upload failed: " + e.getMessage()); }); }
        });
    }

    private void promptMediaTitle(String kind, String url) {
        LinearLayout box = dialogBox();
        String label = kind.equals("audio") ? "Track title" : kind.equals("video") ? "Video title" : "Photo caption";
        EditText t = field(box, label, "", false, kind.equals("audio") ? "Song name — artist" : "");
        new android.app.AlertDialog.Builder(this).setTitle("Add to gallery").setView(box).setCancelable(false)
                .setPositiveButton("Add", (d, w) -> addGalleryItem(kind, url, text(t)))
                .setNegativeButton("Skip title", (d, w) -> addGalleryItem(kind, url, "")).show();
    }

    private void addGalleryItem(String kind, String url, String caption) {
        JSONArray a = SalonStore.arr(this, "gallery");
        try { JSONObject o = new JSONObject(); o.put("type", kind); o.put("url", url); o.put("caption", caption == null ? "" : caption.trim()); a.put(o); } catch (Exception ignored) {}
        SalonStore.setArr(this, "gallery", a); render();
    }

    private void renameGalleryItem(int idx) {
        JSONArray a = SalonStore.arr(this, "gallery");
        JSONObject m = a.optJSONObject(idx); if (m == null) return;
        LinearLayout box = dialogBox();
        EditText t = field(box, "Title / caption", m.optString("caption", ""), false, "");
        showDialog("Rename", box, "Save", () -> { try { m.put("caption", text(t).trim()); } catch (Exception ignored) {} SalonStore.setArr(this, "gallery", a); render(); });
    }

    /** A full-width media card: a labelled header (KIND + title) over the media
     *  itself — image (tap to zoom), video (tap to play fullscreen), or an audio
     *  row with its own player modal. */
    private View mediaCard(JSONObject m) {
        String type = m.optString("type", "image"), url = m.optString("url"), cap = m.optString("caption", "");
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1));

        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.VERTICAL); head.setPadding(dp(11), dp(9), dp(11), dp(8));
        String kindLabel = type.equals("audio") ? "MUSIC" : type.equals("video") ? "VIDEO" : "PHOTO";
        TextView kt = Design.text(this, kindLabel, 8.5f, Design.ACCENT(), Design.sansBold()); kt.setLetterSpacing(0.14f);
        head.addView(kt);
        if (!cap.isEmpty()) head.addView(Design.text(this, cap, 15, Design.INK(), Design.sansBold()));
        col.addView(head);

        if ("image".equals(type)) {
            ImageView iv = new ImageView(this); iv.setAdjustViewBounds(true); iv.setMaxHeight(dp(360)); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFF141310);
            if (!url.isEmpty()) ImageLoader.loadOver(this, url, iv);   // list preview — tap opens full-res
            iv.setClickable(true); iv.setOnClickListener(v -> openImage(url));
            col.addView(iv, new LinearLayout.LayoutParams(-1, -2));
        } else if ("video".equals(type)) {
            FrameLayout f = new FrameLayout(this); f.setBackgroundColor(0xFF141310);
            TextView play = Design.text(this, "▶  TAP TO PLAY", 13, 0xFFF2F1EC, Design.sansBold()); play.setGravity(Gravity.CENTER);
            f.addView(play, new FrameLayout.LayoutParams(-1, -1));
            f.setClickable(true); f.setOnClickListener(v -> openVideo(url));
            col.addView(f, new LinearLayout.LayoutParams(-1, dp(190)));
        } else { // audio
            LinearLayout ar = row(); ar.setPadding(dp(11), dp(2), dp(11), dp(12));
            TextView note = Design.text(this, "♪", 26, Design.INK(), Design.sansBold());
            ar.addView(note, new LinearLayout.LayoutParams(dp(38), -2));
            ar.addView(btn("▶ Play", true, () -> playTrack(url, cap.isEmpty() ? "Untitled track" : cap)), new LinearLayout.LayoutParams(0, dp(46), 1));
            col.addView(ar);
        }
        return col;
    }

    private void openImage(String url) {
        if (url == null || url.isEmpty()) return;
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView iv = new ImageView(this); iv.setBackgroundColor(0xFF000000); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ImageLoader.loadFull(this, url, iv); iv.setOnClickListener(v -> d.dismiss());
        d.setContentView(iv); d.show();
    }

    private void openVideo(String url) {
        if (url == null || url.isEmpty()) return;
        if (RelayResolver.isMediaRef(url)) {                 // decrypt to a cache file first
            toast("Decrypting video…");
            io.execute(() -> { try { java.io.File f = RelayResolver.resolveToTempFile(this, url);
                runOnUiThread(() -> playVideoUri(Uri.fromFile(f))); }
                catch (Exception e) { runOnUiThread(() -> toast("Couldn't load video: " + e.getMessage())); } });
            return;
        }
        playVideoUri(Uri.parse(url));
    }

    private void playVideoUri(Uri uri) {
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        VideoView vv = new VideoView(this);
        MediaController mc = new MediaController(this); mc.setAnchorView(vv);
        vv.setMediaController(mc); vv.setVideoURI(uri);
        FrameLayout fl = new FrameLayout(this); fl.setBackgroundColor(0xFF000000);
        fl.addView(vv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        d.setContentView(fl); d.show();
        vv.setOnPreparedListener(mp -> { mp.setLooping(false); vv.start(); mc.show(0); });
        d.setOnDismissListener(x -> vv.stopPlayback());   // release the player so audio doesn't keep running after Back
    }

    /* ---------- gallery as a collection: photo grid, video carousel, playlist ---------- */

    /** Renders the whole gallery as Instagram-style collections — photos in a
     *  captioned grid (tap → swipeable carousel), videos in a horizontal
     *  carousel, songs as a playlist that plays in the persistent mini-player. */
    private void renderGallery(LinearLayout c, JSONArray gal) {
        List<JSONObject> photos = new ArrayList<>(), videos = new ArrayList<>(), tracks = new ArrayList<>();
        for (int i = 0; i < gal.length(); i++) {
            JSONObject m = gal.optJSONObject(i); if (m == null) continue;
            String t = m.optString("type", "image");
            if ("audio".equals(t)) tracks.add(m); else if ("video".equals(t)) videos.add(m); else photos.add(m);
        }
        if (!photos.isEmpty()) { c.addView(subLabel("Photos · " + photos.size()), lp(0, 10, 0, 6)); c.addView(photoGrid(photos)); }
        if (!videos.isEmpty()) { c.addView(subLabel("Video · " + videos.size()), lp(0, 16, 0, 6)); c.addView(videoCarousel(videos)); }
        if (!tracks.isEmpty()) { c.addView(subLabel("Music · " + tracks.size()), lp(0, 16, 0, 6)); c.addView(trackList(tracks)); }
    }

    private TextView subLabel(String s) { TextView t = Design.text(this, s.toUpperCase(), 9f, Design.DIM(), Design.sansBold()); t.setLetterSpacing(0.14f); return t; }

    private LinearLayout.LayoutParams cellLp(int col) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); if (col == 0) p.rightMargin = dp(4); else p.leftMargin = dp(4); return p; }

    private View photoGrid(final List<JSONObject> photos) {
        LinearLayout grid = new LinearLayout(this); grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r = null;
        for (int i = 0; i < photos.size(); i++) {
            final int idx = i; JSONObject m = photos.get(i);
            if (i % 2 == 0) { r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); grid.addView(r, lp(0, 0, 0, 8)); }
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL);
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackgroundColor(0xFF141310);
            if (!m.optString("url").isEmpty()) ImageLoader.loadOver(this, m.optString("url"), iv);
            iv.setClickable(true); iv.setOnClickListener(v -> openCarousel(photos, idx));
            cell.addView(iv, new LinearLayout.LayoutParams(-1, dp(150)));
            String cap = m.optString("caption", "");
            if (!cap.isEmpty()) { TextView ct = Design.text(this, cap, 11.5f, Design.DIM(), Design.sans()); ct.setPadding(dp(1), dp(4), 0, 0); ct.setMaxLines(2); cell.addView(ct); }
            r.addView(cell, cellLp(i % 2));
        }
        if (photos.size() % 2 == 1 && r != null) r.addView(new View(this), cellLp(1));
        return grid;
    }

    private View videoCarousel(final List<JSONObject> videos) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout rowv = new LinearLayout(this); rowv.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < videos.size(); i++) {
            final JSONObject m = videos.get(i);
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL);
            FrameLayout f = new FrameLayout(this); f.setBackground(Design.ruled(this, 0xFF141310, Design.INK(), 1));
            TextView play = Design.text(this, "▶", 30, 0xFFF2F1EC, Design.sansBold()); play.setGravity(Gravity.CENTER);
            f.addView(play, new FrameLayout.LayoutParams(-1, -1));
            f.setClickable(true); f.setOnClickListener(v -> openVideo(m.optString("url")));
            cell.addView(f, new LinearLayout.LayoutParams(dp(220), dp(140)));
            String cap = m.optString("caption", "");
            if (!cap.isEmpty()) { TextView ct = Design.text(this, cap, 11.5f, Design.DIM(), Design.sans()); ct.setPadding(dp(2), dp(4), 0, 0); ct.setMaxLines(2); cell.addView(ct); }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2); clp.rightMargin = dp(8); rowv.addView(cell, clp);
        }
        hs.addView(rowv); return hs;
    }

    private View trackList(final List<JSONObject> tracks) {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1));
        for (int i = 0; i < tracks.size(); i++) {
            JSONObject m = tracks.get(i); final String url = m.optString("url");
            final String title = m.optString("caption", "").isEmpty() ? "Untitled track" : m.optString("caption");
            LinearLayout r = row(); r.setPadding(dp(11), dp(11), dp(10), dp(11)); r.setClickable(true); Design.pressable(r);
            r.setOnClickListener(v -> playTrack(url, title));
            r.addView(Design.text(this, String.valueOf(i + 1), 12, Design.DIM(), Design.mono()), new LinearLayout.LayoutParams(dp(24), -2));
            TextView tt = Design.text(this, title, 14, Design.INK(), Design.sansBold()); tt.setMaxLines(1); tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(Design.text(this, "▶", 15, Design.ACCENT(), Design.sansBold()));
            col.addView(r);
            if (i < tracks.size() - 1) col.addView(Design.rule(this, 1), new LinearLayout.LayoutParams(-1, dp(1)));
        }
        return col;
    }

    /** Fullscreen swipeable carousel over a list of image items (‹ › + fling), caption underneath. */
    private void openCarousel(final List<JSONObject> items, int start) {
        if (items.isEmpty()) return;
        final int[] idx = { Math.max(0, Math.min(start, items.size() - 1)) };
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(0xFF000000);
        final ImageView img = new ImageView(this); img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(img, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(16), dp(26), dp(16), dp(10));
        final TextView counter = Design.text(this, "", 13, 0xFFFFFFFF, Design.mono()); top.addView(counter, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = Design.text(this, "✕", 20, 0xFFFFFFFF, Design.sansBold()); close.setPadding(dp(10), dp(4), dp(10), dp(4)); close.setOnClickListener(v -> d.dismiss()); top.addView(close);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        final TextView caption = Design.text(this, "", 14, 0xFFFFFFFF, Design.sans()); caption.setPadding(dp(16), dp(12), dp(16), dp(30)); caption.setBackgroundColor(0x99000000);
        root.addView(caption, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        final TextView prev = navArrow("‹"), next = navArrow("›");
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL); plp.leftMargin = dp(4); root.addView(prev, plp);
        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL); nlp.rightMargin = dp(4); root.addView(next, nlp);

        final Runnable[] bind = new Runnable[1];
        bind[0] = () -> {
            JSONObject m = items.get(idx[0]); counter.setText((idx[0] + 1) + " / " + items.size());
            String cap = m.optString("caption", ""); caption.setText(cap); caption.setVisibility(cap.isEmpty() ? View.GONE : View.VISIBLE);
            ImageLoader.loadFull(this, m.optString("url"), img);
            prev.setVisibility(idx[0] > 0 ? View.VISIBLE : View.INVISIBLE); next.setVisibility(idx[0] < items.size() - 1 ? View.VISIBLE : View.INVISIBLE);
        };
        prev.setOnClickListener(v -> { if (idx[0] > 0) { idx[0]--; bind[0].run(); } });
        next.setOnClickListener(v -> { if (idx[0] < items.size() - 1) { idx[0]++; bind[0].run(); } });

        final android.view.GestureDetector gd = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false; float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > dp(56) && Math.abs(dx) > Math.abs(e2.getY() - e1.getY())) {
                    if (dx < 0) { if (idx[0] < items.size() - 1) { idx[0]++; bind[0].run(); } } else { if (idx[0] > 0) { idx[0]--; bind[0].run(); } }
                    return true;
                }
                return false;
            }
        });
        img.setClickable(true); img.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
        bind[0].run();
        d.setContentView(root); d.show();
    }

    private TextView navArrow(String s) { TextView t = Design.text(this, s, 34, 0xFFFFFFFF, Design.sansBold()); t.setPadding(dp(14), dp(10), dp(14), dp(10)); t.setClickable(true); return t; }

    /* ---------- persistent audio: a mini-player docked above the nav ---------- */

    private void buildMiniPlayer() {
        miniPlayer = new LinearLayout(this); miniPlayer.setOrientation(LinearLayout.VERTICAL);
        miniPlayer.setBackgroundColor(Design.CARD()); miniPlayer.setVisibility(View.GONE);
        miniPlayer.addView(Design.rule(this, 2), new LinearLayout.LayoutParams(-1, dp(2)));

        miniBar = new SeekBar(this); miniBar.setMax(1000);
        miniBar.getProgressDrawable().setColorFilter(Design.ACCENT(), android.graphics.PorterDuff.Mode.SRC_IN);
        miniBar.getThumb().setColorFilter(Design.ACCENT(), android.graphics.PorterDuff.Mode.SRC_IN);
        miniBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser && audioDur() > 0) { try { audio.seekTo((int) (audioDur() * (p / 1000f))); } catch (Exception ignored) {} } }
            public void onStartTrackingTouch(SeekBar s) { miniSeeking = true; }
            public void onStopTrackingTouch(SeekBar s) { miniSeeking = false; }
        });
        miniPlayer.addView(miniBar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(dp(12), dp(2), dp(6), dp(6));
        r.addView(Design.text(this, "♪", 18, Design.ACCENT(), Design.sansBold()), new LinearLayout.LayoutParams(dp(22), -2));
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(7), 0, dp(6), 0);
        miniTitle = Design.text(this, "", 13, Design.INK(), Design.sansBold()); miniTitle.setMaxLines(1); miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        miniTime = Design.text(this, "0:00", 10, Design.DIM(), Design.mono());
        col.addView(miniTitle); col.addView(miniTime);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(miniIcon("⏪", () -> seekRel(-10000)));
        miniPlay = miniIcon("❚❚", this::togglePlay); r.addView(miniPlay);
        r.addView(miniIcon("⏩", () -> seekRel(10000)));
        r.addView(miniIcon("✕", this::stopAudio));
        miniPlayer.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView miniIcon(String s, Runnable click) {
        TextView t = Design.text(this, s, 16, Design.INK(), Design.sansBold()); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(6), dp(10), dp(6)); t.setClickable(true); Design.pressable(t);
        t.setOnClickListener(v -> click.run()); return t;
    }

    private int audioDur() { try { return audio == null ? 0 : audio.getDuration(); } catch (Exception e) { return 0; } }
    private void seekRel(int ms) { if (audio == null) return; try { int d = audioDur(); int np = audio.getCurrentPosition() + ms; if (np < 0) np = 0; if (d > 0 && np > d) np = d; audio.seekTo(np); } catch (Exception ignored) {} }
    private void togglePlay() { if (audio == null) return; try { if (audio.isPlaying()) audio.pause(); else audio.start(); updateMiniPlay(); } catch (Exception ignored) {} }
    private void updateMiniPlay() { if (miniPlay == null) return; boolean playing = false; try { playing = audio != null && audio.isPlaying(); } catch (Exception ignored) {} miniPlay.setText(playing ? "❚❚" : "▶"); }

    /** Start (or switch to) a track; it plays on regardless of screen changes,
     *  controlled from the docked mini-player until you hit ✕. */
    private void playTrack(String url, String title) {
        if (url == null || url.isEmpty()) return;
        if (RelayResolver.isMediaRef(url)) {                 // decrypt to a cache file first
            stopAudio(); miniTitle.setText(title); miniTime.setText("decrypting…"); miniBar.setProgress(0);
            miniPlayer.setVisibility(View.VISIBLE);
            io.execute(() -> { try { java.io.File f = RelayResolver.resolveToTempFile(this, url);
                runOnUiThread(() -> playTrackSource(f.getAbsolutePath(), title)); }
                catch (Exception e) { runOnUiThread(() -> { toast("Couldn't load track: " + e.getMessage()); stopAudio(); }); } });
            return;
        }
        playTrackSource(url, title);
    }

    private void playTrackSource(String source, String title) {
        stopAudio();
        final MediaPlayer mp = new MediaPlayer(); audio = mp;
        miniTitle.setText(title); miniTime.setText("0:00"); miniBar.setProgress(0);
        miniPlayer.setVisibility(View.VISIBLE); updateMiniPlay();
        try {
            mp.setDataSource(source);
            mp.setOnPreparedListener(x -> { mp.start(); updateMiniPlay(); startTicker(); });
            mp.setOnCompletionListener(x -> { miniBar.setProgress(1000); updateMiniPlay(); });
            mp.setOnErrorListener((m, w, e) -> { toast("Playback error"); stopAudio(); return true; });
            mp.prepareAsync();
        } catch (Exception e) { toast("Couldn't play: " + e.getMessage()); stopAudio(); }
    }

    private void startTicker() {
        playback.removeCallbacksAndMessages(null);
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            try { int d = audioDur(); if (audio != null && d > 0) { if (!miniSeeking) miniBar.setProgress((int) (1000L * audio.getCurrentPosition() / d)); miniTime.setText(fmtTime(audio.getCurrentPosition()) + " / " + fmtTime(d)); } } catch (Exception ignored) {}
            playback.postDelayed(tick[0], 500);
        };
        playback.post(tick[0]);
    }

    private String fmtTime(int ms) { int s = ms / 1000; return s / 60 + ":" + String.format("%02d", s % 60); }

    private void stopAudio() {
        playback.removeCallbacksAndMessages(null);
        if (audio != null) { try { audio.release(); } catch (Exception ignored) {} audio = null; }
        if (miniPlayer != null) miniPlayer.setVisibility(View.GONE);
    }

    /** A real avatar (round-cornered photo) or, when none is set, a clean
     *  letter monogram — no auto-generated identicon. */
    private View avatarView(String url, String label, int sizeDp) {
        if (url != null && !url.isEmpty()) {
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(Design.ruled(this, Design.CARD(), Design.INK(), 1));
            ImageLoader.loadOver(this, url, iv);   // thumbnail size — avatars are small, don't decode 1800px
            iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
            return iv;
        }
        FrameLayout f = new FrameLayout(this); f.setBackground(Design.ruled(this, Design.PAPER(), Design.INK(), 2));
        String ltr = (label == null || label.trim().isEmpty()) ? "·" : label.trim().substring(0, 1).toUpperCase();
        TextView t = Design.text(this, ltr, sizeDp * 0.44f, Design.ACCENT(), Design.sansBold()); t.setGravity(Gravity.CENTER);
        f.addView(t, new FrameLayout.LayoutParams(-1, -1));
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return f;
    }

    private void addPostMedia(LinearLayout parent, JSONObject post) {
        String type = post.optString("type", post.optString("mtype", "")), media = post.optString("media", "");
        if (media.isEmpty()) return;
        if (type.isEmpty()) type = "image";
        JSONObject m = new JSONObject(); try { m.put("type", type); m.put("url", media); m.put("caption", ""); } catch (Exception ignored) {}
        parent.addView(mediaCard(m), lp(0, 6, 0, 2));
    }

    /* ================= ONBOARD / claim (unchanged core) ================= */

    private void renderOnboard() {
        LinearLayout intro = card();
        intro.addView(Design.lot(this, "№ 1 · Open your Salon"));
        intro.addView(Design.note(this, "Your identity becomes a signed token you own forever. Your page is a file you host and edit any time. First set hosting, then claim your handle."), lp(0, 6, 0, 0));
        body.addView(intro, lp(0, 0, 0, 12));

        Hosting.Profile def = HostingStore.getDefault(this);
        LinearLayout hostCard = card(); hostCard.addView(Design.lot(this, "Hosting"));
        addKvPlain(hostCard, "Destination", def == null ? "none set — required" : def.name() + " · " + def.type());
        hostCard.addView(btn(def == null ? "Set up hosting" : "Manage hosting", def == null, () -> go(Screen.HOSTING)), lph(46, 0, 8, 0, 0));
        if (def == null) {
            // RETIRED (MaxLite sunset) — the one-tap encrypted-relay shortcut is gone. To restore:
            // hostCard.addView(Design.note(this, "No server? Publish to the encrypted relay — zero setup, your page is stored end-to-end encrypted."), lp(0, 8, 0, 4));
            // hostCard.addView(btn("Publish with no server (relay)", false, this::quickStartRelay), lph(44, 0, 4, 0, 0));
            hostCard.addView(Design.note(this, "No server? Publish to Blossom — free public file hosting on the nostr network, zero setup."), lp(0, 8, 0, 4));
            hostCard.addView(btn("Publish with no server (Blossom)", false, this::quickStartBlossom), lph(44, 0, 4, 0, 0));
        }
        body.addView(hostCard, lp(0, 0, 0, 12));

        LinearLayout form = card(); form.addView(Design.lot(this, "Claim your handle"));
        EditText handle = field(form, "Handle", SalonStore.get(this, "handle"), false, "e.g. eurobuddha");
        EditText name = field(form, "Display name", SalonStore.get(this, "name"), false, "Euro Buddha");
        EditText bio = field(form, "Bio", SalonStore.get(this, "bio"), false, "one line about you");
        final TextView status = Design.note(this, ""); form.addView(status, lp(0, 8, 0, 0));
        claimBtn = btn("Claim my Salon", true, () -> claimIdentity(text(handle), text(name), text(bio), status));
        claimBtn.setEnabled(!claiming); if (claiming) claimBtn.setAlpha(0.4f);
        form.addView(claimBtn, lph(52, 0, 10, 0, 0));
        body.addView(form, lp(0, 0, 0, 12));
    }

    private void claimIdentity(String handle, String name, String bio, TextView status) {
        if (claiming) { status.setText("Already claiming — please wait."); return; }
        handle = Hosting.slug(handle);
        if (handle.isEmpty() || name.trim().isEmpty()) { status.setText("Handle and display name are required."); return; }
        Hosting.Profile def = HostingStore.getDefault(this);
        if (def == null) { status.setText("Set a hosting destination first."); return; }
        if (!nodeUp) { status.setText("Waiting for Minima Core."); return; }
        if (pubkey.isEmpty()) { status.setText("Fetching your key… try again in a moment."); fetchPubkey(); return; }
        claiming = true; setClaimEnabled(false);
        final String h = handle, n = name.trim(), b = bio.trim();
        SalonStore.put(this, "handle", h); SalonStore.put(this, "name", n); SalonStore.put(this, "bio", b);
        status.setText("Publishing your page…");
        io.execute(() -> {
            try {
                JSONObject profile = buildProfileJson();
                final String profileUrl;
                try (Hosting.Uploader up = Hosting.forProfile(def)) {
                    profileUrl = up.putFile(profile.toString().getBytes("UTF-8"), h + "/profile.json", "application/json");
                    // Content-addressed Blossom: relative fetch can't work — skip the renderer.
                    if (!Hosting.TYPE_RELAY.equals(def.type()) && !Hosting.TYPE_BLOSSOM.equals(def.type()))
                        try { up.putFile(SALON_HTML.getBytes("UTF-8"), h + "/index.html", "text/html"); } catch (Exception ignore) {}
                }
                Hosting.verifyUrl(profileUrl, def);
                runOnUiThread(() -> { SalonStore.put(this, "profileUrl", profileUrl); status.setText("Page live. Checking identity…"); adoptOrMint(h, n, b, profileUrl, status); });
            } catch (SftpUploader.HostKeyUnverified hk) { runOnUiThread(() -> { promptTrustHostKey(def, hk.fingerprint, status); claimFailed(); }); }
            catch (Exception e) { runOnUiThread(() -> { status.setText("Hosting failed: " + e.getMessage()); claimFailed(); }); }
        });
    }

    private void adoptOrMint(String h, String n, String b, String url, TextView status) {
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { String tid = findSalonToken(j, h); if (!tid.isEmpty()) runOnUiThread(() -> finishClaim(tid)); else runOnUiThread(() -> { status.setText("Minting your identity token…"); mintIdentity(h, n, b, url, status); }); }
            @Override public void onError(String m) { runOnUiThread(() -> { status.setText("Minting your identity token…"); mintIdentity(h, n, b, url, status); }); }
        });
    }

    private void mintIdentity(String handle, String name, String bio, String profileUrl, TextView status) {
        // Defense-in-depth: keep command/JSON-structure breakers out of the free-text metadata
        // that gets interpolated into the tokencreate command (quotes are JSON-escaped upstream).
        if (!Args.isSafeMeta(name) || !Args.isSafeMeta(bio)) { status.setText("Please remove { } or line breaks from your name/bio."); claimFailed(); return; }
        JSONObject meta = new JSONObject();
        putJson(meta, "salon", "1"); putJson(meta, "handle", handle); putJson(meta, "name", name); putJson(meta, "url", profileUrl); putJson(meta, "bio", bio);
        node.cmd("tokencreate name:" + meta + " amount:1 decimals:0 signtoken:" + pubkey, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) { runOnUiThread(() -> { status.setText("Mint failed: " + json.optString("error", "rejected")); claimFailed(); }); return; }
                runOnUiThread(() -> { status.setText("Minting… confirming (leave open)."); pollForIdentity(handle, 0, status); });
            }
            @Override public void onError(String m) { runOnUiThread(() -> { status.setText("Mint failed: " + m); claimFailed(); }); }
        });
    }

    private void pollForIdentity(String handle, int tries, TextView status) {
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                String tid = findSalonToken(json, handle);
                if (!tid.isEmpty()) runOnUiThread(() -> finishClaim(tid));
                else if (tries < 30) body.postDelayed(() -> pollForIdentity(handle, tries + 1, status), 4000);
                else runOnUiThread(() -> { status.setText("Minted — confirming. Reopen shortly, it'll adopt."); claiming = false; });
            }
            @Override public void onError(String m) { if (tries < 30) body.postDelayed(() -> pollForIdentity(handle, tries + 1, status), 4000); else runOnUiThread(() -> claiming = false); }
        });
    }

    private void finishClaim(String tokenid) {
        SalonStore.put(this, "tokenid", tokenid); claiming = false;
        publishSalon(null);   // put myself on the square
        toast("Your Salon is open."); go(Screen.HOME);
    }
    private void claimFailed() { claiming = false; setClaimEnabled(true); }
    private void setClaimEnabled(boolean on) { if (claimBtn != null) { claimBtn.setEnabled(on); claimBtn.setAlpha(on ? 1f : 0.4f); } }

    private JSONObject findAnySalonToken(JSONObject j) { return findSalonEntry(j, null); }
    private String findSalonToken(JSONObject j, String handle) { JSONObject t = findSalonEntry(j, handle); return t == null ? "" : t.optString("tokenid"); }
    private JSONObject findSalonEntry(JSONObject balanceJson, String handle) {
        try {
            JSONArray arr = balanceJson.optJSONArray("response"); if (arr == null) return null;
            for (int i = 0; i < arr.length(); i++) { JSONObject t = arr.optJSONObject(i); if (t == null) continue; JSONObject meta = t.optJSONObject("token"); if (meta == null) continue;
                if ("1".equals(meta.optString("salon")) && (handle == null || handle.equals(meta.optString("handle")))) return t; }
        } catch (Exception ignored) {}
        return null;
    }
    private void adoptFromToken(JSONObject tokenRow) {
        try { JSONObject meta = tokenRow.optJSONObject("token");
            SalonStore.put(this, "tokenid", tokenRow.optString("tokenid")); SalonStore.put(this, "handle", meta.optString("handle"));
            if (SalonStore.get(this, "name").isEmpty()) SalonStore.put(this, "name", meta.optString("name"));
            if (SalonStore.get(this, "bio").isEmpty()) SalonStore.put(this, "bio", meta.optString("bio"));
            SalonStore.put(this, "profileUrl", meta.optString("url")); claiming = false;
            hydrateFromHosted(meta.optString("url"), false);   // reinstall: pull content back from the hosted page
            if (screen == Screen.HOME || screen == Screen.ONBOARD || screen == Screen.FEED) go(Screen.HOME);
        } catch (Exception ignored) {}
    }

    /** A reinstall wipes the local draft, but the hosted profile.json still holds
     *  everything — pull the CONTENT back so the page isn't blank. Never overwrites
     *  tokenid/handle (on-chain authoritative) or the local messaging key (msgpk). */
    private void hydrateFromHosted(final String url, final boolean loud) {
        if (url == null || url.trim().isEmpty()) { if (loud) toast("No hosted page URL known yet."); return; }
        if (loud) toast("Restoring your page…");
        io.execute(() -> {
            final JSONObject p = httpGetJson(url);
            if (p == null) { if (loud) runOnUiThread(() -> toast("Couldn't reach your hosted page.")); return; }
            JSONObject me = SalonStore.me(this);
            boolean got = false;
            try {
                for (String k : new String[]{"name","bio","about","avatar","banner","webvalidate","tipaddr"}) {
                    String v = p.optString(k, "");
                    if (!v.isEmpty()) { me.put(k, v); got = true; }
                }
                for (String k : new String[]{"links","gallery","posts","nfts"}) {
                    JSONArray a = p.optJSONArray(k);
                    if (a != null && a.length() > 0) { me.put(k, a); got = true; }
                }
            } catch (Exception ignored) {}
            SalonStore.save(this, me);
            final boolean restored = got;
            runOnUiThread(() -> {
                if (restored) toast("Page restored from your hosting.");
                else if (loud) toast("Nothing to restore — your hosted page is empty.");
                render();
            });
        });
    }

    private void publishSalon(TextView status) {
        if (!nodeUp || !SalonStore.hasIdentity(this)) { if (status != null) status.setText("Need the node to publish."); return; }
        SalonRegistry.announce(node, SalonStore.get(this, "tokenid"), SalonStore.get(this, "profileUrl"), SalonStore.get(this, "handle"),
                (ok, msg) -> runOnUiThread(() -> { if (status != null) status.setText(msg); else toast(msg); }));
    }

    /** Burn any salon identity token that ISN'T your active one (e.g. a spare from
     *  an old double-mint) — sent to the unspendable graveyard. Never touches the
     *  token in SalonStore, so your live identity is always safe. */
    private void burnSpares() {
        if (!nodeUp) { toast("Need the node connected."); return; }
        final String keep = SalonStore.get(this, "tokenid");
        if (keep.isEmpty()) { toast("No active identity — nothing to compare against."); return; }   // never bury everything
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                final List<String> spares = new ArrayList<>();
                JSONArray arr = j.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject t = arr.optJSONObject(i); if (t == null) continue;
                    JSONObject meta = t.optJSONObject("token"); if (meta == null) continue;
                    if ("1".equals(meta.optString("salon"))) { String tid = t.optString("tokenid"); if (!tid.equalsIgnoreCase(keep) && !spares.contains(tid)) spares.add(tid); }
                }
                runOnUiThread(() -> {
                    if (spares.isEmpty()) { toast("No spare identity tokens — nothing to burn."); return; }
                    StringBuilder sb = new StringBuilder();
                    for (String s : spares) sb.append("• ").append(s).append("\n");
                    new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("Burn " + spares.size() + " spare token(s)?")
                            .setMessage("Your active identity " + Util.shorten(keep) + " is KEPT. These other salon tokens go to the unspendable graveyard — irreversible:\n\n" + sb)
                            .setPositiveButton("Burn", (d, w) -> { for (String tid : spares) burnOne(tid); })
                            .setNegativeButton("Cancel", null).show();
                });
            }
            @Override public void onError(String m) { runOnUiThread(() -> toast("Balance failed: " + m)); }
        });
    }

    private void burnOne(final String tid) {
        node.cmd("send amount:1 tokenid:" + tid + " address:" + GRAVEYARD, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { runOnUiThread(() -> toast(j.optBoolean("status", false) ? "Burn sent, mining: " + Util.shorten(tid) : "Burn failed: " + j.optString("error", ""))); }
            @Override public void onError(String m) { runOnUiThread(() -> toast("Burn error: " + m)); }
        });
    }

    /* ================= SETTINGS + HOSTING (unchanged) ================= */

    private void renderSettings() {
        masthead("Settings");
        LinearLayout nodeCard = card(); nodeCard.addView(Design.lot(this, "Minima Core"));
        addKvPlain(nodeCard, "Node", nodeUp ? "connected" : "not connected — enable The Salon in Minima → Apps");
        body.addView(nodeCard, lp(0, 0, 0, 12));
        Hosting.Profile def = HostingStore.getDefault(this);
        LinearLayout hostCard = card(); hostCard.addView(Design.lot(this, "Hosting"));
        hostCard.addView(Design.note(this, "Your page + all media upload to your OWN storage — SFTP straight to your server, or WebDAV / IPFS / GitHub."), lp(0, 4, 0, 6));
        addKvPlain(hostCard, "Default", def == null ? "none yet" : def.name() + " · " + def.type());
        hostCard.addView(btn("Manage destinations", true, () -> go(Screen.HOSTING)), lph(48, 0, 8, 0, 0));
        body.addView(hostCard, lp(0, 0, 0, 12));

        LinearLayout bkp = card(); bkp.addView(Design.lot(this, "Backup & restore"));
        bkp.addView(Design.note(this, "Save your messaging key + hosting logins to one file. After a reinstall, Restore brings them back — then your page content restores from hosting. Keep the file safe: it holds your credentials in the clear."), lp(0, 4, 0, 6));
        bkp.addView(btn("Back up to file", true, () -> saveTextFile("salon-backup.json", buildBackupJson())), lph(48, 0, 6, 0, 0));
        bkp.addView(btn("Restore from file", false, this::restoreFromFile), lph(44, 0, 8, 0, 0));
        body.addView(bkp, lp(0, 0, 0, 12));
        if (SalonStore.hasIdentity(this)) {
            LinearLayout idc = card(); idc.addView(Design.lot(this, "Identity"));
            copyRow(idc, "Handle", "@" + SalonStore.get(this, "handle"));
            copyRow(idc, "Token", SalonStore.get(this, "tokenid"));
            String tipaddr = SalonStore.get(this, "tipaddr");
            if (!tipaddr.isEmpty()) { copyRow(idc, "Tip address", tipaddr);
                idc.addView(btn("Show tip-address QR", false, () -> showQr("Tip @" + SalonStore.get(this, "handle"), tipaddr)), lph(44, 0, 4, 0, 0)); }
            idc.addView(btn("Re-publish to the Salon", false, () -> publishSalon(null)), lph(44, 0, 8, 0, 0));
            idc.addView(btn("Restore page from hosting", false, () -> hydrateFromHosted(SalonStore.get(this, "profileUrl"), true)), lph(44, 0, 8, 0, 0));
            idc.addView(btn("Back up messaging key", false, this::backupMsgKey), lph(44, 0, 8, 0, 0));
            idc.addView(btn("Restore messaging key", false, this::restoreMsgKey), lph(44, 0, 8, 0, 0));
            idc.addView(btn("Burn spare identity token", false, this::burnSpares), lph(44, 0, 8, 0, 0));
            body.addView(idc, lp(0, 0, 0, 12));
        }
        LinearLayout about = card(); about.addView(Design.lot(this, "Colophon"));
        addKvPlain(about, "App", "The Salon"); addKvPlain(about, "Version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        body.addView(about, lp(0, 0, 0, 12));
    }

    private void renderHosting() {
        masthead("Hosting");
        List<Hosting.Profile> profiles = HostingStore.list(this);
        if (profiles.isEmpty()) { LinearLayout e = card(); e.addView(Design.note(this, "No destinations yet. Add one — SFTP uploads straight to your own server.")); body.addView(e, lp(0, 0, 0, 12)); }
        for (Hosting.Profile p : profiles) {
            LinearLayout c = card(); LinearLayout head = row();
            head.addView(Design.text(this, p.name().isEmpty() ? "(unnamed)" : p.name(), 15, Design.INK(), Design.sansBold()), new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(Design.pill(this, p.type(), Design.PILL_DIM)); if (p.isDefault()) head.addView(Design.pill(this, "default", Design.PILL_DONE));
            c.addView(head, lp(0, 0, 0, 8));
            LinearLayout r = row();
            r.addView(btn("Test", false, () -> testProfile(p)), weight(31, 0, 4));
            r.addView(btn("Edit", false, () -> { hostEdit = p; go(Screen.HOSTING_EDIT); }), weight(31, 4, 4));
            r.addView(btn("Delete", false, () -> { HostingStore.remove(this, p.id()); render(); }), weight(31, 4, 0));
            c.addView(r, lp(0, 0, 0, 6));
            if (!p.isDefault()) c.addView(btn("Make default", false, () -> { HostingStore.setDefault(this, p.id()); render(); }), lph(42, 0, 0, 0, 0));
            body.addView(c, lp(0, 0, 0, 12));
        }
        body.addView(btn("Add destination", true, () -> { hostEdit = null; go(Screen.HOSTING_EDIT); }), lph(52, 0, 4, 0, 8));
    }

    // RETIRED (MaxLite sunset) — encrypted relay dropped from the picker. To restore, re-add
    // Hosting.TYPE_RELAY to HTYPES and "Encrypted relay" to HLABELS at the same index.
    private static final String[] HTYPES = { Hosting.TYPE_BLOSSOM, Hosting.TYPE_SFTP, Hosting.TYPE_WEBDAV, Hosting.TYPE_KUBO, Hosting.TYPE_PINATA, Hosting.TYPE_GITHUB, Hosting.TYPE_MAXIMA };
    private static final String[] HLABELS = { "Blossom", "SFTP", "WebDAV", "IPFS node", "Pinata", "GitHub", "Maxima mesh" };

    private void renderHostingEdit() {
        masthead(hostEdit == null ? "Add destination" : "Edit destination");
        if (hostEdit == null) hostEdit = Hosting.Profile.fresh(Hosting.TYPE_BLOSSOM);
        final Hosting.Profile p = hostEdit;
        LinearLayout card = card();
        EditText nameF = field(card, "Name", p.name(), false, "my server");
        nameF.addTextChangedListener(watch(s -> Hosting.put(p.j, "name", s)));
        card.addView(Design.kicker(this, "Type"), lp(0, 8, 0, 4));
        LinearLayout chips = row();
        for (int i = 0; i < HTYPES.length; i++) { final String t = HTYPES[i]; TextView chip = Design.chip(this, HLABELS[i], t.equals(p.type())); chip.setPadding(dp(11), dp(6), dp(11), dp(6));
            chip.setOnClickListener(v -> { if (!t.equals(p.type())) { Hosting.put(p.j, "type", t); if (p.j.optJSONObject(t) == null) Hosting.put(p.j, t, new JSONObject()); render(); } });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2); clp.rightMargin = dp(6); chips.addView(chip, clp); }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false); hs.addView(chips);
        card.addView(hs, lp(0, 0, 0, 6));
        JSONObject cfg = p.cfg();
        switch (p.type()) {
            case Hosting.TYPE_SFTP:
                cfgField(card, cfg, "host", "Host", "31.125.188.214", false); cfgField(card, cfg, "port", "Port", "22", false); cfgField(card, cfg, "user", "User", "root", false);
                boolean keyAuth = "key".equals(cfg.optString("auth", "password"));
                card.addView(Design.kicker(this, "Authentication"), lp(0, 8, 0, 4));
                LinearLayout ar = row(); TextView pw = Design.chip(this, "Password", !keyAuth), ky = Design.chip(this, "Private key", keyAuth);
                pw.setPadding(dp(11), dp(6), dp(11), dp(6)); ky.setPadding(dp(11), dp(6), dp(11), dp(6));
                pw.setOnClickListener(v -> { Hosting.put(cfg, "auth", "password"); render(); }); ky.setOnClickListener(v -> { Hosting.put(cfg, "auth", "key"); render(); });
                LinearLayout.LayoutParams m8 = new LinearLayout.LayoutParams(-2, -2); m8.rightMargin = dp(8); ar.addView(pw, m8); ar.addView(ky); card.addView(ar, lp(0, 0, 0, 6));
                if (keyAuth) cfgSecretMulti(card, cfg, "privateKey", "Private key (paste PEM)"); else cfgSecret(card, cfg, "password", "Password");
                cfgField(card, cfg, "remoteRoot", "Remote root (server dir)", "/var/www/html/salon", false);
                cfgField(card, cfg, "urlPrefix", "Public URL prefix", "http://31.125.188.214/salon/", false); break;
            case Hosting.TYPE_WEBDAV:
                cfgField(card, cfg, "endpoint", "Write endpoint", "https://dav.example.com/files/", false); cfgField(card, cfg, "user", "User", "", false); cfgSecret(card, cfg, "password", "Password"); cfgField(card, cfg, "urlPrefix", "Public URL prefix", "https://example.com/files/", false); break;
            case Hosting.TYPE_KUBO:
                cfgField(card, cfg, "apiUrl", "kubo RPC API", "https://api-ipfs.eurobuddha.com", false);
                cfgField(card, cfg, "user", "API user (HTTP Basic — optional)", "ipfs", false);
                cfgSecret(card, cfg, "password", "API password (HTTP Basic — optional)");
                cfgField(card, cfg, "gateway", "Public gateway", "https://ipfs.eurobuddha.com", false);
                card.addView(Design.note(this, "If your kubo RPC sits behind HTTP Basic auth (reverse proxy), put the user + password here — the app sends a proper Authorization header. Embedding user:pass@ in the API URL does NOT work on Android (the header is dropped → 401)."), lp(0, 6, 0, 2));
                break;
            case Hosting.TYPE_PINATA:
                cfgSecret(card, cfg, "jwt", "Pinata JWT"); cfgField(card, cfg, "gateway", "Gateway", "https://gateway.pinata.cloud", false); break;
            case Hosting.TYPE_GITHUB:
                card.addView(Design.note(this, "Set-up (once):\n"
                        + "1. Make a PUBLIC repo on github.com (e.g. 'salon'). Owner = your username, Repo = its name.\n"
                        + "2. Settings → Developer settings → Personal access tokens → Fine-grained → Generate. Give it Contents: Read and write on that repo. Paste it as Token (PAT).\n"
                        + "3. Serve via 'raw' works instantly (raw.githubusercontent.com). For 'pages': in the repo Settings → Pages, enable Pages from your branch, then set Pages prefix to https://<owner>.github.io/<repo>/.\n"
                        + "Branch 'main' is fine. Then Save & test."), lp(0, 6, 0, 4));
                cfgField(card, cfg, "owner", "Owner (GitHub username)", "", false); cfgField(card, cfg, "repo", "Repo name", "salon", false); cfgField(card, cfg, "branch", "Branch", "main", false); cfgSecret(card, cfg, "token", "Token (PAT — Contents: read & write)"); cfgField(card, cfg, "serve", "Serve via (raw / pages)", "raw", false); cfgField(card, cfg, "pagesPrefix", "Pages prefix (only if serve=pages)", "https://you.github.io/salon/", false); break;
            // RETIRED (MaxLite sunset) — encrypted-relay config UI removed from the picker. To
            // restore, re-add TYPE_RELAY to HTYPES/HLABELS and uncomment this case:
            // case Hosting.TYPE_RELAY:
            //     cfgField(card, cfg, "relayUrl", "Relay URL", RelayUploader.DEFAULT_RELAY, false);
            //     card.addView(Design.note(this, "Publish with NO server of your own: your phone encrypts your page + media (libsodium) and uploads only the CIPHERTEXT to this relay's blob store — the relay can never read it. The decryption key travels in your public pointer, so any Salon viewer can see your page.\n\nTrade-offs: viewable in the Salon app only (a web browser can't decrypt it — SFTP/IPFS keep the browser view); and content expires ~7 days after it's last opened/viewed, so open the app now and then to keep it alive."), lp(0, 6, 0, 2));
            //     break;
            case Hosting.TYPE_BLOSSOM:
                card.addView(Design.note(this, "Free public file hosting on the nostr network — no account, no password, no server of your own. Files are signed with a key derived from your Salon messaging identity and stored by content hash, so your page stays viewable in any web browser and every republish gets a fresh URL (Salon re-announces it automatically).\n\nThe default server (blossom.primal.net) accepts your page AND media for free. Media-only servers like blossom.band can't host the page itself. Free servers cap file size (typically tens of MB) — large video/audio belongs on SFTP/IPFS/GitHub."), lp(0, 6, 0, 2));
                // Pre-fill the actual value, not just the hint — the field must show
                // where the page will land without the user typing anything.
                if (cfg.optString("endpoint", "").isEmpty()) Hosting.put(cfg, "endpoint", BlossomUploader.DEFAULT_SERVER);
                cfgField(card, cfg, "endpoint", "Blossom server", BlossomUploader.DEFAULT_SERVER, false);
                card.addView(Design.kicker(this, "Your nostr identity"), lp(0, 8, 0, 4));
                copyRow(card, "public key (hex)", NostrKeys.pubkeyHex());
                copyRow(card, "npub", NostrKeys.npub());
                break;
            case Hosting.TYPE_MAXIMA:
                card.addView(Design.note(this, "You are the server. Media is encrypted on your phone, kept HERE, and mirrored across the Maxima relay mesh so it stays reachable while your phone sleeps — no single relay to trust, pay or lose. Needs the Maxima app installed (approve Salon once in Maxima → Connected apps).\n\nViewable in Maxima-capable apps only. This is the decentralised path: 100% of the network is hosted by its users.\n\nSize limit: up to 16 MB per file — great for photos and short clips. Large video/audio won't fit the mesh (it's user-hosted redundancy, not a storage locker); host those on a server type (SFTP/IPFS/GitHub), which has no size limit and stays online without you."
                        + "\n\nProfile budget: " + mibOf(meshUsageBytes()) + " / " + (MAX_PROFILE_MESH_BYTES >> 20) + " MB used (total across all your mesh media). Big libraries belong on a server host."
                        + (MaximaLink.isReady(this) ? "\n\n✓ Maxima connected." : "\n\n⚠ Maxima not connected yet — install/approve it, then reopen this.")), lp(0, 6, 0, 2));
                break;
        }
        final TextView status = Design.note(this, ""); card.addView(status, lp(0, 8, 0, 0));
        LinearLayout btns = row();
        btns.addView(btn("Save", true, () -> { saveHost(p); go(Screen.HOSTING); }), weight(48, 0, 4));
        btns.addView(btn("Save & test", false, () -> { saveHost(p); testProfile(p, status); }), weight(48, 4, 0));
        card.addView(btns, lp(0, 8, 0, 0));
        body.addView(card, lp(0, 0, 0, 12));
    }

    private void saveHost(Hosting.Profile p) {
        if (p.name().isEmpty()) Hosting.put(p.j, "name", p.type());
        Hosting.put(p.j, "pathTemplate", "salon/{collection}/{name}-{ts}{ext}"); Hosting.put(p.j, "dirTemplate", "salon/{collection}-{ts}");
        HostingStore.upsert(this, p);
        if (HostingStore.getDefault(this) == null || HostingStore.list(this).size() == 1) HostingStore.setDefault(this, p.id());
    }

    private void testProfile(Hosting.Profile p) { testProfile(p, null); }
    private void testProfile(Hosting.Profile p, TextView status) {
        if (status != null) status.setText("Testing…"); else toast("Testing " + p.name() + "…");
        io.execute(() -> {
            String msg;
            try { String rel = "_test/probe-" + Long.toString(System.currentTimeMillis(), 36) + ".txt";
                String url; try (Hosting.Uploader up = Hosting.forProfile(p)) { url = up.putFile(("salon-test").getBytes("UTF-8"), rel, "text/plain"); }
                Hosting.verifyUrl(url, p); msg = "OK — uploaded and served: " + url;
            } catch (SftpUploader.HostKeyUnverified hk) { runOnUiThread(() -> promptTrustHostKey(p, hk.fingerprint, status)); return;
            } catch (Exception e) { msg = "Failed: " + e.getMessage(); }
            final String m = msg; runOnUiThread(() -> { if (status != null) status.setText(m); else toast(m); });
        });
    }

    private void promptTrustHostKey(Hosting.Profile p, String fp, TextView status) {
        new android.app.AlertDialog.Builder(this).setTitle("Trust this server?")
                .setMessage("First connection to " + p.cfgStr("host") + ".\n\nSSH host-key fingerprint:\n" + fp + "\n\nTrust it only if this is your server. It'll be pinned.")
                .setPositiveButton("Trust & pin", (d, w) -> { Hosting.put(p.cfg(), "hostKeyFp", fp); HostingStore.upsert(this, p); testProfile(p, status); })
                .setNegativeButton("Cancel", (d, w) -> { if (status != null) status.setText("Not trusted."); }).show();
    }

    /* ================= node key ================= */

    private void fetchPubkey() {
        node.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) { String pk = firstPublicKey(json); if (!pk.isEmpty()) runOnUiThread(() -> pubkey = pk); else node.cmd("getaddress", new NodeApi.Cb() {
                @Override public void onResult(JSONObject j2) { JSONObject r = j2.optJSONObject("response"); if (r != null && !r.optString("publickey").isEmpty()) runOnUiThread(() -> pubkey = r.optString("publickey")); }
                @Override public void onError(String m) {} }); }
            @Override public void onError(String m) {} });
    }
    private String firstPublicKey(JSONObject json) {
        Object resp = json.opt("response");
        try { if (resp instanceof JSONArray) { JSONArray a = (JSONArray) resp; if (a.length() > 0 && a.optJSONObject(0) != null) return a.optJSONObject(0).optString("publickey", ""); }
            else if (resp instanceof JSONObject) { JSONArray keys = ((JSONObject) resp).optJSONArray("keys"); if (keys != null && keys.length() > 0) return keys.optJSONObject(0).optString("publickey", ""); return ((JSONObject) resp).optString("publickey", ""); }
        } catch (Exception ignored) {}
        return "";
    }

    /* ================= HTTP ================= */

    // Short-TTL memo of fetched profile JSON, keyed by url/ref. Feed + Discover re-pull
    // every followed/listed profile (each up to 1 MB) on EVERY render/swipe/reconnect;
    // this collapses that to at most one fetch per url per TTL. Pull-to-refresh clears it.
    private final java.util.Map<String, Object[]> mProfileCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PROFILE_CACHE_TTL_MS = 90_000L;

    // Memo of the last town-square scan (a depth-1500 chain read). Discover and
    // openTokenProfile both trigger it; without this, tapping a feed author or
    // re-rendering Discover re-scans the whole square each time. Cleared on pull-to-refresh.
    private java.util.List<SalonRegistry.Entry> mRegistryCache;
    private long mRegistryCacheTs;
    private static final long REGISTRY_TTL_MS = 60_000L;

    private void registryList(SalonRegistry.Listed cb) {
        if (mRegistryCache != null && System.currentTimeMillis() - mRegistryCacheTs < REGISTRY_TTL_MS) { cb.done(mRegistryCache); return; }
        SalonRegistry.list(node, entries -> { mRegistryCache = entries; mRegistryCacheTs = System.currentTimeMillis(); cb.done(entries); });
    }

    private JSONObject httpGetJson(String url) { return httpGetJson(url, false); }

    /** @param force bypass the memo cache and re-fetch (pull-to-refresh). */
    private JSONObject httpGetJson(String url, boolean force) {
        if (url == null) return null;
        if (!force) {
            Object[] e = mProfileCache.get(url);
            if (e != null && System.currentTimeMillis() - (Long) e[1] < PROFILE_CACHE_TTL_MS) return (JSONObject) e[0];
        }
        JSONObject j = httpGetJsonRaw(url);
        if (j != null) mProfileCache.put(url, new Object[]{ j, System.currentTimeMillis() });
        return j;
    }

    private JSONObject httpGetJsonRaw(String url) {
        // A profile pointer can be relay1: (encrypted relay) OR mx1: (Maxima mesh)
        // OR a plain http(s) URL. isMediaRef covers BOTH ref schemes; without the
        // mx1: case a mesh-hosted profile fell through to the http branch, failed
        // the startsWith("http") test, returned null and showed "host offline".
        if (RelayResolver.isMediaRef(url)) {
            try { return RelayResolver.resolveJson(url); } catch (Exception e) { return null; }
        }
        HttpURLConnection c = null;
        try {
            if (url == null || !url.startsWith("http")) return null;
            // SSRF-safe: validates the host AND re-checks every redirect hop, so an
            // attacker-published profile URL can't 302 us to the local Minima node.
            c = ImageLoader.openChecked(url, 3);
            if (c == null || c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n, tot = 0; while ((n = in.read(buf)) > 0 && tot < 1_000_000) { bos.write(buf, 0, n); tot += n; } in.close();
            return new JSONObject(bos.toString("UTF-8"));
        } catch (Exception e) { return null; } finally { if (c != null) c.disconnect(); }
    }

    private byte[] readCapped(Uri uri, int maxMB) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("cannot read file");
        ByteArrayOutputStream bos = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n; long tot = 0, cap = (long) maxMB * 1024 * 1024;
        while ((n = in.read(buf)) > 0) { tot += n; if (tot > cap) { in.close(); throw new Exception("file too large (max " + maxMB + " MB)"); } bos.write(buf, 0, n); }
        in.close(); return bos.toByteArray();
    }

    private byte[] readScaledJpeg(Uri uri, int maxDim) throws Exception {
        // ImageTools.boundedBitmap decodes via ImageDecoder, which applies EXIF
        // orientation exactly once — the Atelier fix for sideways Samsung photos.
        Bitmap bmp = ImageTools.boundedBitmap(this, uri, maxDim);
        if (bmp == null) throw new Exception("could not read image");
        ByteArrayOutputStream bos = new ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos); return bos.toByteArray();
    }

    /* ================= UI helpers ================= */

    private int dp(int v) { return Design.dp(this, v); }
    private LinearLayout card() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setBackground(Design.blockShadow(this, Design.CARD())); c.setPadding(dp(14), dp(12), dp(14), dp(12)); return c; }
    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }

    private void addKvPlain(LinearLayout parent, String k, String v) {
        LinearLayout r = row(); r.setPadding(0, dp(4), 0, dp(4));
        TextView key = Design.text(this, k.toUpperCase(), 9.5f, Design.DIM(), Design.sansBold()); key.setLetterSpacing(0.12f);
        r.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = Design.text(this, v == null ? "—" : v, 11.5f, Design.INK(), Design.mono()); val.setGravity(Gravity.END);
        r.addView(val, new LinearLayout.LayoutParams(0, -2, 1.6f)); parent.addView(r);
    }

    /** kv row: shortened value, TAP copies the FULL value. */
    private void copyRow(LinearLayout parent, String k, String full) {
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(0, dp(6), 0, dp(6));
        LinearLayout head = row();
        TextView key = Design.text(this, k.toUpperCase(), 9.5f, Design.DIM(), Design.sansBold()); key.setLetterSpacing(0.12f);
        head.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(Design.text(this, "TAP TO COPY", 8.5f, Design.ACCENT(), Design.sansBold()));
        col.addView(head);
        // Show the FULL value (no truncation) in a tappable box — wraps rather than clipping.
        TextView val = Design.text(this, full, 11f, Design.INK(), Design.mono());
        val.setBackground(Design.dashed(this, Design.CARD(), Design.DIM())); val.setPadding(dp(8), dp(6), dp(8), dp(6));
        copyOnTap(val, full);
        col.addView(val, lp(0, 3, 0, 0));
        parent.addView(col);
    }

    private void copyOnTap(TextView t, String full) {
        t.setClickable(true);
        t.setOnClickListener(v -> { ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText("salon", full)); toast("Copied"); });
    }

    /** label + tappable vermilion underlined link (opens browser), long-press copies. */
    private LinearLayout linkRow(String label, String url) {
        LinearLayout r = row(); r.setPadding(0, dp(5), 0, dp(5));
        TextView key = Design.text(this, label.toUpperCase(), 9.5f, Design.DIM(), Design.sansBold()); key.setLetterSpacing(0.1f);
        r.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = Design.text(this, url, 11f, Design.ACCENT(), Design.mono()); val.setGravity(Gravity.END);
        val.setPaintFlags(val.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG); val.setMaxLines(2);
        val.setClickable(true); val.setOnClickListener(v -> openUrl(url));
        val.setOnLongClickListener(v -> { ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText("salon", url)); toast("Copied"); return true; });
        r.addView(val, new LinearLayout.LayoutParams(0, -2, 1.8f)); return r;
    }

    private TextView verifiedBadge(String host) {
        int gold = 0xFF9A7B1F;
        String label = host == null || host.isEmpty() ? "✓ WEB-VERIFIED" : "✓ VERIFIED · " + host.toUpperCase();
        TextView t = Design.text(this, label, 9.5f, gold, Design.sansBold());
        t.setLetterSpacing(0.1f); t.setPadding(dp(8), dp(5), dp(8), dp(6)); t.setBackground(Design.ruled(this, Design.CARD(), gold, 2)); return t;
    }

    private String urlHost(String url) { try { return new java.net.URL(url.trim()).getHost(); } catch (Exception e) { return ""; } }

    /** Atelier/NFTwallet parity: wrap the avatar so a web-validation plaque
     *  (Identicon.checkBadge) sits bottom-right when the token's webvalidate doc
     *  proves the tokenid — the over-image mark that flags the real, verified
     *  account (handles aren't unique, so this is what distinguishes fakes). */
    private FrameLayout badgedAvatar(String avatarUrl, String label, final String tokenid, String webvalidate, int sizeDp) {
        FrameLayout f = new FrameLayout(this);
        View av = avatarView(avatarUrl, label, sizeDp);
        f.addView(av, new FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        final int bs = dp(Math.max(15, sizeDp / 3));
        final Runnable paint = () -> {
            if (f.getChildCount() > 1) f.removeViewAt(1);
            if (Boolean.TRUE.equals(WebValidate.status(tokenid))) {
                ImageView badge = new ImageView(this);
                FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(bs, bs, Gravity.BOTTOM | Gravity.END);
                blp.setMargins(0, 0, dp(2), dp(2));
                badge.setLayoutParams(blp); badge.setImageBitmap(Identicon.checkBadge(bs));
                f.addView(badge);
            }
        };
        paint.run();
        if (tokenid != null && !tokenid.isEmpty() && webvalidate != null && !webvalidate.isEmpty())
            WebValidate.ensure(this, tokenid, webvalidate, paint);
        return f;
    }

    private EditText field(LinearLayout parent, String label, String value, boolean secret, String hint) {
        parent.addView(Design.text(this, label.toUpperCase(), 9.5f, Design.DIM(), Design.sansBold()), lp(0, 8, 0, 3));
        EditText e = new EditText(this); e.setText(value == null ? "" : value); e.setHint(hint); e.setTextColor(Design.INK()); e.setTextSize(14);
        e.setBackground(Design.ruled(this, Design.PAPER(), Design.INK(), 1)); e.setPadding(dp(10), dp(9), dp(10), dp(9));
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2)); return e;
    }
    private EditText fieldMulti(LinearLayout parent, String label, String value) {
        parent.addView(Design.text(this, label.toUpperCase(), 9.5f, Design.DIM(), Design.sansBold()), lp(0, 8, 0, 3));
        EditText e = new EditText(this); e.setText(value == null ? "" : value); e.setTextColor(Design.INK()); e.setTextSize(14);
        e.setBackground(Design.ruled(this, Design.PAPER(), Design.INK(), 1)); e.setPadding(dp(10), dp(9), dp(10), dp(9));
        e.setMinLines(3); e.setGravity(Gravity.TOP); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2)); return e;
    }
    private void cfgField(LinearLayout parent, JSONObject cfg, String key, String label, String hint, boolean secret) { EditText e = field(parent, label, cfg.optString(key, ""), secret, hint); e.addTextChangedListener(watch(s -> Hosting.put(cfg, key, s))); }
    private void cfgSecret(LinearLayout parent, JSONObject cfg, String key, String label) { EditText e = field(parent, label, Crypt.decrypt(cfg.optString(key, "")), true, "••••••"); e.addTextChangedListener(watch(s -> Hosting.put(cfg, key, Crypt.encrypt(s)))); }
    private void cfgSecretMulti(LinearLayout parent, JSONObject cfg, String key, String label) {
        parent.addView(Design.text(this, label.toUpperCase(), 9.5f, Design.DIM(), Design.sansBold()), lp(0, 8, 0, 3));
        EditText e = new EditText(this); e.setText(Crypt.decrypt(cfg.optString(key, ""))); e.setTextColor(Design.INK()); e.setTextSize(11); e.setTypeface(Design.mono());
        e.setBackground(Design.ruled(this, Design.PAPER(), Design.INK(), 1)); e.setPadding(dp(10), dp(9), dp(10), dp(9)); e.setMinLines(4); e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); e.addTextChangedListener(watch(s -> Hosting.put(cfg, key, Crypt.encrypt(s))));
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView btn(String label, boolean filled, Runnable click) { TextView b = Design.button(this, label, filled); b.setOnClickListener(v -> click.run()); return b; }

    private LinearLayout dialogBox() { LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(18), dp(6), dp(18), dp(6)); return b; }
    private void showDialog(String title, LinearLayout content, String ok, Runnable onOk) {
        new android.app.AlertDialog.Builder(this).setTitle(title).setView(content)
                .setPositiveButton(ok, (d, w) -> onOk.run()).setNegativeButton("Cancel", null).show();
    }

    private JSONArray removeAt(JSONArray a, int idx) { JSONArray out = new JSONArray(); for (int i = 0; i < a.length(); i++) if (i != idx) out.put(a.opt(i)); return out; }

    private void openUrl(String url) {
        try {
            String u = url.trim();
            if (u.matches("(?i)^https?://.*")) {                       // http/https: open
            } else if (u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {     // any other scheme (file://, market://, app deep-links): refuse
                toast("Only web (http/https) links open here."); return;
            } else {
                u = "http://" + u;                                    // bare domain → http
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) { toast("Couldn't open link"); }
    }

    /** One-tap: create + default a Blossom hosting profile (no server needed). */
    private void quickStartBlossom() {
        Hosting.Profile p = Hosting.Profile.fresh(Hosting.TYPE_BLOSSOM);
        Hosting.put(p.j, "name", "Blossom");
        Hosting.put(p.cfg(), "endpoint", BlossomUploader.DEFAULT_SERVER);
        saveHost(p);
        HostingStore.setDefault(this, p.id());
        toast("Blossom hosting ready — now claim your handle.");
        render();
    }

    // RETIRED (MaxLite sunset) — one-tap encrypted-relay onboarding. Uncomment to restore:
    // /** One-tap: create + default an encrypted-relay hosting profile (no server needed). */
    // private void quickStartRelay() {
    //     Hosting.Profile p = Hosting.Profile.fresh(Hosting.TYPE_RELAY);
    //     Hosting.put(p.j, "name", "Encrypted relay");
    //     saveHost(p);
    //     HostingStore.setDefault(this, p.id());
    //     toast("Relay hosting ready — now claim your handle.");
    //     render();
    // }

    private void sharePage(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, "@" + SalonStore.get(this, "handle") + " on The Salon — " + url);
            startActivity(Intent.createChooser(i, "Share your Salon"));
        } catch (Exception e) { toast("Couldn't share"); }
    }

    private void showQr(String title, String data) {
        Bitmap bmp = Qr.bitmap(data, 640);
        if (bmp == null) { toast("Couldn't render QR"); return; }
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(0xFFFFFFFF); col.setPadding(dp(24), dp(24), dp(24), dp(24));
        ImageView iv = new ImageView(this); iv.setImageBitmap(bmp);
        col.addView(iv, new LinearLayout.LayoutParams(dp(280), dp(280)));
        TextView cap = Design.text(this, title, 13, 0xFF111111, Design.mono()); cap.setGravity(Gravity.CENTER); cap.setPadding(0, dp(16), 0, 0);
        col.addView(cap);
        col.setOnClickListener(v -> d.dismiss());
        d.setContentView(col); d.show();
    }
    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
    private String text(EditText e) { return e.getText().toString(); }
    private static void putJson(JSONObject o, String k, String v) { try { o.put(k, v == null ? "" : v); } catch (Exception ignored) {} }
    private TextWatcher watch(final java.util.function.Consumer<String> on) { return new TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int b, int c) {} public void onTextChanged(CharSequence s, int a, int b, int c) {} public void afterTextChanged(android.text.Editable s) { on.accept(s.toString()); } }; }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private LinearLayout.LayoutParams lph(int h, int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(h)); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private LinearLayout.LayoutParams weight(int hDp, int lm, int rm) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(hDp), 1); p.setMargins(dp(lm), 0, dp(rm), 0); return p; }
    private LinearLayout.LayoutParams weight1() { return new LinearLayout.LayoutParams(0, -2, 1); }

    /* Public web renderer uploaded beside profile.json — fetches ./profile.json and draws a real page. */
    private static final String SALON_HTML =
        "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
        + "<title>The Salon</title><style>body{margin:0;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;background:#F2F1EC;color:#141310}"
        + ".w{max-width:680px;margin:0 auto;padding:0 16px 64px}.ban{width:100%;height:180px;object-fit:cover;display:block}"
        + ".av{width:88px;height:88px;border-radius:8px;object-fit:cover;border:2px solid #141310;margin-top:-30px;background:#ddd}"
        + "h1{font-size:26px;margin:10px 0 2px}.h{color:#E63312;font-family:monospace}.bio{font-size:16px}"
        + ".k{font:700 11px/1 monospace;letter-spacing:.15em;text-transform:uppercase;color:#6B6A64;margin:26px 0 8px;border-bottom:2px solid #141310;padding-bottom:6px}"
        + "a.l{display:block;padding:11px 13px;border:1.5px solid #141310;background:#FCFBF7;margin:8px 0;color:#141310;text-decoration:none;box-shadow:3px 3px 0 #141310}"
        + ".g{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.g>*{aspect-ratio:1;width:100%;object-fit:cover;border:1.5px solid #141310;background:#000}"
        + ".post{border:1.5px solid #141310;background:#FCFBF7;padding:12px 14px;margin:10px 0;box-shadow:3px 3px 0 #141310}"
        + "video,audio{width:100%}img.pm{width:100%;border:1.5px solid #141310;margin-top:8px}</style></head><body><div class=w>"
        + "<div id=b></div></div><script>"
        // Escape every profile.json field before it enters innerHTML: text is HTML-encoded (x),
        // URLs are scheme-checked + encoded (u), so a crafted profile can't inject markup/script
        // or a javascript:/data: URI into the hosted page a visitor trusts.
        + "var x=function(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/'/g,'&#39;').replace(/\"/g,'&quot;')};"
        + "var u=function(s){s=String(s==null?'':s);return /^\\s*(javascript|data|vbscript):/i.test(s)?'':x(s)};"
        + "fetch('./profile.json').then(r=>r.json()).then(p=>{"
        + "var b=document.getElementById('b');var h='';if(p.banner)h+=\"<img class=ban src='\"+u(p.banner)+\"'>\";"
        + "h+=\"<img class=av src='\"+u(p.avatar||'')+\"'>\";h+='<h1>'+x(p.name||'')+'</h1>';h+=\"<div class=h>@\"+x(p.handle||'')+'</div>';if(p.bio)h+='<div class=bio>'+x(p.bio)+'</div>';"
        + "if(p.about){h+='<div class=k>About</div><div>'+x(p.about)+'</div>'}"
        + "if(p.links&&p.links.length){h+='<div class=k>Links</div>';p.links.forEach(function(l){h+=\"<a class=l href='\"+u(l.url)+\"'>\"+x(l.label||l.url)+'</a>'})}"
        + "if(p.gallery&&p.gallery.length){h+='<div class=k>Gallery</div><div class=g>';p.gallery.forEach(function(m){if(m.type=='video')h+=\"<video src='\"+u(m.url)+\"' controls></video>\";else if(m.type=='audio')h+=\"<div style='display:flex;align-items:center;justify-content:center'>&#9835;</div>\";else h+=\"<img src='\"+u(m.url)+\"'>\"});h+='</div>';p.gallery.forEach(function(m){if(m.type=='audio')h+=\"<audio src='\"+u(m.url)+\"' controls></audio>\"})}"
        + "if(p.posts&&p.posts.length){h+='<div class=k>Posts</div>';p.posts.slice().reverse().forEach(function(t){h+='<div class=post>'+x(t.text||'');if(t.media){if(t.type=='video')h+=\"<video src='\"+u(t.media)+\"' controls></video>\";else if(t.type=='audio')h+=\"<audio src='\"+u(t.media)+\"' controls></audio>\";else h+=\"<img class=pm src='\"+u(t.media)+\"'>\"}h+='</div>'})}"
        + "b.innerHTML=h}).catch(function(){document.getElementById('b').innerHTML='<p>Profile not found.</p>'})</script></body></html>";
}
