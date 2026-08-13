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

Roadmap: milestone 2 — Discover (on-chain registry), follows, the pull Feed, and posts.

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

Salon-specific: `MainActivity` (screen framework, onboard, hosting editor, profile edit,
My Salon), `SalonStore` (local identity cache).

Licensed the same as the rest of the Minima build family. Experimental — it moves real
tokens and posts to a live chain; test small.
