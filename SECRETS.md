# AliGram Secrets — Inventory (NAMES ONLY)

## Absolute rule — read first

**This file, the README, the patchset, and the repo contain NO secret
values, ever.** No passwords, no key material, no JSON file contents, no
Telegram API ID, no API hash, no private keys — not in full, not in part,
not encoded, not masked. The repository (`tedfoagen-stack/AliGram`) is
**public**, so anything committed there is visible to everyone.

This file lists secret **names**, what each one is, where it lives, how to
check it exists, and how to recreate or restore it if lost. The values
live only in the places named below.

- **GitHub secrets** are checked by **name only**: `gh secret list`
  returns secret names, never values. If a name is listed, the secret
  exists; its value can never be read back from GitHub.
- **Ali's own credentials** (Telegram API ID/hash, Firebase service
  account) are his. Never stored by us, never sent in chat, never baked
  into any build. Only Ali can see or recreate them, from his own
  accounts, as described below.

## GitHub secrets (repo: tedfoagen-stack/AliGram)

All five are already set on the repo. They are consumed only by CI
(`.github/workflows/aligram-build.yml` and `pr.yml`); `apply.sh` never
touches them, and the patchset does not need them. The workflow decodes
`RELEASE_KEYSTORE_BASE64` in CI, signs the debug APK, and verifies the
APK's fingerprint and `versionCode` 1261 in CI. No secret value is ever
printed in CI logs.

### RELEASE_KEYSTORE_BASE64

The AliGram release keystore, base64-encoded, so CI can decode it and
sign the debug APK with the stable AliGram release key. AliGram-only
(created for this project), RSA 4096, alias `aligram`, valid 2026-11-21
to 2054-04-08. Lives as a GitHub secret on `tedfoagen-stack/AliGram`
and as the backup file
`/home/hatch/workspace/aligram-signing/aligram-release.keystore`
(private workspace — **never** committed, never sent in chat). Check:
`gh secret list --repo tedfoagen-stack/AliGram` shows the name and the
backup file exists; either copy alone restores the other. **If lost:**
if the backup file **and** the secret are both lost, the key is
**unrecoverable** — the same private key cannot be regenerated. A new
keystore must be generated, uploaded as a new secret, and the CI
fingerprint check updated. Consequence: the next build cannot install
over existing installs — one uninstall/reinstall cycle for every user
(see "Keystore loss" below).

### KEYSTORE_PASS

The password for the AliGram release keystore file. Lives as a GitHub
secret on `tedfoagen-stack/AliGram` (used by CI when signing); it is not
written in any file in this workspace or repo. Check: `gh secret list
--repo tedfoagen-stack/AliGram` shows `KEYSTORE_PASS` (the value itself
cannot be read back). **If lost:** the keystore cannot be used without
its password — the file becomes unusable, with the **same consequence as
losing the keystore itself** (new key, new signature, one
uninstall/reinstall cycle). No password recovery exists for a keystore.

### ALIAS_NAME

The key alias inside the keystore. Its value is the alias `aligram`
(the alias name is not itself secret, but it is stored as a secret
alongside the other signing inputs so CI needs no hardcoded values).
Lives as a GitHub secret on `tedfoagen-stack/AliGram`. Check: `gh secret
list --repo tedfoagen-stack/AliGram` shows `ALIAS_NAME`. **If lost:**
recreate the secret with the alias `aligram`. No rebuild or reinstall is
needed — this only matters if the keystore itself is replaced, in which
case set it to the new key's alias.

### ALIAS_PASS

The password for the key alias inside the keystore. Lives as a GitHub
secret on `tedfoagen-stack/AliGram` (used by CI when signing). Check:
`gh secret list --repo tedfoagen-stack/AliGram` shows `ALIAS_PASS`
(the value itself cannot be read back). **If lost:** same as
`KEYSTORE_PASS` — the key cannot be used to sign without it, so a lost
alias password has the **same consequence as losing the keystore** (new
key, new signature, one uninstall/reinstall cycle). No recovery.

### GOOGLE_SERVICES_JSON

Ali's `google-services.json` for his Firebase project (`aligram-41bad`,
Android app `app.aligram`). It lets the app obtain an FCM token and
register it with Telegram. CI writes this file from the secret before
Gradle runs, overwriting the checked-in file. Lives as a GitHub secret
on `tedfoagen-stack/AliGram`; the `google-services.json` checked in to
the repo stays **upstream's file** — Ali's file is never committed.
Check: `gh secret list --repo tedfoagen-stack/AliGram` shows
`GOOGLE_SERVICES_JSON`. (A build failing at the secret step, or an app
that cannot obtain an FCM token, is the symptom of a missing/wrong
value.) **If lost:** re-download it from the Firebase console (project
`aligram-41bad` → Project settings → Your apps → `app.aligram` →
Download `google-services.json`) and set it again as the secret. No
reinstall is needed; the next CI build picks it up. This file is **not**
the service-account key (below) — do not confuse the two.

## Ali's own credentials (his — never stored by us)

These belong to Ali's own Telegram and Firebase accounts. Nobody on the
project stores them, and they must never be sent in chat, committed to
the repo, or placed in an APK.

### Telegram API ID and API hash

Ali's own Telegram app credentials, created by him on
[my.telegram.org](https://my.telegram.org) (app title AliGram). They
identify his app to Telegram's servers and are the fix for the
server-driven logouts seen with the public test credentials. They live
only on Ali's my.telegram.org app page, entered by Ali himself in the
app via Login screen ⋮ menu → `CustomApi` (custom → App ID + App Hash).
Never in chat, never in the build, never here. Check: Ali can see them
on his my.telegram.org app page at any time; nobody else can check them
— that is by design. **If lost:** read them again from the same
my.telegram.org app page. If the app itself was deleted there, create a
new app, enter the new ID/hash in the app's CustomApi setting, and point
the FCM service-account field (below) at the new app. After switching API
credentials, per-chat "preserve media" opt-ins must be re-enabled.

### Firebase service-account JSON (FCM service account)

The private key for the Firebase service account of project
`aligram-41bad`. Telegram's servers need it to send push notifications
to FCM tokens in Ali's Firebase project — without it, push delivery
(F2) can never work. This is a **different file** from
`google-services.json` and is far more powerful: a private key for the
Firebase project. It lives only on Ali's my.telegram.org app page, in
the "FCM service account" field, where Ali pasted/uploaded it; generated
in the Firebase console → project `aligram-41bad` → Settings →
Service accounts → Generate new private key. Check: Ali can see on
his my.telegram.org app page whether the field is filled; the key itself
cannot be read back from either place. **If lost:** generate a new
private key in the Firebase console (same Service accounts page) and
paste it into the FCM service account field on my.telegram.org, replacing
the old one. Then **revoke the old key** in the Firebase console
(Service accounts → the old key → delete/revoke), since a lost private
key that is still valid is a live credential. Never send the new key in
chat, and never commit it anywhere.

## Keystore loss — the honest consequences

Losing the release keystore is the one loss in this inventory that
cannot be undone:

- **Keystore file AND `RELEASE_KEYSTORE_BASE64` both lost → key
  unrecoverable.** New keystore, new secrets (`RELEASE_KEYSTORE_BASE64`,
  `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS`), CI fingerprint check updated.
- **`KEYSTORE_PASS` or `ALIAS_PASS` lost → keystore unusable.** Same
  consequence as losing the keystore; there is no password recovery.
- **Consequence in both cases:** the next build is signed with a
  different key, so Android refuses to install it over existing
  installs. Every user needs **one uninstall/reinstall cycle**; after
  that, later builds (same new key, `versionCode` increasing) update in
  place again. No data inside the app survives an uninstall, so warn Ali
  before shipping a re-signed build.
- **Everything else is recoverable:** `GOOGLE_SERVICES_JSON` can be
  re-downloaded from Firebase; API ID/hash can be re-read from
  my.telegram.org; a lost service-account key can be regenerated in
  Firebase (and the old one revoked). None of those require a reinstall.

## Quick check (names only)

```bash
gh secret list --repo tedfoagen-stack/AliGram
# Must show all five: RELEASE_KEYSTORE_BASE64, KEYSTORE_PASS,
# ALIAS_NAME, ALIAS_PASS, GOOGLE_SERVICES_JSON.
# Values are never displayed — a listed name is the whole check.

ls -l ~/workspace/aligram-signing/aligram-release.keystore
# Backup keystore file must exist. If it does not, re-create it from
# the RELEASE_KEYSTORE_BASE64 secret while the secret still exists:
# decode it to a file and verify the fingerprint matches the key CI
# signs with — do this BEFORE the secret is ever needed in an emergency.
```

## When adding a secret later

1. Add it as a GitHub secret (never a file in the repo).
2. Add a section to this file: what it is, where it lives, how to check
   it by name, and how to recreate it if lost.
3. Name only. No value, no fragment, no hint of the value.
