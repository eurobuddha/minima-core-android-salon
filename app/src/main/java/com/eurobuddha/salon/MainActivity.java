package com.eurobuddha.salon;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Salon — native Minima Core companion. Identity = a 1/1 signed token you own;
 * your page = a profile.json you host on your OWN storage (SFTP / WebDAV / IPFS /
 * GitHub) and edit freely. Milestone 1: hosting settings, claim + mint identity,
 * create/host/view your Salon page.
 */
public class MainActivity extends AppCompatActivity {

    private NodeApi node;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private enum Screen { HOME, SETTINGS, HOSTING, HOSTING_EDIT, ONBOARD, PROFILE_EDIT }
    private Screen screen = Screen.HOME;

    private LinearLayout appbar, navRow, body;
    private FrameLayout rootFrame;
    private TextView nodeChip;
    private boolean nodeUp = false;
    private String pubkey = "";

    private Hosting.Profile hostEdit;     // profile being edited
    private int insetTop = 0, insetBottom = 0;
    private TextView claimBtn;            // the Claim button (disabled while minting)
    private boolean claiming = false;     // hard guard: one claim at a time, ever

    /* ---------------- lifecycle ---------------- */

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Design.PAPER());

        LinearLayout chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);

        appbar = new LinearLayout(this);
        appbar.setOrientation(LinearLayout.VERTICAL);
        appbar.setBackgroundColor(Design.PAPER());
        chrome.addView(appbar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(10), dp(16), dp(28));
        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        chrome.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        navRow = new LinearLayout(this);
        navRow.setOrientation(LinearLayout.HORIZONTAL);
        navRow.setBackgroundColor(Design.PAPER());
        chrome.addView(navRow, new LinearLayout.LayoutParams(-1, -2));

        rootFrame.addView(chrome, new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootFrame);

        WindowInsetsControllerCompat wic = new WindowInsetsControllerCompat(getWindow(), rootFrame);
        wic.setAppearanceLightStatusBars(true);
        wic.setAppearanceLightNavigationBars(true);
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            insetTop = sys.top;
            insetBottom = Math.max(sys.bottom, ime.bottom);
            appbar.setPadding(0, insetTop, 0, 0);
            navRow.setPadding(0, 0, 0, ime.bottom > 0 ? 0 : sys.bottom);
            return insets;
        });

        node = new NodeApi(this, enabled -> runOnUiThread(() -> { nodeUp = enabled; onNode(); }));
        buildNav();
        render();
    }

    private boolean adoptChecked = false;

    private void onNode() {
        paintNodeChip();
        if (nodeUp && pubkey.isEmpty()) fetchPubkey();
        // Reinstall-proof + resolves a claim whose token confirmed after we stopped
        // polling: if the wallet holds a salon token but we have no local identity,
        // adopt it — never a fresh mint.
        if (nodeUp && !adoptChecked && !claiming && !SalonStore.hasIdentity(this)) {
            adoptChecked = true;
            node.cmd("balance", new NodeApi.Cb() {
                @Override public void onResult(JSONObject json) {
                    JSONObject tok = findAnySalonToken(json);
                    if (tok != null) runOnUiThread(() -> adoptFromToken(tok));
                }
                @Override public void onError(String m) {}
            });
        }
    }

    /** First salon token this wallet holds (the balance row), or null.
     *  handle == null matches ANY salon token. */
    private JSONObject findSalonEntry(JSONObject balanceJson, String handle) {
        try {
            JSONArray arr = balanceJson.optJSONArray("response");
            if (arr == null) return null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t == null) continue;
                JSONObject meta = t.optJSONObject("token");   // metadata object, directly
                if (meta == null) continue;                   // Minima's token is a plain string
                if ("1".equals(meta.optString("salon"))
                        && (handle == null || handle.equals(meta.optString("handle")))) return t;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private JSONObject findAnySalonToken(JSONObject balanceJson) { return findSalonEntry(balanceJson, null); }

    /** Store identity from an on-chain salon token's metadata and open My Salon. */
    private void adoptFromToken(JSONObject tokenRow) {
        try {
            JSONObject meta = tokenRow.optJSONObject("token");
            SalonStore.put(this, "tokenid", tokenRow.optString("tokenid"));
            SalonStore.put(this, "handle", meta.optString("handle"));
            SalonStore.put(this, "name", meta.optString("name"));
            SalonStore.put(this, "bio", meta.optString("bio"));
            SalonStore.put(this, "profileUrl", meta.optString("url"));
            claiming = false;
            if (screen == Screen.HOME || screen == Screen.ONBOARD) go(Screen.HOME);
        } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() { super.onDestroy(); io.shutdownNow(); }

    /* ---------------- chrome ---------------- */

    private void buildNav() {
        navRow.removeAllViews();
        navRow.addView(Design.rule(this, 2), new LinearLayout.LayoutParams(-1, dp(2)));
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(navTab("My Salon", screen == Screen.HOME || screen == Screen.ONBOARD || screen == Screen.PROFILE_EDIT,
                () -> go(Screen.HOME)), weight1());
        tabs.addView(navTab("Settings", screen == Screen.SETTINGS || screen == Screen.HOSTING || screen == Screen.HOSTING_EDIT,
                () -> go(Screen.SETTINGS)), weight1());
        navRow.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
    }

    private View navTab(String label, boolean active, Runnable click) {
        TextView t = Design.text(this, label.toUpperCase(), 11.5f, active ? Design.INK() : Design.DIM(), Design.sansBold());
        t.setLetterSpacing(0.1f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(12));
        if (active) t.setBackgroundColor(0x14000000);
        t.setOnClickListener(v -> click.run());
        return t;
    }

    private void masthead(String title) {
        appbar.removeAllViews();
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.HORIZONTAL);
        pad.setGravity(Gravity.CENTER_VERTICAL);
        pad.setPadding(dp(16), dp(10), dp(16), dp(8));
        TextView w = Design.display(this, title, 22);
        pad.addView(w, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ver = Design.text(this, "№ " + BuildConfig.VERSION_NAME, 10.5f, Design.DIM(), Design.mono());
        ver.setPadding(0, 0, dp(8), 0);
        pad.addView(ver);
        nodeChip = Design.pill(this, "node", Design.PILL_DIM);
        pad.addView(nodeChip);
        appbar.addView(pad);
        appbar.addView(Design.rule(this, 2), new LinearLayout.LayoutParams(-1, dp(2)));
        paintNodeChip();
    }

    private void paintNodeChip() {
        if (nodeChip == null) return;
        nodeChip.setText(nodeUp ? "connected" : "no node");
    }

    private void go(Screen s) { screen = s; render(); }

    /* ---------------- router ---------------- */

    private void render() {
        buildNav();
        body.removeAllViews();
        switch (screen) {
            case HOME:          renderHome(); break;
            case ONBOARD:       renderOnboard(); break;
            case PROFILE_EDIT:  renderProfileEdit(); break;
            case SETTINGS:      renderSettings(); break;
            case HOSTING:       renderHosting(); break;
            case HOSTING_EDIT:  renderHostingEdit(); break;
        }
    }

    /* ---------------- HOME / My Salon ---------------- */

    private void renderHome() {
        if (!SalonStore.hasIdentity(this)) { masthead("The Salon"); renderOnboard(); return; }
        masthead("My Salon");
        JSONObject me = SalonStore.me(this);

        LinearLayout card = card();
        String banner = me.optString("banner", "");
        if (!banner.isEmpty()) {
            ImageView bn = new ImageView(this);
            bn.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ImageLoader.loadFull(this, banner, bn);
            card.addView(bn, new LinearLayout.LayoutParams(-1, dp(120)));
        }
        LinearLayout idrow = row();
        String avatar = me.optString("avatar", "");
        ImageView av = new ImageView(this);
        av.setScaleType(ImageView.ScaleType.CENTER_CROP);
        av.setImageBitmap(Identicon.forToken(me.optString("tokenid"), 200));
        if (!avatar.isEmpty()) ImageLoader.loadFull(this, avatar, av);
        idrow.addView(av, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout nameCol = new LinearLayout(this);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        nameCol.setPadding(dp(12), 0, 0, 0);
        nameCol.addView(Design.text(this, me.optString("name"), 18, Design.INK(), Design.sansBold()));
        nameCol.addView(Design.text(this, "@" + me.optString("handle"), 13, Design.ACCENT(), Design.mono()));
        idrow.addView(nameCol, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(idrow, lp(0, 10, 0, 6));
        if (!me.optString("bio").isEmpty()) card.addView(Design.body(this, me.optString("bio")), lp(0, 4, 0, 4));
        body.addView(card, lp(0, 0, 0, 12));

        LinearLayout links = card();
        links.addView(Design.lot(this, "Your page"));
        addKv(links, "Handle", "@" + me.optString("handle"));
        addKv(links, "Identity token", shorten(me.optString("tokenid")));
        addLinkKv(links, "Profile URL", me.optString("profileUrl"));
        body.addView(links, lp(0, 0, 0, 12));

        body.addView(btn("Edit my page", true, () -> go(Screen.PROFILE_EDIT)), lph(52, 0, 0, 0, 6));
    }

    /* ---------------- ONBOARD / claim identity ---------------- */

    private void renderOnboard() {
        LinearLayout intro = card();
        intro.addView(Design.lot(this, "№ 1 · Open your Salon"));
        intro.addView(Design.note(this, "Your identity becomes a signed token you own forever. Your page is a "
                + "file you host and can edit any time. First set a hosting destination, then claim your handle."), lp(0, 6, 0, 0));
        body.addView(intro, lp(0, 0, 0, 12));

        Hosting.Profile def = HostingStore.getDefault(this);
        LinearLayout hostCard = card();
        hostCard.addView(Design.lot(this, "Hosting"));
        addKv(hostCard, "Destination", def == null ? "none set — required" : def.name() + " · " + def.type());
        hostCard.addView(btn(def == null ? "Set up hosting" : "Manage hosting", def == null, () -> go(Screen.HOSTING)), lph(46, 0, 8, 0, 0));
        body.addView(hostCard, lp(0, 0, 0, 12));

        LinearLayout form = card();
        form.addView(Design.lot(this, "Claim your handle"));
        EditText handle = field(form, "Handle", SalonStore.get(this, "handle"), false, "e.g. eurobuddha");
        EditText name = field(form, "Display name", SalonStore.get(this, "name"), false, "Euro Buddha");
        EditText bio = field(form, "Bio", SalonStore.get(this, "bio"), false, "one line about you");
        final TextView status = Design.note(this, "");
        form.addView(status, lp(0, 8, 0, 0));
        claimBtn = btn("Claim my Salon", true, () -> claimIdentity(
                text(handle), text(name), text(bio), status));
        claimBtn.setEnabled(!claiming);
        if (claiming) claimBtn.setAlpha(0.4f);
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
        status.setText("Publishing your page to your hosting…");

        io.execute(() -> {
            try {
                JSONObject profile = buildProfile(h, n, b, "", "");
                String rel = h + "/profile.json";
                Hosting.Uploader up = Hosting.forProfile(def);
                String profileUrl = up.putFile(profile.toString().getBytes("UTF-8"), rel, "application/json");
                Hosting.verifyUrl(profileUrl, def);
                runOnUiThread(() -> {
                    SalonStore.put(this, "profileUrl", profileUrl);
                    status.setText("Page live. Checking for your identity…");
                    adoptOrMint(h, n, b, profileUrl, status);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { status.setText("Hosting failed: " + e.getMessage()); claimFailed(); });
            }
        });
    }

    /** Idempotent: if a salon token for this handle already exists in the wallet,
     *  ADOPT it — never mint a second. Only mint when none is found. */
    private void adoptOrMint(String h, String n, String b, String url, TextView status) {
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                String tid = findSalonToken(json, h);
                if (!tid.isEmpty()) { runOnUiThread(() -> finishClaim(tid)); return; }
                runOnUiThread(() -> { status.setText("Minting your identity token…"); mintIdentity(h, n, b, url, status); });
            }
            @Override public void onError(String m) {
                runOnUiThread(() -> { status.setText("Minting your identity token…"); mintIdentity(h, n, b, url, status); });
            }
        });
    }

    private void mintIdentity(String handle, String name, String bio, String profileUrl, TextView status) {
        JSONObject meta = new JSONObject();
        putJson(meta, "salon", "1");
        putJson(meta, "handle", handle);
        putJson(meta, "name", name);
        putJson(meta, "url", profileUrl);
        putJson(meta, "bio", bio);
        String cmd = "tokencreate name:" + meta + " amount:1 decimals:0 signtoken:" + pubkey;
        node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                if (!json.optBoolean("status", false)) {
                    runOnUiThread(() -> { status.setText("Mint failed: " + json.optString("error", "tokencreate rejected")); claimFailed(); });
                    return;
                }
                runOnUiThread(() -> { status.setText("Minting… confirming on-chain (leave this open)."); pollForIdentity(handle, 0, status); });
            }
            @Override public void onError(String m) { runOnUiThread(() -> { status.setText("Mint failed: " + m); claimFailed(); }); }
        });
    }

    /** After tokencreate, watch the wallet for our new salon token by handle. */
    private void pollForIdentity(String handle, int tries, TextView status) {
        node.cmd("balance", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                String tid = findSalonToken(json, handle);
                if (!tid.isEmpty()) {
                    runOnUiThread(() -> finishClaim(tid));
                } else if (tries < 30) {
                    body.postDelayed(() -> pollForIdentity(handle, tries + 1, status), 4000);
                } else {
                    runOnUiThread(() -> { status.setText("Minted — confirming. Reopen shortly and it'll adopt your token."); claiming = false; });
                }
            }
            @Override public void onError(String m) {
                if (tries < 30) body.postDelayed(() -> pollForIdentity(handle, tries + 1, status), 4000);
                else runOnUiThread(() -> claiming = false);
            }
        });
    }

    private void finishClaim(String tokenid) {
        SalonStore.put(this, "tokenid", tokenid);
        claiming = false;
        Toast.makeText(this, "Your Salon is open.", Toast.LENGTH_LONG).show();
        go(Screen.HOME);
    }

    private void claimFailed() { claiming = false; setClaimEnabled(true); }

    private void setClaimEnabled(boolean on) {
        if (claimBtn != null) { claimBtn.setEnabled(on); claimBtn.setAlpha(on ? 1f : 0.4f); }
    }

    /** balance entry shape: { "token": {salon,handle,name,url,bio}, "tokenid", … }
     *  — the metadata object is DIRECTLY under "token" (not token.name). */
    private String findSalonToken(JSONObject balanceJson, String handle) {
        JSONObject t = findSalonEntry(balanceJson, handle);
        return t == null ? "" : t.optString("tokenid");
    }

    /* ---------------- PROFILE EDIT ---------------- */

    private static final int PICK_AVATAR = 41, PICK_BANNER = 42;
    private EditText edAvatar, edBanner;   // so upload callbacks can fill them
    private TextView profStatus;

    private void renderProfileEdit() {
        masthead("Edit page");
        JSONObject me = SalonStore.me(this);
        LinearLayout form = card();
        form.addView(Design.lot(this, "Your page"));
        EditText name = field(form, "Display name", me.optString("name"), false, "");
        EditText bio = field(form, "Bio", me.optString("bio"), false, "");
        edAvatar = field(form, "Avatar image URL", me.optString("avatar"), false, "https://…/avatar.jpg");
        form.addView(btn("Upload avatar from photos", false, () -> pickImage(PICK_AVATAR)), lph(44, 0, 4, 0, 8));
        edBanner = field(form, "Banner image URL", me.optString("banner"), false, "https://…/banner.jpg");
        form.addView(btn("Upload banner from photos", false, () -> pickImage(PICK_BANNER)), lph(44, 0, 4, 0, 8));
        profStatus = Design.note(this, "");
        form.addView(profStatus, lp(0, 8, 0, 0));
        form.addView(btn("Save page", true, () -> saveProfile(text(name), text(bio), text(edAvatar), text(edBanner))), lph(52, 0, 8, 0, 0));
        body.addView(form, lp(0, 0, 0, 12));
    }

    private void saveProfile(String name, String bio, String avatar, String banner) {
        Hosting.Profile def = HostingStore.getDefault(this);
        if (def == null) { profStatus.setText("Set hosting first."); return; }
        String handle = SalonStore.get(this, "handle");
        profStatus.setText("Saving your page to your hosting…");
        io.execute(() -> {
            try {
                JSONObject profile = buildProfile(handle, name, bio, avatar, banner);
                String rel = handle + "/profile.json";
                Hosting.Uploader up = Hosting.forProfile(def);
                String url = up.putFile(profile.toString().getBytes("UTF-8"), rel, "application/json");
                Hosting.verifyUrl(url, def);
                runOnUiThread(() -> {
                    SalonStore.put(this, "name", name); SalonStore.put(this, "bio", bio);
                    SalonStore.put(this, "avatar", avatar); SalonStore.put(this, "banner", banner);
                    SalonStore.put(this, "profileUrl", url);
                    Toast.makeText(this, "Page updated — instantly, no fee.", Toast.LENGTH_SHORT).show();
                    go(Screen.HOME);
                });
            } catch (Exception e) {
                runOnUiThread(() -> profStatus.setText("Save failed: " + e.getMessage()));
            }
        });
    }

    private JSONObject buildProfile(String handle, String name, String bio, String avatar, String banner) {
        JSONObject p = new JSONObject();
        putJson(p, "v", "1"); putJson(p, "handle", handle); putJson(p, "name", name);
        putJson(p, "bio", bio); putJson(p, "avatar", avatar); putJson(p, "banner", banner);
        putJson(p, "updated", Long.toString(System.currentTimeMillis() / 1000));
        try { p.put("posts", new JSONArray()); } catch (Exception ignored) {}
        return p;
    }

    /* ---------------- image pick + upload ---------------- */

    private void pickImage(int code) {
        if (HostingStore.getDefault(this) == null) { toast("Set hosting first."); return; }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(Intent.createChooser(i, "Choose image"), code);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if ((req != PICK_AVATAR && req != PICK_BANNER) || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final boolean avatar = req == PICK_AVATAR;
        if (profStatus != null) profStatus.setText("Uploading image…");
        io.execute(() -> {
            try {
                byte[] jpeg = readScaledJpeg(uri, avatar ? 640 : 1280);
                Hosting.Profile def = HostingStore.getDefault(this);
                String handle = SalonStore.get(this, "handle");
                String rel = handle + "/" + (avatar ? "avatar-" : "banner-") + Hosting.ts36() + ".jpg";
                String url = Hosting.forProfile(def).putFile(jpeg, rel, "image/jpeg");
                runOnUiThread(() -> {
                    if (avatar && edAvatar != null) edAvatar.setText(url);
                    if (!avatar && edBanner != null) edBanner.setText(url);
                    if (profStatus != null) profStatus.setText("Image uploaded.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (profStatus != null) profStatus.setText("Upload failed: " + e.getMessage()); });
            }
        });
    }

    private byte[] readScaledJpeg(Uri uri, int maxDim) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        Bitmap bmp = BitmapFactory.decodeStream(in);
        if (in != null) in.close();
        if (bmp == null) throw new Exception("could not read image");
        int w = bmp.getWidth(), h = bmp.getHeight();
        float k = Math.min(1f, (float) maxDim / Math.max(w, h));
        if (k < 1f) bmp = Bitmap.createScaledBitmap(bmp, Math.round(w * k), Math.round(h * k), true);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos);
        return bos.toByteArray();
    }

    /* ---------------- SETTINGS ---------------- */

    private void renderSettings() {
        masthead("Settings");
        LinearLayout nodeCard = card();
        nodeCard.addView(Design.lot(this, "Minima Core"));
        addKv(nodeCard, "Node", nodeUp ? "connected" : "not connected — enable The Salon in Minima → Apps");
        body.addView(nodeCard, lp(0, 0, 0, 12));

        Hosting.Profile def = HostingStore.getDefault(this);
        LinearLayout hostCard = card();
        hostCard.addView(Design.lot(this, "Hosting"));
        hostCard.addView(Design.note(this, "Upload your page and images to your OWN storage — SFTP straight to your server, or WebDAV / IPFS / GitHub."), lp(0, 4, 0, 6));
        addKv(hostCard, "Default", def == null ? "none yet" : def.name() + " · " + def.type());
        hostCard.addView(btn("Manage destinations", true, () -> go(Screen.HOSTING)), lph(48, 0, 8, 0, 0));
        body.addView(hostCard, lp(0, 0, 0, 12));

        if (SalonStore.hasIdentity(this)) {
            JSONObject me = SalonStore.me(this);
            LinearLayout idc = card();
            idc.addView(Design.lot(this, "Identity"));
            addKv(idc, "Handle", "@" + me.optString("handle"));
            addKv(idc, "Token", shorten(me.optString("tokenid")));
            body.addView(idc, lp(0, 0, 0, 12));
        }

        LinearLayout about = card();
        about.addView(Design.lot(this, "Colophon"));
        addKv(about, "App", "The Salon");
        addKv(about, "Version", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        body.addView(about, lp(0, 0, 0, 12));
    }

    /* ---------------- HOSTING list + edit ---------------- */

    private void renderHosting() {
        masthead("Hosting");
        List<Hosting.Profile> profiles = HostingStore.list(this);
        if (profiles.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(Design.note(this, "No destinations yet. Add one — SFTP uploads straight to your own server; "
                    + "no server? a free Pinata key pins to IPFS."));
            body.addView(empty, lp(0, 0, 0, 12));
        }
        for (Hosting.Profile p : profiles) {
            LinearLayout card = card();
            LinearLayout head = row();
            head.addView(Design.text(this, p.name().isEmpty() ? "(unnamed)" : p.name(), 15, Design.INK(), Design.sansBold()),
                    new LinearLayout.LayoutParams(0, -2, 1));
            head.addView(Design.pill(this, p.type(), Design.PILL_DIM));
            if (p.isDefault()) head.addView(Design.pill(this, "default", Design.PILL_DONE));
            card.addView(head, lp(0, 0, 0, 8));
            LinearLayout r = row();
            r.addView(btn("Test", false, () -> testProfile(p)), weight(31, 0, 4));
            r.addView(btn("Edit", false, () -> { hostEdit = p; go(Screen.HOSTING_EDIT); }), weight(31, 4, 4));
            r.addView(btn("Delete", false, () -> { HostingStore.remove(this, p.id()); render(); }), weight(31, 4, 0));
            card.addView(r, lp(0, 0, 0, 6));
            if (!p.isDefault())
                card.addView(btn("Make default", false, () -> { HostingStore.setDefault(this, p.id()); render(); }), lph(42, 0, 0, 0, 0));
            body.addView(card, lp(0, 0, 0, 12));
        }
        body.addView(btn("Add destination", true, () -> { hostEdit = null; go(Screen.HOSTING_EDIT); }), lph(52, 0, 4, 0, 8));
    }

    private static final String[] HTYPES = { Hosting.TYPE_SFTP, Hosting.TYPE_WEBDAV, Hosting.TYPE_KUBO, Hosting.TYPE_PINATA, Hosting.TYPE_GITHUB };
    private static final String[] HLABELS = { "SFTP", "WebDAV", "IPFS node", "Pinata", "GitHub" };

    private void renderHostingEdit() {
        masthead(hostEdit == null ? "Add destination" : "Edit destination");
        if (hostEdit == null) hostEdit = Hosting.Profile.fresh(Hosting.TYPE_SFTP);
        final Hosting.Profile p = hostEdit;

        LinearLayout card = card();
        EditText nameF = field(card, "Name", p.name(), false, "my server");
        nameF.addTextChangedListener(watch(s -> Hosting.put(p.j, "name", s)));

        card.addView(Design.kicker(this, "Type"), lp(0, 8, 0, 4));
        LinearLayout chips = row();
        for (int i = 0; i < HTYPES.length; i++) {
            final String t = HTYPES[i];
            TextView chip = Design.chip(this, HLABELS[i], t.equals(p.type()));
            chip.setPadding(dp(11), dp(6), dp(11), dp(6));
            chip.setOnClickListener(v -> { if (!t.equals(p.type())) { Hosting.put(p.j, "type", t);
                if (p.j.optJSONObject(t) == null) Hosting.put(p.j, t, new JSONObject()); render(); } });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2); clp.rightMargin = dp(6);
            chips.addView(chip, clp);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false); hs.addView(chips);
        card.addView(hs, lp(0, 0, 0, 6));

        JSONObject cfg = p.cfg();
        switch (p.type()) {
            case Hosting.TYPE_SFTP:
                cfgField(card, cfg, "host", "Host", "eurobuddha.com", false);
                cfgField(card, cfg, "port", "Port", "22", false);
                cfgField(card, cfg, "user", "User", "root", false);
                boolean keyAuth = "key".equals(cfg.optString("auth", "password"));
                card.addView(Design.kicker(this, "Authentication"), lp(0, 8, 0, 4));
                LinearLayout ar = row();
                TextView pw = Design.chip(this, "Password", !keyAuth), ky = Design.chip(this, "Private key", keyAuth);
                pw.setPadding(dp(11), dp(6), dp(11), dp(6)); ky.setPadding(dp(11), dp(6), dp(11), dp(6));
                pw.setOnClickListener(v -> { Hosting.put(cfg, "auth", "password"); render(); });
                ky.setOnClickListener(v -> { Hosting.put(cfg, "auth", "key"); render(); });
                LinearLayout.LayoutParams m8 = new LinearLayout.LayoutParams(-2, -2); m8.rightMargin = dp(8);
                ar.addView(pw, m8); ar.addView(ky);
                card.addView(ar, lp(0, 0, 0, 6));
                if (keyAuth) cfgSecretMulti(card, cfg, "privateKey", "Private key (paste PEM)");
                else cfgSecret(card, cfg, "password", "Password");
                cfgField(card, cfg, "remoteRoot", "Remote root (server dir)", "/var/www/html/salon", false);
                cfgField(card, cfg, "urlPrefix", "Public URL prefix", "https://eurobuddha.com/salon/", false);
                break;
            case Hosting.TYPE_WEBDAV:
                cfgField(card, cfg, "endpoint", "Write endpoint", "https://dav.example.com/files/", false);
                cfgField(card, cfg, "user", "User", "", false);
                cfgSecret(card, cfg, "password", "Password");
                cfgField(card, cfg, "urlPrefix", "Public URL prefix", "https://example.com/files/", false);
                break;
            case Hosting.TYPE_KUBO:
                cfgField(card, cfg, "apiUrl", "kubo API", "http://127.0.0.1:5001", false);
                cfgField(card, cfg, "gateway", "Public gateway", "https://ipfs.eurobuddha.com", false);
                break;
            case Hosting.TYPE_PINATA:
                cfgSecret(card, cfg, "jwt", "Pinata JWT");
                cfgField(card, cfg, "gateway", "Gateway", "https://gateway.pinata.cloud", false);
                break;
            case Hosting.TYPE_GITHUB:
                cfgField(card, cfg, "owner", "Owner", "", false);
                cfgField(card, cfg, "repo", "Repo", "", false);
                cfgField(card, cfg, "branch", "Branch", "main", false);
                cfgSecret(card, cfg, "token", "Token (PAT)");
                cfgField(card, cfg, "serve", "Serve via (raw/pages)", "raw", false);
                cfgField(card, cfg, "pagesPrefix", "Pages prefix", "", false);
                break;
        }

        final TextView status = Design.note(this, "");
        card.addView(status, lp(0, 8, 0, 0));
        LinearLayout btns = row();
        btns.addView(btn("Save", true, () -> { saveHost(p); go(Screen.HOSTING); }), weight(48, 0, 4));
        btns.addView(btn("Save & test", false, () -> { saveHost(p); testProfile(p, status); }), weight(48, 4, 0));
        card.addView(btns, lp(0, 8, 0, 0));
        body.addView(card, lp(0, 0, 0, 12));
    }

    private void saveHost(Hosting.Profile p) {
        if (p.name().isEmpty()) Hosting.put(p.j, "name", p.type());
        // Salon's own path templates (Atelier default names -> salon)
        Hosting.put(p.j, "pathTemplate", "salon/{collection}/{name}-{ts}{ext}");
        Hosting.put(p.j, "dirTemplate", "salon/{collection}-{ts}");
        HostingStore.upsert(this, p);
        if (HostingStore.getDefault(this) == null || HostingStore.list(this).size() == 1)
            HostingStore.setDefault(this, p.id());
    }

    private void testProfile(Hosting.Profile p) { testProfile(p, null); }

    private void testProfile(Hosting.Profile p, TextView status) {
        if (status != null) status.setText("Testing…"); else toast("Testing " + p.name() + "…");
        io.execute(() -> {
            String msg;
            try {
                String rel = "_test/probe-" + Hosting.ts36() + ".txt";
                Hosting.Uploader up = Hosting.forProfile(p);
                String url = up.putFile(("salon-test " + System.currentTimeMillis()).getBytes("UTF-8"), rel, "text/plain");
                Hosting.verifyUrl(url, p);
                msg = "OK — uploaded and served: " + url;
            } catch (SftpUploader.HostKeyUnverified hk) {
                runOnUiThread(() -> promptTrustHostKey(p, hk.fingerprint, status));
                return;
            } catch (Exception e) { msg = "Failed: " + e.getMessage(); }
            final String m = msg;
            runOnUiThread(() -> { if (status != null) status.setText(m); else toast(m); });
        });
    }

    /** First-contact TOFU: show the server's SSH host-key fingerprint and, on the
     *  user's OK, pin it to the profile so every future connect is verified. */
    private void promptTrustHostKey(Hosting.Profile p, String fp, TextView status) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Trust this server?")
                .setMessage("First connection to " + p.cfgStr("host") + ".\n\nSSH host-key fingerprint:\n" + fp
                        + "\n\nTrust it only if this is your server. It'll be pinned — if the key ever changes "
                        + "you'll be warned (possible interception).")
                .setPositiveButton("Trust & pin", (d, w) -> {
                    Hosting.put(p.cfg(), "hostKeyFp", fp);
                    HostingStore.upsert(this, p);
                    testProfile(p, status);
                })
                .setNegativeButton("Cancel", (d, w) -> { if (status != null) status.setText("Not trusted — nothing saved."); })
                .show();
    }

    /* ---------------- node key ---------------- */

    private void fetchPubkey() {
        node.cmd("keys", new NodeApi.Cb() {
            @Override public void onResult(JSONObject json) {
                String pk = firstPublicKey(json);
                if (!pk.isEmpty()) runOnUiThread(() -> pubkey = pk);
                else node.cmd("getaddress", new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j2) {
                        JSONObject r = j2.optJSONObject("response");
                        if (r != null && !r.optString("publickey").isEmpty()) runOnUiThread(() -> pubkey = r.optString("publickey"));
                    }
                    @Override public void onError(String m) {}
                });
            }
            @Override public void onError(String m) {}
        });
    }

    private String firstPublicKey(JSONObject json) {
        Object resp = json.opt("response");
        try {
            if (resp instanceof JSONArray) {
                JSONArray a = (JSONArray) resp;
                if (a.length() > 0 && a.optJSONObject(0) != null) return a.optJSONObject(0).optString("publickey", "");
            } else if (resp instanceof JSONObject) {
                JSONArray keys = ((JSONObject) resp).optJSONArray("keys");
                if (keys != null && keys.length() > 0 && keys.optJSONObject(0) != null) return keys.optJSONObject(0).optString("publickey", "");
                return ((JSONObject) resp).optString("publickey", "");
            }
        } catch (Exception ignored) {}
        return "";
    }

    /* ---------------- UI helpers ---------------- */

    private int dp(int v) { return Design.dp(this, v); }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Design.blockShadow(this, Design.CARD()));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        return c;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void addKv(LinearLayout parent, String k, String v) {
        LinearLayout r = row();
        r.setPadding(0, dp(4), 0, dp(4));
        TextView key = Design.text(this, k.toUpperCase(), 9.5f, Design.DIM(), Design.sansBold());
        key.setLetterSpacing(0.12f);
        r.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = Design.text(this, v == null ? "—" : v, 11.5f, Design.INK(), Design.mono());
        val.setGravity(Gravity.END);
        r.addView(val, new LinearLayout.LayoutParams(0, -2, 1.5f));
        parent.addView(r);
    }

    private void addLinkKv(LinearLayout parent, String k, String url) {
        LinearLayout r = row();
        r.setPadding(0, dp(4), 0, dp(4));
        TextView key = Design.text(this, k.toUpperCase(), 9.5f, Design.DIM(), Design.sansBold());
        key.setLetterSpacing(0.12f);
        r.addView(key, new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = Design.text(this, url == null ? "—" : url, 11f, Design.ACCENT(), Design.mono());
        val.setGravity(Gravity.END);
        val.setPaintFlags(val.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        val.setMaxLines(2);
        if (url != null && !url.isEmpty()) val.setOnClickListener(v -> openUrl(url));
        r.addView(val, new LinearLayout.LayoutParams(0, -2, 1.8f));
        parent.addView(r);
    }

    private EditText field(LinearLayout parent, String label, String value, boolean secret, String hint) {
        parent.addView(Design.text(this, label.toUpperCase(), 9.5f, Design.DIM(), Design.sansBold()), lp(0, 8, 0, 3));
        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setHint(hint);
        e.setTextColor(Design.INK());
        e.setTextSize(14);
        e.setBackground(Design.ruled(this, Design.PAPER(), Design.INK(), 1));
        e.setPadding(dp(10), dp(9), dp(10), dp(9));
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return e;
    }

    /** cfg field bound to the profile JSON (plaintext). */
    private void cfgField(LinearLayout parent, JSONObject cfg, String key, String label, String hint, boolean secret) {
        EditText e = field(parent, label, cfg.optString(key, ""), secret, hint);
        e.addTextChangedListener(watch(s -> Hosting.put(cfg, key, s)));
    }

    /** secret cfg field — stored encrypted, prefilled decrypted. */
    private void cfgSecret(LinearLayout parent, JSONObject cfg, String key, String label) {
        String plain = Crypt.decrypt(cfg.optString(key, ""));
        EditText e = field(parent, label, plain, true, "••••••");
        e.addTextChangedListener(watch(s -> Hosting.put(cfg, key, Crypt.encrypt(s))));
    }

    private void cfgSecretMulti(LinearLayout parent, JSONObject cfg, String key, String label) {
        parent.addView(Design.text(this, label.toUpperCase(), 9.5f, Design.DIM(), Design.sansBold()), lp(0, 8, 0, 3));
        EditText e = new EditText(this);
        e.setText(Crypt.decrypt(cfg.optString(key, "")));
        e.setTextColor(Design.INK()); e.setTextSize(11);
        e.setTypeface(Design.mono());
        e.setBackground(Design.ruled(this, Design.PAPER(), Design.INK(), 1));
        e.setPadding(dp(10), dp(9), dp(10), dp(9));
        e.setMinLines(4); e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.addTextChangedListener(watch(s -> Hosting.put(cfg, key, Crypt.encrypt(s))));
        parent.addView(e, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView btn(String label, boolean filled, Runnable click) {
        TextView b = Design.button(this, label, filled);
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private void openUrl(String url) {
        try {
            String u = url.trim();
            if (!u.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) u = "https://" + u;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) { toast("Couldn't open link"); }
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
    private String text(EditText e) { return e.getText().toString(); }
    private String shorten(String s) { return s == null || s.length() < 14 ? s : s.substring(0, 8) + "…" + s.substring(s.length() - 4); }
    private static void putJson(JSONObject o, String k, String v) { try { o.put(k, v == null ? "" : v); } catch (Exception ignored) {} }

    private TextWatcher watch(final java.util.function.Consumer<String> onText) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { onText.accept(s.toString()); }
        };
    }

    /* layout param helpers */
    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }
    private LinearLayout.LayoutParams lph(int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(h));
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }
    private LinearLayout.LayoutParams weight(int hDp, int lm, int rm) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(hDp), 1);
        p.setMargins(dp(lm), 0, dp(rm), 0); return p;
    }
    private LinearLayout.LayoutParams weight1() { return new LinearLayout.LayoutParams(0, -2, 1); }
}
