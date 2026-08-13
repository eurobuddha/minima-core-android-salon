# The Salon

A **native Minima Core companion app** for a fully decentralised social identity.

- **Your identity is a token you own** — a 1/1 signed token (`tokencreate … signtoken`)
  whose metadata commits to your handle + a profile URL. The chain proves *who* and *where*.
- **Your page is a file you host** — a `profile.json` on **your own storage** (SFTP straight
  to your server, or WebDAV / IPFS / GitHub / Pinata). Edit it freely: name, bio, avatar,
  banner, posts change instantly with **no transaction and no fee** — the chain never sees the edit.

No Maxima, no platform, no central server. Everything is MinimaCore-native.

## Status

**Milestone 1 (v0.1.x): identity + profile hosting.**
- Hosting settings with **SFTP** (Host / Port / User / password or PEM key / remote root /
  public URL prefix), plus WebDAV, IPFS (kubo), Pinata, GitHub — Add / Test / Edit / Delete /
  default. First-contact SSH host keys are pinned (TOFU) with a trust prompt.
- **Claim** a handle → host your first `profile.json` → mint your signed identity token →
  the app polls the wallet, and **auto-adopts** your token on open (reinstall-proof; never
  double-mints — an existing salon token is adopted, never re-created).
- **My Salon** page + **Edit** (name / bio / avatar / banner, images picked → compressed →
  uploaded via SFTP), re-hosting `profile.json` to a stable URL.

**Milestone 2 (v0.2.x): a real, media-rich, discoverable social space.**
- **Rich page** — `profile.json` now carries `about`, `links[]`, `gallery[]`, and `posts[]`.
  Editors for each (add / remove) live under **Edit my page**; every save re-hosts the file
  (no chain txn). Nothing is truncated — tokenid is tap-to-copy, the profile URL is a real link.
- **Photos, video and music** — pick an image / video / audio file → it uploads to your own
  storage (`<handle>/media/…`; images compressed, A/V size-capped) → shows in your gallery
  and posts, with **in-app playback** (fullscreen image zoom, `VideoView`, streaming audio).
- **The town square** — a shared on-chain address `SALON_ADDRESS = 0x53414C4F4E`. **Publish**
  sends a dust coin whose state points at your `{tokenid, url, handle}`; **Discover** reads
  every pointer off that address on any node (deduped by tokenid, latest-wins) — no server,
  no directory company.
- **View anyone** — tap a Discover card → the app fetches that profile's `profile.json` and
  renders it with the same page renderer used for your own.
- **Follows + Feed** — follow a *token* (device-side list); the **Feed** pull-fetches each
  followed profile's posts, merges them by timestamp, and is the default landing once you
  follow someone.
- **Public web page** — a static `web/salon.html` renderer is uploaded beside `profile.json`,
  so `http://<server>/salon/<handle>/` shows a real page to anyone with the link, in a browser.

Later milestones: DMs (Minima Mail), commerce (tip jar / paid content / shop), replies /
reactions, and registry re-announce hardening (coins can be pruned).

## Build

```
./gradlew assembleRelease      # APK → app/build/outputs/apk/release/
./gradlew assembleDebug        # debug build / compile check
```

Requires the Minima family release keystore props in `~/.gradle/gradle.properties`
(`MINIMA_FAMILY_RELEASE_*`); without them, release falls back to debug signing.

Talks to the node via the bundled `minimaapi.aar` broadcast IPC — **enable The Salon in
Minima → Apps** on the device so it can pair (the header chip reads *no node* until you do).

## Architecture

Scaffolded from Atelier's Android app (`mds/statenft-suite/android`); reuses its proven
infrastructure verbatim (package-renamed to `com.eurobuddha.salon`):

- `NodeApi` — Minima Core broadcast IPC (+ file hand-off for large replies).
- `Hosting` + `SftpUploader` / `WebDavUploader` / `IpfsUploader` / `PinataUploader` /
  `GithubUploader` + `HostingStore` + `Crypt` — the hosting stack (secrets encrypted at rest).
- `Design` (Katalog: paper / ink / vermilion, block shadows, lot numbers),
  `ImageLoader` / `Identicon` / `WebValidate` / `ImageTools` / `SvgSanitizer` / `Util`.

Salon-specific:
- `MainActivity` — screen framework (Feed / Discover / My Salon / View / Edit / Settings /
  Hosting), onboard + claim, the one `renderProfilePage` renderer, media pick / upload / play.
- `SalonRegistry` — the town square: `announce` (dust coin + state pointer) and `list`
  (`coins address:0x53414C4F4E` → parse / dedupe).
- `SalonStore` — local identity cache + rich-content draft (`links` / `gallery` / `posts`)
  + the `follows` set.
- `web/salon.html` — the public browser renderer (kept in sync with the inlined copy the app
  uploads).

Licensed the same as the rest of the Minima build family. Experimental — it moves real
tokens and posts to a live chain; test small.
