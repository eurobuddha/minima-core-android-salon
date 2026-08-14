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

    private LinearLayout appbar, navRow, body;
    private ScrollView scroll;
    private FrameLayout rootFrame;
    private TextView nodeChip;
    private boolean nodeUp = false;
    private boolean wasNodeUp = false;      // to re-render node-gated screens on connect
    private String expandedNft = null;      // tokenid of the showcase holding currently expanded
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
        chrome.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        buildMiniPlayer();
        chrome.addView(miniPlayer, new LinearLayout.LayoutParams(-1, -2));

        navRow = new LinearLayout(this); navRow.setOrientation(LinearLayout.VERTICAL);
        navRow.setBackgroundColor(Design.PAPER());
        chrome.addView(navRow, new LinearLayout.LayoutParams(-1, -2));

        rootFrame.addView(chrome, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootFrame);

        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(getWindow(), rootFrame);
        wic.setAppearanceLightStatusBars(true); wic.setAppearanceLightNavigationBars(true);
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            appbar.setPadding(0, sys.top, 0, 0);
            navRow.setPadding(0, 0, 0, ime.bottom > 0 ? 0 : sys.bottom);
            // When the keyboard is up, pad the scroll area by its height so the
            // focused field always scrolls clear of the keyboard (never overlaid).
            int kb = Math.max(0, ime.bottom - sys.bottom);
            scroll.setPadding(0, 0, 0, kb);
            return insets;
        });

        node = new NodeApi(this, enabled -> runOnUiThread(() -> { nodeUp = enabled; onNode(); }));
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
        if (SalonStore.hasIdentity(this)) screen = Screen.HOME;
        render();
    }

    private void onNode() {
        if (nodeChip != null) nodeChip.setText(nodeUp ? "connected" : "no node");
        if (nodeUp && pubkey.isEmpty()) fetchPubkey();
        if (nodeUp && !adoptChecked && !claiming && !SalonStore.hasIdentity(this)) {
            adoptChecked = true;
            node.cmd("balance", new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) { JSONObject t = findAnySalonToken(j); if (t != null) runOnUiThread(() -> adoptFromToken(t)); }
                @Override public void onError(String m) {}
            });
        }
        // Gossip mesh: once per session, re-post any faded pointer (own + a few
        // others) so the square stays alive collectively. Gated on the on-chain
        // fade check inside keepAlive, so it no-ops unless something has faded.
        if (nodeUp && !reannouncedThisSession && SalonStore.hasIdentity(this)) {
            reannouncedThisSession = true;
            SalonRegistry.keepAlive(node, SalonStore.get(this, "tokenid"), SalonStore.get(this, "profileUrl"),
                    SalonStore.get(this, "handle"), SalonStore.follows(this), n -> {});
            touchOwnRelay();   // refresh the 7-day TTL on any relay-hosted content
            ensureMailKey();
            ensureInboxAndScan();
        }
        // The first render() runs at onCreate before the node connects; node-gated
        // content (the holdings showcase, the tip/message bar) was drawn in its
        // "no node" state. Re-render once, on the false->true transition, so it refreshes.
        if (nodeUp && !wasNodeUp && (screen == Screen.HOME || screen == Screen.VIEW)) render();
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
        MinimaMail.scan(node, SalonComms.crypto(this), addr, msgs -> runOnUiThread(() -> {
            MailDb db = MailDb.get(MainActivity.this);
            int newCount = 0; String lastFrom = "", lastBody = "";
            for (MinimaMail.Msg m : msgs) {
                if (m.coinid.isEmpty()) continue;
                if (!m.valid) continue;   // anonymous seal — drop messages whose sender signature doesn't verify (anti-impersonation)
                String preview = !m.body.isEmpty() ? m.body : (!m.mediaRef.isEmpty() ? "📎 media" : "");
                boolean isNew = db.insert(m.coinid, m.fromPublicId, false, m.body, m.mediaRef, m.mediaMime, m.ts, m.valid);
                if (isNew) {
                    db.upsertContact(m.fromPublicId, m.fromHandle, "", m.fromAddr, preview, m.ts, !m.fromPublicId.equals(threadPeer));
                    newCount++; lastFrom = m.fromHandle; lastBody = preview;
                }
            }
            if (newCount > 0) {
                if (screen == Screen.MESSAGES || screen == Screen.THREAD) render();
                else { buildNav(); toast("💬 " + (newCount == 1 ? "@" + lastFrom.replaceFirst("^@", "") + ": " + lastBody : newCount + " new messages")); }
            }
        }));
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
        final EditText note = field(box, "Note (optional)", "", false, "nice work!");
        curBtn.setOnClickListener(v -> {
            tokenid[0] = tokenid[0].equals(TipTransport.MXUSD) ? TipTransport.MINIMA : TipTransport.MXUSD;
            curBtn.setText("Currency: " + TipTransport.label(tokenid[0]));
            presets.setVisibility(tokenid[0].equals(TipTransport.MXUSD) ? View.VISIBLE : View.GONE);
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
        toast("Checking balance…");
        node.cmd("balance tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                String sendable = "0";
                try { JSONArray a = j.optJSONArray("response"); if (a != null && a.length() > 0) sendable = a.optJSONObject(0).optString("sendable", "0"); } catch (Exception ignored) {}
                java.math.BigDecimal haveBd; try { haveBd = new java.math.BigDecimal(sendable); } catch (Exception e) { haveBd = java.math.BigDecimal.ZERO; }
                final java.math.BigDecimal have = haveBd;
                runOnUiThread(() -> {
                    if (amt.compareTo(have) > 0) { toast("Not enough " + TipTransport.label(tokenid) + " (you have " + have.toPlainString() + ")"); return; }
                    toast("Sending tip…");
                    TipTransport.tip(node, toAddr, amount, tokenid, "@" + SalonStore.get(MainActivity.this, "handle"), note, new TipTransport.Cb() {
                        @Override public void onSent(String txpowid) { runOnUiThread(() -> toast("Tip sent to @" + handle + " — mining ⛏")); }
                        @Override public void onFailed(String msg) { runOnUiThread(() -> toast("Tip failed: " + msg)); }
                    });
                });
            }
            @Override public void onError(String m) { runOnUiThread(() -> toast("Balance check failed: " + m)); }
        });
    }

    /* ================= Messages (DMs over Minima Mail) ================= */

    private void openThread(String peerpk, String handle, String avatar, String addr) {
        if (peerpk == null || peerpk.isEmpty()) { toast("This account can't receive messages yet."); return; }
        MailDb db = MailDb.get(this);
        MailDb.Contact ex = db.contact(peerpk);
        db.upsertContact(peerpk, handle, avatar, addr, ex != null ? ex.lastbody : "", ex != null ? ex.lastts : 0, false);
        threadPeer = peerpk;
        db.clearUnread(peerpk);
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
        db.clearUnread(threadPeer);
        LinearLayout bar = row();
        bar.addView(btn("← Messages", false, () -> go(Screen.MESSAGES)), weight(46, 0, 0));
        body.addView(bar, lp(0, 0, 0, 8));
        List<MailDb.Message> msgs = db.messages(threadPeer);
        if (msgs.isEmpty()) body.addView(Design.note(this, "No messages yet — say hi. Everything here is end-to-end encrypted and sent over the chain."), lp(0, 0, 0, 10));
        for (MailDb.Message m : msgs) body.addView(bubble(m));
        LinearLayout comp = card();
        final EditText input = fieldMulti(comp, "Message", "");
        LinearLayout crow = row();
        crow.addView(btn("📎", false, this::attachDmMedia), new LinearLayout.LayoutParams(dp(54), dp(46)));
        crow.addView(btn("Send", true, () -> sendDm(text(input), "", "")), weight(46, 6, 0));
        comp.addView(crow, lp(0, 6, 0, 0));
        body.addView(comp, lp(0, 8, 0, 0));
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private View bubble(MailDb.Message m) {
        LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Design.ruled(this, m.mine ? Design.ACCENT() : Design.CARD(), Design.INK(), 1));
        b.setPadding(dp(12), dp(9), dp(12), dp(9));
        if (m.body != null && !m.body.isEmpty())
            b.addView(Design.text(this, m.body, 14.5f, m.mine ? Design.PAPER() : Design.INK(), Design.sans()));
        if (m.media != null && !m.media.isEmpty()) {
            JSONObject mm = new JSONObject();
            try { mm.put("type", m.mime != null && m.mime.contains("video") ? "video" : m.mime != null && m.mime.contains("audio") ? "audio" : "image"); mm.put("url", m.media); mm.put("caption", ""); } catch (Exception ignored) {}
            b.addView(mediaCard(mm), lp(0, m.body != null && !m.body.isEmpty() ? 6 : 0, 0, 0));
        }
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
        if (ct == null || ct.addr == null || ct.addr.isEmpty()) { toast("No delivery address for this contact yet."); return; }
        long ts = System.currentTimeMillis() / 1000;
        String myHandle = "@" + SalonStore.get(this, "handle");
        String myAddr = SalonStore.get(this, "tipaddr");
        JSONObject msg = MinimaMail.compose(myHandle, myAddr, body, mediaRef, mediaMime, ts);
        db.insert("me-" + System.currentTimeMillis() + "-" + Math.abs(body.hashCode()), threadPeer, true, body, mediaRef, mediaMime, ts, true);
        db.upsertContact(threadPeer, ct.handle, ct.avatar, ct.addr, body.isEmpty() ? "📎 media" : body, ts, false);
        render();
        MinimaMail.send(node, SalonComms.crypto(this), ct.addr, threadPeer, msg, new MinimaMail.Cb() {
            @Override public void onSent(String txpowid) { runOnUiThread(() -> toast("Sent ⛏ (mining)")); }
            @Override public void onFailed(String m) { runOnUiThread(() -> toast("Send failed: " + m)); }
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

    @Override protected void onDestroy() { super.onDestroy(); io.shutdownNow(); stopAudio(); }

    /* ---------------- chrome + nav ---------------- */

    private void buildNav() {
        navRow.removeAllViews();
        navRow.addView(Design.rule(this, 2), new LinearLayout.LayoutParams(-1, dp(2)));
        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(navTab("Feed", screen == Screen.FEED, () -> go(Screen.FEED)), weight1());
        tabs.addView(navTab("Discover", screen == Screen.DISCOVER || screen == Screen.VIEW, () -> go(Screen.DISCOVER)), weight1());
        tabs.addView(navTab("Salon", screen == Screen.HOME || screen == Screen.ONBOARD || screen == Screen.EDIT, () -> go(Screen.HOME)), weight1());
        int unread = 0; try { unread = MailDb.get(this).totalUnread(); } catch (Exception ignored) {}
        tabs.addView(navTabBadge("Messages", screen == Screen.MESSAGES || screen == Screen.THREAD, unread, () -> go(Screen.MESSAGES)), weight1());
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

    private void go(Screen s) { screen = s; render(); }   // audio keeps playing across screens

    private void render() {
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

    /* ================= profile model ================= */

    /** The public profile.json = identity + rich content, from the local draft. */
    private JSONObject buildProfileJson() {
        JSONObject me = SalonStore.me(this), p = new JSONObject();
        putJson(p, "v", "1");
        for (String k : new String[]{"handle","name","bio","about","avatar","banner","tokenid","webvalidate","tipaddr","msgpk"})
            putJson(p, k, me.optString(k, ""));
        putJson(p, "updated", Long.toString(System.currentTimeMillis() / 1000));
        try {
            p.put("links", me.optJSONArray("links") == null ? new JSONArray() : me.optJSONArray("links"));
            p.put("gallery", me.optJSONArray("gallery") == null ? new JSONArray() : me.optJSONArray("gallery"));
            p.put("posts", me.optJSONArray("posts") == null ? new JSONArray() : me.optJSONArray("posts"));
            p.put("nfts", me.optJSONArray("nfts") == null ? new JSONArray() : me.optJSONArray("nfts"));
        } catch (Exception ignored) {}
        return p;
    }

    /** Re-host profile.json (+ the public web renderer) to <handle>/ and announce. */
    private void hostProfile(TextView status, Runnable done) {
        Hosting.Profile def = HostingStore.getDefault(this);
        if (def == null) { if (status != null) status.setText("Set hosting first."); return; }
        String handle = SalonStore.get(this, "handle");
        if (status != null) status.setText("Publishing your page…");
        JSONObject profile = buildProfileJson();
        io.execute(() -> {
            try {
                Hosting.Uploader up = Hosting.forProfile(def);
                String url = up.putFile(profile.toString().getBytes("UTF-8"), handle + "/profile.json", "application/json");
                // The encrypted relay isn't a web host — skip the browser renderer there.
                if (!Hosting.TYPE_RELAY.equals(def.type()))
                    try { up.putFile(SALON_HTML.getBytes("UTF-8"), handle + "/index.html", "text/html"); } catch (Exception ignore) {}
                Hosting.verifyUrl(url, def);
                runOnUiThread(() -> {
                    SalonStore.put(this, "profileUrl", url);
                    if (status != null) status.setText("Live.");
                    if (done != null) done.run();
                });
            } catch (Exception e) { runOnUiThread(() -> { if (status != null) status.setText("Publish failed: " + e.getMessage()); }); }
        });
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
        final int[] pending = { follows.length() };
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
                }
                runOnUiThread(() -> { if (--pending[0] == 0) paintFeed(posts, status); });
            });
        }
    }

    private void paintFeed(List<JSONObject> posts, TextView status) {
        Collections.sort(posts, (a, b) -> Long.compare(b.optLong("ts", 0), a.optLong("ts", 0)));
        body.removeView(status);
        if (posts.isEmpty()) { body.addView(Design.note(this, "No posts yet from anyone you follow."), lp(0, 0, 0, 12)); return; }
        for (JSONObject post : posts) {
            LinearLayout c = card();
            LinearLayout head = row();
            head.addView(avatarView(post.optString("_avatar"), post.optString("_author"), 34));
            TextView who = Design.text(this, "@" + post.optString("_author"), 13, Design.ACCENT(), Design.sansBold());
            who.setPadding(dp(9), 0, 0, 0);
            who.setOnClickListener(v -> openTokenProfile(post.optString("_tid")));
            head.addView(who, new LinearLayout.LayoutParams(0, -2, 1));
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
        TextView status = Design.note(this, "Reading the square…");
        body.addView(status, lp(0, 0, 0, 8));
        if (!nodeUp) { status.setText("Waiting for Minima Core."); return; }
        SalonRegistry.list(node, entries -> runOnUiThread(() -> {
            body.removeView(status);
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

    private void openProfile(SalonRegistry.Entry e) {
        viewEntry = e; viewProfile = null;
        go(Screen.VIEW);
        io.execute(() -> { JSONObject p = httpGetJson(e.url); runOnUiThread(() -> { viewProfile = p; if (screen == Screen.VIEW) render(); }); });
    }

    private void openTokenProfile(String tokenid) {
        SalonRegistry.list(node, entries -> {
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
        if (viewProfile == null) { body.addView(Design.note(this, "Loading @" + viewEntry.handle + "'s page…"), lp(0, 0, 0, 12)); return; }
        String tipAddr = viewProfile.optString("tipaddr", "");
        String peerMsgpk = viewProfile.optString("msgpk", "");
        boolean canMsg = !tipAddr.isEmpty() && !peerMsgpk.isEmpty();
        if (!tipAddr.isEmpty()) {
            LinearLayout arow = row();
            if (canMsg) arow.addView(btn("💬 Message", true, () -> openThread(peerMsgpk, viewEntry.handle, viewProfile.optString("avatar", ""), tipAddr)), weight(46, 0, 4));
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
            body.addView(actions, lp(0, 0, 0, 12));
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
            renderGallery(c, gal);
            body.addView(c, lp(0, 0, 0, 12));
        }

        JSONArray posts = p.optJSONArray("posts");
        if (posts != null && posts.length() > 0) {
            LinearLayout c = card(); c.addView(Design.lot(this, "Posts"));
            for (int i = posts.length() - 1; i >= 0; i--) {
                JSONObject post = posts.optJSONObject(i); if (post == null) continue;
                LinearLayout pc = new LinearLayout(this); pc.setOrientation(LinearLayout.VERTICAL);
                pc.setPadding(0, dp(8), 0, dp(8));
                if (i < posts.length() - 1) pc.addView(Design.rule(this, 1), new LinearLayout.LayoutParams(-1, dp(1)));
                if (!post.optString("text").isEmpty()) pc.addView(Design.body(this, post.optString("text")), lp(0, 6, 0, 4));
                addPostMedia(pc, post);
                c.addView(pc);
            }
            body.addView(c, lp(0, 0, 0, 12));
        }

        JSONArray nfts = p.optJSONArray("nfts");
        // Bind proofs to the ON-CHAIN discovered tokenid when viewing others, not the
        // profile's self-declared one — else a profile could paste someone else's valid proofs.
        String bindTid = (!mine && viewEntry != null) ? viewEntry.tokenid : p.optString("tokenid");
        if (nfts != null && nfts.length() > 0) renderNftShowcase(nfts, bindTid);

        if (mine) {
            LinearLayout meta = card(); meta.addView(Design.lot(this, "Your page"));
            copyRow(meta, "Identity token", p.optString("tokenid"));
            meta.addView(linkRow("Profile URL", SalonStore.get(this, "profileUrl")));
            body.addView(meta, lp(0, 0, 0, 12));
        }
    }

    /** Render showcased holdings; the VIEWER's node verifies each live. Tap a row to
     *  expand its details + a manual re-verify. */
    private void renderNftShowcase(JSONArray nfts, String profileTokenid) {
        LinearLayout c = card(); c.addView(Design.lot(this, "Holdings"));
        final String bindHex = NftProof.hexOf(profileTokenid);
        for (int i = 0; i < nfts.length(); i++) {
            final JSONObject n = nfts.optJSONObject(i); if (n == null) continue;
            final String tid = n.optString("tokenid");
            final boolean open = tid != null && tid.equals(expandedNft);
            LinearLayout r = row(); r.setPadding(0, dp(8), 0, dp(8)); r.setClickable(true);
            ImageView iv = new ImageView(this); iv.setScaleType(ImageView.ScaleType.CENTER_CROP); iv.setBackgroundColor(0xFF141310);
            if (!n.optString("image").isEmpty()) ImageLoader.loadFull(this, n.optString("image"), iv);
            r.addView(iv, new LinearLayout.LayoutParams(dp(46), dp(46)));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(12), 0, dp(8), 0);
            col.addView(Design.text(this, n.optString("name", Util.shorten(tid)), 15, Design.INK(), Design.sansBold()));
            final TextView badge = Design.text(this, "verifying on your node…", 11f, Design.DIM(), Design.mono());
            col.addView(badge);
            r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(Design.text(this, open ? "▾" : "▸", 15, Design.DIM(), Design.mono()));
            r.setOnClickListener(v -> { expandedNft = open ? null : tid; render(); });
            c.addView(r);
            verifyInto(n, bindHex, badge);

            if (open) {   // expanded: full main icon (tap→fullscreen), ids, verify, tappable editions gallery
                LinearLayout d = new LinearLayout(this); d.setOrientation(LinearLayout.VERTICAL);
                d.setPadding(dp(4), dp(2), dp(4), dp(10));
                final String iconUrl = n.optString("image");
                if (!iconUrl.isEmpty()) {
                    ImageView hero = new ImageView(this); hero.setScaleType(ImageView.ScaleType.FIT_CENTER); hero.setBackgroundColor(0xFF141310);
                    ImageLoader.loadFull(this, iconUrl, hero); hero.setClickable(true); hero.setOnClickListener(v -> openImage(iconUrl));
                    d.addView(hero, new LinearLayout.LayoutParams(-1, dp(300)));
                    d.addView(Design.text(this, "tap the artwork to view it full-screen", 10.5f, Design.DIM(), Design.mono()), lp(0, 4, 0, 2));
                }
                copyRow(d, "Token id", tid);
                if (!n.optString("coinid").isEmpty()) copyRow(d, "Coin id", n.optString("coinid"));
                final TextView vline = Design.text(this, "verifying on your node…", 12f, Design.DIM(), Design.mono());
                d.addView(vline, lp(0, 8, 0, 6));
                d.addView(btn("Verify again", false, () -> verifyInto(n, bindHex, vline)), lph(44, 0, 4, 0, 0));
                c.addView(d);
                verifyInto(n, bindHex, vline);
                renderEditionArt(d, tid);   // the state-variable editions, as a tappable gallery
            }
        }
        body.addView(c, lp(0, 0, 0, 12));
    }

    /** Verify a showcase holding on THIS device's node; write the outcome (with the
     *  failing-stage reason) into {@code tv}. Green on success, vermilion on failure. */
    private void verifyInto(final JSONObject n, final String bindHex, final TextView tv) {
        if (!nodeUp) { tv.setText("connect a node to verify"); tv.setTextColor(Design.DIM()); return; }
        tv.setText("verifying on your node…"); tv.setTextColor(Design.DIM());
        NftProof.verify(node, n, bindHex, (ok, reason, stateImg) -> runOnUiThread(() -> {
            tv.setText(ok ? "✓ VERIFIED HOLDING" : "✕ " + reason);
            tv.setTextColor(ok ? 0xFF1F7A3F : 0xFFB4462A);
        }));
    }

    /** The StateNFT's per-edition state-variable images THIS node holds, as a 2-up gallery —
     *  each tile taps into a fullscreen, swipeable carousel over every edition. Handles BOTH
     *  modes: embedded state art, and url-mode ({@code base + index + ext}) collections like
     *  "gallery bibeau". base/ext from the token metadata (balance); index from each coin's
     *  state port 0. Empty (silent) for a viewer who holds no editions. */
    private void renderEditionArt(final LinearLayout parent, final String tokenid) {
        if (!nodeUp || tokenid == null || tokenid.isEmpty()) return;
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
                        JSONArray arr = r.optJSONArray("response"); if (arr == null) return;
                        final java.util.List<JSONObject> editions = new java.util.ArrayList<>();
                        final java.util.HashSet<String> seen = new java.util.HashSet<>();
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject coin = arr.optJSONObject(i); if (coin == null) continue;
                            String u = NftProof.editionImageUrl(coin, fbase, fext);
                            if (u.isEmpty() || !seen.add(u)) continue;
                            String idx = NftProof.state(coin, 0);
                            try { JSONObject it = new JSONObject(); it.put("url", u);
                                it.put("caption", idx != null && idx.matches("[0-9]+") ? "Edition #" + idx : "Edition"); editions.add(it); }
                            catch (Exception ignored) {}
                        }
                        if (editions.isEmpty()) return;
                        runOnUiThread(() -> {
                            parent.addView(Design.text(MainActivity.this, editions.size() + " STATE IMAGE" + (editions.size() == 1 ? "" : "S") + " YOU HOLD · TAP TO ENLARGE", 9f, Design.DIM(), Design.sansBold()), lp(0, 12, 0, 6));
                            LinearLayout g = null;
                            for (int i = 0; i < editions.size(); i++) {
                                final int idx = i;
                                if (i % 2 == 0) { g = row(); parent.addView(g, lp(0, 0, 0, 6)); }
                                ImageView t = new ImageView(MainActivity.this); t.setScaleType(ImageView.ScaleType.CENTER_CROP); t.setBackgroundColor(0xFF141310);
                                t.setClickable(true); t.setOnClickListener(v -> openCarousel(editions, idx));
                                ImageLoader.loadFull(MainActivity.this, editions.get(i).optString("url"), t);
                                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(168), 1); tp.setMargins(0, 0, i % 2 == 0 ? dp(6) : 0, 0);
                                g.addView(t, tp);
                            }
                            if (editions.size() % 2 == 1) g.addView(new View(MainActivity.this), new LinearLayout.LayoutParams(0, dp(168), 1));
                        });
                    }
                    @Override public void onError(String m) {}
                });
            }
            @Override public void onError(String m) {}
        });
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
                        Object nm = tm.opt("name"); name = nm instanceof JSONObject ? ((JSONObject) nm).optString("name", "") : tm.optString("name", "");
                        image = firstNonEmpty(tm.optString("url"), tm.optString("icon"), tm.optString("image"));
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
                runOnUiThread(() -> { JSONArray a = SalonStore.arr(MainActivity.this, "nfts"); a.put(item); SalonStore.setArr(MainActivity.this, "nfts", a); toast("Proof added — Save & publish to show it."); render(); });
            }
            @Override public void fail(String msg) { runOnUiThread(() -> toast("Couldn't prove: " + msg)); }
        });
    }

    private String firstNonEmpty(String... xs) { for (String x : xs) if (x != null && !x.isEmpty()) return x; return ""; }

    /* ================= EDIT hub ================= */

    private EditText edAvatar, edBanner, edWebvalidate; private TextView profStatus;

    private void renderEdit() {
        masthead("Edit page");
        JSONObject me = SalonStore.me(this);
        LinearLayout who = card(); who.addView(Design.lot(this, "You"));
        EditText name = field(who, "Display name", me.optString("name"), false, "");
        EditText bio = field(who, "Bio (one line)", me.optString("bio"), false, "");
        EditText about = fieldMulti(who, "About (long-form)", me.optString("about"));
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
        if (r == PICK_DM_IMG) {   // DM photo: encrypt to the relay blob store, send the ref
            toast("Sending photo…");
            io.execute(() -> {
                try {
                    byte[] jpeg = readScaledJpeg(uri, 1400);
                    String ref = new RelayUploader(Hosting.Profile.fresh(Hosting.TYPE_RELAY)).putFile(jpeg, "dm.jpg", "image/jpeg");
                    runOnUiThread(() -> sendDm("", ref, "image/jpeg"));
                } catch (Exception e) { runOnUiThread(() -> toast("Photo failed: " + e.getMessage())); }
            });
            return;
        }
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
                String url = Hosting.forProfile(def).putFile(bytes, rel, mime);
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
            if (!url.isEmpty()) ImageLoader.loadFull(this, url, iv);
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
        if (RelayResolver.isRelayRef(url)) {                 // decrypt to a cache file first
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
            if (!m.optString("url").isEmpty()) ImageLoader.loadFull(this, m.optString("url"), iv);
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
        if (RelayResolver.isRelayRef(url)) {                 // decrypt to a cache file first
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
            ImageLoader.loadFull(this, url, iv);
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
                Hosting.Uploader up = Hosting.forProfile(def);
                String profileUrl = up.putFile(profile.toString().getBytes("UTF-8"), h + "/profile.json", "application/json");
                if (!Hosting.TYPE_RELAY.equals(def.type()))
                    try { up.putFile(SALON_HTML.getBytes("UTF-8"), h + "/index.html", "text/html"); } catch (Exception ignore) {}
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
            if (!tipaddr.isEmpty()) copyRow(idc, "Tip address", tipaddr);
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

    private static final String[] HTYPES = { Hosting.TYPE_SFTP, Hosting.TYPE_WEBDAV, Hosting.TYPE_KUBO, Hosting.TYPE_PINATA, Hosting.TYPE_GITHUB, Hosting.TYPE_RELAY };
    private static final String[] HLABELS = { "SFTP", "WebDAV", "IPFS node", "Pinata", "GitHub", "Encrypted relay" };

    private void renderHostingEdit() {
        masthead(hostEdit == null ? "Add destination" : "Edit destination");
        if (hostEdit == null) hostEdit = Hosting.Profile.fresh(Hosting.TYPE_SFTP);
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
            case Hosting.TYPE_RELAY:
                cfgField(card, cfg, "relayUrl", "Relay URL", RelayUploader.DEFAULT_RELAY, false);
                card.addView(Design.note(this, "Publish with NO server of your own: your phone encrypts your page + media (libsodium) and uploads only the CIPHERTEXT to this relay's blob store — the relay can never read it. The decryption key travels in your public pointer, so any Salon viewer can see your page.\n\nTrade-offs: viewable in the Salon app only (a web browser can't decrypt it — SFTP/IPFS keep the browser view); and content expires ~7 days after it's last opened/viewed, so open the app now and then to keep it alive."), lp(0, 6, 0, 2));
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
                String url = Hosting.forProfile(p).putFile(("salon-test").getBytes("UTF-8"), rel, "text/plain"); Hosting.verifyUrl(url, p); msg = "OK — uploaded and served: " + url;
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

    private JSONObject httpGetJson(String url) {
        if (RelayResolver.isRelayRef(url)) {
            try { return RelayResolver.resolveJson(url); } catch (Exception e) { return null; }
        }
        HttpURLConnection c = null;
        try {
            if (url == null || !url.startsWith("http")) return null;
            URL u = new URL(url);
            if (ImageLoader.isBlockedHost(u.getHost())) return null;   // no SSRF to LAN/loopback/metadata via attacker-published profile URLs
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(10000); c.setRequestProperty("User-Agent", "TheSalon");
            if (c.getResponseCode() != 200) return null;
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
        LinearLayout r = row(); r.setPadding(0, dp(4), 0, dp(4));
        TextView key = Design.text(this, k.toUpperCase(), 9.5f, Design.DIM(), Design.sansBold()); key.setLetterSpacing(0.12f);
        r.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = Design.text(this, Util.shorten(full), 11.5f, Design.INK(), Design.mono()); val.setGravity(Gravity.END);
        copyOnTap(val, full); r.addView(val, new LinearLayout.LayoutParams(0, -2, 1.6f)); parent.addView(r);
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

    private void openUrl(String url) { try { String u = url.trim(); if (!u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) u = "http://" + u; startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception e) { toast("Couldn't open link"); } }
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
        + "<div id=b></div></div><script>fetch('./profile.json').then(r=>r.json()).then(p=>{var e=function(s){var d=document.createElement('div');d.innerHTML=s;return d.firstChild};"
        + "var b=document.getElementById('b');var h='';if(p.banner)h+=\"<img class=ban src='\"+p.banner+\"'>\";"
        + "h+=\"<img class=av src='\"+(p.avatar||'')+\"'>\";h+='<h1>'+(p.name||'')+'</h1>';h+=\"<div class=h>@\"+(p.handle||'')+'</div>';if(p.bio)h+='<div class=bio>'+p.bio+'</div>';"
        + "if(p.about){h+='<div class=k>About</div><div>'+p.about+'</div>'}"
        + "if(p.links&&p.links.length){h+='<div class=k>Links</div>';p.links.forEach(function(l){h+=\"<a class=l href='\"+l.url+\"'>\"+(l.label||l.url)+'</a>'})}"
        + "if(p.gallery&&p.gallery.length){h+='<div class=k>Gallery</div><div class=g>';p.gallery.forEach(function(m){if(m.type=='video')h+=\"<video src='\"+m.url+\"' controls></video>\";else if(m.type=='audio')h+=\"<div style='display:flex;align-items:center;justify-content:center'>&#9835;</div>\";else h+=\"<img src='\"+m.url+\"'>\"});h+='</div>';p.gallery.forEach(function(m){if(m.type=='audio')h+=\"<audio src='\"+m.url+\"' controls></audio>\"})}"
        + "if(p.posts&&p.posts.length){h+='<div class=k>Posts</div>';p.posts.slice().reverse().forEach(function(t){h+='<div class=post>'+(t.text||'');if(t.media){if(t.type=='video')h+=\"<video src='\"+t.media+\"' controls></video>\";else if(t.type=='audio')h+=\"<audio src='\"+t.media+\"' controls></audio>\";else h+=\"<img class=pm src='\"+t.media+\"'>\"}h+='</div>'})}"
        + "b.innerHTML=h}).catch(function(){document.getElementById('b').innerHTML='<p>Profile not found.</p>'})</script></body></html>";
}
