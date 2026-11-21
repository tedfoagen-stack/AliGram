# AliGram Patchset Template

A complete, replayable export of every AliGram change made on top of
upstream NagramX, so the whole project can be re-applied onto a fresh base
— a new upstream version, a clean checkout, or a fresh machine — without
re-doing any of the work by hand.

## What AliGram is

AliGram is a modified Telegram Android client based on
[risin42/NagramX](https://github.com/risin42/NagramX) (GPL-3.0). Its sole
enhancement is **per-chat selective auto-download and durable preservation
of media from deleted and edited messages in selected groups and channels**,
plus a hidden-folder feature. The project repo is
<https://github.com/tedfoagen-stack/AliGram>; work happens on branch
`feature/aligram-media-preservation`; PR #1 is open and **never merged**.

- **Baseline:** tag `1260` = commit
  `4335a2e589aac4a82f8fceb21b3102c5559db2bf` (upstream NagramX 12.9.2,
  `versionCode` 1260).
- **Head reproduced by this patchset:** `b4358ec60e2125b13b030d056f1d4039ca411b1e`,
  whose tree SHA is `6eced977bd7022299cdbe52fe06a64c94ac8197a`.
  `apply.sh` verifies the result tree equals that SHA.
- **Licence:** AliGram is derived from NagramX, which is GPL-3.0. Any
  redistribution of a build made from this patchset must comply with the
  GPL-3.0, including making the corresponding source available.

## A note on upstream

Upstream NagramX is **archived** — its last push was 2026-08-23 — so a new
official version is unlikely to appear. This template is still worth
keeping: it is the complete backup of the project and the fast rebase path
if a new base ever does appear (a forked upstream, a security fix, or a
move to a different NagramX tag).

## Contents

```
patchset/
├── README.md        # this file
├── SECRETS.md       # secret inventory — NAMES ONLY, no values, ever
├── apply.sh         # applier + verifier (see below)
├── series.txt       # ordered list of the 67 patches, one line each
└── patches/
    ├── 0001-…patch  \
    │   …            } 67 patch files, 0001–0067, in application order
    └── 0067-…patch /
```

`series.txt` is the authoritative order. Each line is
`<patch file> | <one-line description>`. The patches are plain
`git format-patch` output and can also be applied by hand with `git am`.

### Patch groups

| Patches | Content |
|---|---|
| 0001–0041 | Original logical commits: Phase 3 core preservation (Room DB, hooks, UI, branding, JVM tests); Phase 3.1 fixes (D-01…D-14, R-7); Phase 3.2 (O-01…O-04, `claimJobs` atomicity); FCM via CI secret (0039); adjustable preservation limits (0040); `pr.yml` secret step (0041). |
| 0042–0047 | Preserve Media dominance + retry-until-success: Archive gate removed, megagroup post rule fixed, legacy Message Saving toggles overridden for opted-in dialogs, retry with backoff, startup sweep for missed messages, PENDING-window fix, rejection logging. |
| 0048–0050 | Release signing: `versionCode` 1260 → 1261; CI signs the debug APK with the AliGram release key from GitHub secrets and verifies fingerprint + versionCode in CI. |
| 0051–0067 | Hidden folders: salted code hash, `##` setup, search-box code intercept, lock/unlock, 1-hour auto-lock, re-lock on background, filtering of hidden dialogs from dialog list, search, notifications, badges, share sheet, stories and preserved-media stats, plus two compile fixes (0066, 0067). |

### The 67-vs-27 deviation — read this first

The original brief for this template said "27 commits". That number is
wrong, and this patchset deliberately does **not** reproduce it. The local
branch history is shallow: only 27 commits are reachable from the branch
head (1 squashed commit + 26 logical commits), because earlier publish work
reset the local branch to the squashed remote mirrors. The complete
41-commit early logical history survives in the local clone
(`/home/hatch/workspace/nagramx/repo-git`) as the chain
`4335a2e5..1a7fc6a59`, so this patchset exports the **full 67 logical
commits (41 + 26)**, not a rollup. Nothing is hidden by the squashing:
patch 0041's commit is `1a7fc6a59`, and the squashed mirror commit
`93509ca1e` has the **identical** tree
`b2957ea4f023f56b6eab4bf6945a311916bb80cb`, so patches 0042+ (diffed
against the squashed chain) apply exactly onto patch 0041's result.
If you ever see "27 commits" quoted elsewhere for this project, it refers
to the shallow reachable history, not to the work actually done.

## Prerequisites

- `git` (any recent version; `git am -3` is used for three-way applies).
- A clean checkout of the base tag — no local modifications, no staged
  changes. `apply.sh` refuses to run on a dirty tree.
- For a **local build** after applying: JDK 21 and the Android SDK.
  (The CI build needs neither locally; see "Verification" below.)
- **No secrets are needed to apply the patchset.** `apply.sh` never
  touches secrets. Secrets are only needed to build a signed APK in CI
  or to configure Ali's own Telegram/Firebase accounts — see
  `SECRETS.md`.

## Applying the patchset

### Clone mode (recommended for a fresh start)

```bash
./apply.sh --repo https://github.com/tedfoagen-stack/AliGram.git \
           --dir /tmp/aligram-fresh
```

This clones the base tag fresh into `--dir` and applies all 67 patches
there. Defaults (no flags needed for the current baseline):

- `--base 1260`
- `--repo https://github.com/tedfoagen-stack/AliGram.git`
  — its `main` **is** tag 1260, so this repo is the correct default base.

### In-place mode

```bash
git clone --branch 1260 https://github.com/tedfoagen-stack/AliGram.git aligram
cd aligram
/path/to/patchset/apply.sh
```

Run inside a clean checkout of the base tag. `apply.sh` applies each
patch with its own `git am` invocation (one patch at a time, up to 3
attempts each — sequential application in this environment hits transient
"does not match index" failures at a moving target; a failed patch's
pre-image provably matches the index and an immediate retry succeeds, so
the script retries before treating a failure as a real conflict), then
verifies the result: the resulting tree must equal
`6eced977bd7022299cdbe52fe06a64c94ac8197a` and the worktree must be clean.
`--no-verify` skips the tree check (only useful while debugging a
conflict; never use it for a final result).

## Updating to a new upstream version

This is the procedure the template exists for. Work on a throwaway
verification branch first; push to the real branch only after CI is green.

1. **Get the new base.** Note the new upstream tag, e.g. `13.0.0`:
   ```bash
   git clone --branch <new-tag> https://github.com/risin42/NagramX.git aligram-new
   cd aligram-new
   git checkout -b verify/rebase-<new-tag>
   ```
   (For the current 1260 base the AliGram repo itself is used, because
   its `main` is tag 1260; for a future upstream base, pass
   `--repo https://github.com/risin42/NagramX.git --base <new-tag>`.)
2. **Apply the patchset.**
   ```bash
   /path/to/patchset/apply.sh --repo https://github.com/risin42/NagramX.git \
                              --base <new-tag>
   ```
   Or in-place from inside the checkout (see above).
3. **Resolve any conflicts** (see next section). Expect some: upstream
   will have touched the same files. This is normal and is the whole
   point of keeping the changes as separate logical patches.
4. **Verify the tree.** A clean apply on the *old* base must reproduce
   tree `6eced977…` exactly. On a *new* base the tree will differ — that
   is expected. Verify instead that `git status` is clean (no leftover
   `.rej` / conflict markers), `git log --oneline` shows the 67 patches
   on top of the new base, and the diff of the result against the new
   base contains the AliGram changes and nothing else
   (`git diff <new-tag> --stat`, reviewed by eye).
5. **CI build.** Push the verification branch to
   `tedfoagen-stack/AliGram` and let the `aligram-build.yml` workflow run
   (Temurin JDK 21, `assembleDebug`; needs the `GOOGLE_SERVICES_JSON`
   secret — already set on the repo). `pr.yml` also runs on pull
   requests. Do not merge or publish anything until CI is green.
   A local build needs JDK 21 + Android SDK; see the workflow file for
   the exact steps CI runs.
6. **Publish the new build.** Once CI is green: open/update the PR
   against `feature/aligram-media-preservation` (PR #1 is the standing
   one; it is never merged — the branch is the delivery vehicle), take
   the `AliGram-Debug` APK artifact from the green run, and hand it to
   Ali with the one-time-install note if the signature changed (it
   should not — the release key is stable; see `SECRETS.md`).
7. **Regenerate / refresh the template.** If any patch had to be edited
   during the rebase, the template is now stale:
   - Fix the patch file(s) in `patches/` (see "When a patch conflicts"),
     update `series.txt` if a description changed, and update
     `EXPECTED_TREE` in `apply.sh` to the new head's tree SHA.
   - Re-run `apply.sh` from a **clean** checkout of the new base to
     re-verify the whole series end-to-end.
   - Record the new base tag, new head commit and new expected tree at
     the top of this README, and commit the refreshed template.

## When a patch conflicts

Upstream changing the same lines is expected on a rebase. `git am`
stops at the first conflicting patch and `apply.sh` exits non-zero with
a clear report: **which patch failed and which files conflict**. It never
auto-resolves anything.

```bash
# 1. See what failed
git am --show-current-patch        # the patch that stopped
git status                         # conflicting files (marked "both modified")

# 2. Resolve each conflict by hand: open the file, keep the AliGram
#    change, adapt it to the new upstream code, remove all markers.
#    Then mark resolved:
git add <resolved-file>...

# 3a. Continue with the rest of the series:
git am --continue

# 3b. Or give up on this attempt entirely (nothing is half-applied):
git am --abort
```

**If a patch needed editing, update the patch file too.** A fix that
lives only in your working tree is lost the next time the template is
applied. After the full series applies and CI is green:

1. Regenerate the corrected patch from your rebased branch, e.g.
   `git format-patch <new-base> --output-directory patches/` (or edit
   the single patch file by hand if the change is small), keeping the
   `0001`–`0067` numbering and the `series.txt` descriptions in sync.
2. Re-run `apply.sh` from a **clean** checkout of the new base and
   confirm the whole series applies with no intervention.
3. Update `EXPECTED_TREE` in `apply.sh` to the new head's tree SHA
   (`git rev-parse HEAD^{tree}`) and record the new base/head/tree in
   this README (see step 7 above).

Never commit a result that still contains conflict markers, `.rej`
files, or a half-applied `git am` state.

## Verification

Two independent checks, both required before a rebased build is trusted:

1. **Local tree compare (exact, on the 1260 base).** `apply.sh`
   does this automatically: after applying all 67 patches it compares
   `git rev-parse HEAD^{tree}` with the expected tree
   `6eced977bd7022299cdbe52fe06a64c94ac8197a` and requires a clean
   worktree. Any mismatch means the patchset does not reproduce the
   known-good head — stop and investigate; do not ship it.
   On a new base the expected tree cannot match (see step 4 of the
   update procedure); there, the checks are a clean status, 67 patches
   on top of the new base, and a reviewed `git diff <new-tag>`.
2. **CI build on a verification branch.** Push a branch (never the
   feature branch directly until green) and confirm both workflows pass:
   - `.github/workflows/aligram-build.yml` — Temurin JDK 21,
     `assembleDebug`; triggers on push to
     `feature/aligram-media-preservation` and on `workflow_dispatch`;
     needs the `GOOGLE_SERVICES_JSON` secret.
   - `.github/workflows/pr.yml` — triggers on `pull_request`; also has
     the secret step.
   The green run's `AliGram-Debug` APK artifact is the deliverable.
   A local pure-JVM unit-test run (`./gradlew testDebugUnitTest`) needs
   the Android SDK and JDK 21; it cannot substitute for the CI build.

## What is NOT in this template

- **No secrets.** No keystore, no passwords, no JSON contents, no API
  ID/hash — anywhere. See `SECRETS.md` for the inventory (names only)
  and how each item is checked, restored, or recreated.
- **No build outputs.** No APKs, no generated sources. Build them from
  the patched tree via CI (or locally with JDK 21 + Android SDK).
- **No upstream history.** The patchset starts at tag 1260. The
  baseline commit is `4335a2e589aac4a82f8fceb21b3102c5559db2bf`.
