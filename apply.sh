#!/usr/bin/env bash
#
# apply.sh — apply the AliGram patchset onto a fresh NagramX base.
#
# Usage:
#   ./apply.sh [--base TAG] [--repo URL] [--dir DIR] [--no-verify]
#
# Modes:
#   1. In-place (default): run inside an existing clean checkout of the
#      base tag (e.g. `git checkout 1260`). The patches are applied there
#      with `git am` and the result is verified against the expected tree.
#   2. Clone mode: pass --repo <url> --dir <dir> to clone the base tag
#      fresh from <url> into <dir> first, then apply there.
#
# Defaults:
#   BASE = 1260 (tag 1260 = upstream NagramX 12.9.2, our baseline)
#   REPO = https://github.com/tedfoagen-stack/AliGram.git
#          (its main branch IS tag 1260; for a future upstream base, pass
#           --repo https://github.com/risin42/NagramX.git --base <new tag>)
#
# On any conflict `git am` stops and this script exits non-zero with a
# clear report: which patch failed and which files conflict. It never
# auto-resolves. See README.md § "When a patch conflicts" for the fix.
#
# The script never touches secrets: no keystore, no passwords, no JSON
# contents, no API ID/hash. Those are documented in SECRETS.md and live
# outside this template (GitHub secrets / Ali's own accounts).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH_DIR="$SCRIPT_DIR/patches"

BASE="1260"
REPO="https://github.com/tedfoagen-stack/AliGram.git"
DIR=""
VERIFY=1

# Tree of the feature-branch head b4358ec60 — the exact result the
# patchset must reproduce on the 1260 base. Update this (and BASE)
# whenever the patchset is regenerated for a new head.
EXPECTED_TREE="6eced977bd7022299cdbe52fe06a64c94ac8197a"
EXPECTED_HEAD="b4358ec60e2125b13b030d056f1d4039ca411b1e"

while [ $# -gt 0 ]; do
    case "$1" in
        --base) BASE="$2"; shift 2 ;;
        --repo) REPO="$2"; shift 2 ;;
        --dir) DIR="$2"; shift 2 ;;
        --no-verify) VERIFY=0; shift ;;
        -h|--help)
            sed -n '2,30p' "$0"
            exit 0
            ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

[ -d "$PATCH_DIR" ] || { echo "ERROR: patches dir not found: $PATCH_DIR" >&2; exit 2; }
PATCHES=( "$PATCH_DIR"/*.patch )
[ "${#PATCHES[@]}" -gt 0 ] || { echo "ERROR: no .patch files in $PATCH_DIR" >&2; exit 2; }
echo "patchset: ${#PATCHES[@]} patches (base tag $BASE, expected tree $EXPECTED_TREE)"

if [ -n "$DIR" ]; then
    if [ -e "$DIR" ]; then
        echo "ERROR: --dir target already exists: $DIR" >&2
        exit 2
    fi
    echo "cloning $REPO (tag $BASE) into $DIR ..."
    # --depth 1: only the base commit is needed to apply the patchset.
    # (A full clone of this repo is >1 GB; /tmp on this machine is a
    # 512 MB tmpfs, so point --dir at a roomy filesystem.)
    git clone --depth 1 --branch "$BASE" --single-branch --no-checkout "$REPO" "$DIR"
    cd "$DIR"
    git checkout "$BASE"
else
    git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
        || { echo "ERROR: not inside a git checkout (use --repo/--dir, or run inside the base checkout)" >&2; exit 2; }
fi

# The worktree must be clean and checked out at the base.
if [ -n "$(git status --porcelain)" ]; then
    echo "ERROR: worktree is not clean — commit or stash your changes first." >&2
    exit 2
fi
CURRENT_TREE="$(git rev-parse 'HEAD^{tree}')"
BASE_TREE="$(git rev-parse "$BASE^{tree}" 2>/dev/null || echo unknown)"
if [ "$CURRENT_TREE" != "$BASE_TREE" ]; then
    echo "WARNING: HEAD tree ($CURRENT_TREE) is not the base tag tree ($BASE_TREE)." >&2
    echo "         Expected a clean checkout of tag $BASE. Continuing anyway." >&2
fi

# Apply one patch at a time, with retries. Sequential application in
# this environment hits TRANSIENT "does not match index" failures at a
# moving target (observed at patches 0006, 0009, 0018, 0019, 0031 across
# runs): the failing patch's pre-image blobs provably match the index,
# `git apply --index --check` passes, and an immediate retry of the same
# patch in the same state succeeds with the exact expected tree. A batch
# `git am` over the whole series fails the same way, and plain
# `git apply --index` + commit does too — so the flakiness is in
# sequential application itself, not in any one patch or in `git am`.
# Each patch is therefore retried (up to 3 attempts, with cleanup +
# a pause between attempts). A patch that fails 3 times is treated as a
# REAL conflict and stops the run with the report below.
# No -3: the three-way fallback cannot work in a shallow clone (it needs
# the original commit objects, which a --depth 1 clone does not have), so
# on a drifted base a failed patch is resolved by hand instead — see
# README.md § "When a patch conflicts".
echo "applying ${#PATCHES[@]} patches, one git am per patch (max 3 attempts each) ..."
applied=0
for patch in "${PATCHES[@]}"; do
    applied=$((applied + 1))
    subject="$(grep -m1 '^Subject:' "$patch" | sed 's/^Subject: //')"
    echo "[$applied/${#PATCHES[@]}] $subject"
    attempt=1
    while true; do
        if git -c user.name="AliGram" -c user.email="aligram@local" am "$patch"; then
            break
        fi
        if [ "$attempt" -ge 3 ]; then
            echo "" >&2
            echo "APPLY FAILED at patch $applied/${#PATCHES[@]} after 3 attempts: $patch" >&2
            echo "Conflicting files:" >&2
            git diff --name-only --diff-filter=U || true
            git status --porcelain | head -30 >&2
            echo "" >&2
            echo "To fix: resolve the conflicts, then 'git am --continue'." >&2
            echo "To abort: 'git am --abort' (returns to the clean base)." >&2
            echo "See README.md § 'When a patch conflicts'." >&2
            exit 1
        fi
        echo "attempt $attempt failed (transient?); cleaning up and retrying ..." >&2
        rm -rf .git/rebase-apply
        git reset --hard HEAD --quiet
        sleep 2
        attempt=$((attempt + 1))
    done
done

echo "all ${#PATCHES[@]} patches applied cleanly."
echo "result head: $(git rev-parse HEAD)"
echo "result tree: $(git rev-parse 'HEAD^{tree}')"

if [ "$VERIFY" -eq 1 ]; then
    RESULT_TREE="$(git rev-parse 'HEAD^{tree}')"
    if [ "$RESULT_TREE" = "$EXPECTED_TREE" ]; then
        echo "VERIFY OK: result tree matches expected tree $EXPECTED_TREE (head $EXPECTED_HEAD)."
    else
        echo "VERIFY FAILED: result tree $RESULT_TREE != expected $EXPECTED_TREE." >&2
        echo "Run: git diff $EXPECTED_HEAD --stat   (in a checkout that has the head)" >&2
        exit 1
    fi
    if [ -n "$(git status --porcelain)" ]; then
        echo "VERIFY FAILED: worktree not clean after apply." >&2
        exit 1
    fi
    echo "worktree clean. Patchset verified."
fi
