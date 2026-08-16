# Salon — authoritative working rules. Follow exactly.

This app is part of the Minima build family: **real funds, real chain, real
complexity.** Reversibility and traceability are not optional.

## RULE: every code change ships with a version bump — no exceptions

You must **never change code without changing the version**, so every state is
committed, reversible and trackable. Concretely:

1. Before committing any change under `app/src/`, bump **both** `versionCode`
   (integer, monotonic) and `versionName` in `app/build.gradle`.
2. One logical change = one version = one commit = one push. Do not batch
   several changes under a single version, and never rebuild/reinstall the same
   version with different code — that destroys rollback.
3. Build the release APK and archive it as `_artifacts/salon-<versionName>.apk`
   so any version can be reinstalled as-is.
4. Commit and push in order. Each commit message states the version and the
   single change it carries.
5. Docs / hooks / non-`app/src` config commits do **not** need a version bump.

This is mechanically enforced by a **pre-commit hook** that blocks any commit
touching `app/src/**` unless `versionCode` changed vs HEAD. Do **not** bypass it
with `--no-verify` — that is the exact failure it exists to prevent.

### Installing the hook (once per clone)

```
sh .githooks/install.sh
```

It copies `.githooks/pre-commit` into `.git/hooks/pre-commit`. We copy rather
than set `core.hooksPath`, so the existing graphify `post-commit` hook keeps
working.

## Build / install facts

- Release is signed with the Minima family key (creds in `~/.gradle/gradle.properties`).
  The installed app on devices is release-signed — install release, not debug
  (`adb install -r` a debug build fails with a signature mismatch and would
  otherwise require an uninstall that wipes the on-chain identity).
- `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
- Companion app: Java + classic Views, no XML layouts (built in code). Talks to
  the node only via `minimaapi.aar` broadcast IPC (`NodeApi`).
