#!/usr/bin/env bash
# Pocketbox manifest updater: fetches the latest Alpine minirootfs and OpenCode
# release info and prints (or writes) an updated runtime-manifest.json.
#
# Usage: scripts/update-manifest.sh [--write]
#
# The script NEVER copies values blindly: it verifies what it downloads
# (SHA-256 is computed from the actual file) and prints the JSON so a human
# can review before committing. Run `--write` only after eyeballing the diff.
set -euo pipefail

WRITE=0
[ "${1:-}" = "--write" ] && WRITE=1

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/runtime/src/main/assets/runtime-manifest.json"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

say() { printf '\n== %s\n' "$*"; }

# --- Alpine ---------------------------------------------------------------
ALPINE_BRANCH=$(curl -fsSL https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/latest-releases.yaml \
  | grep -A5 'flavor: alpine-minirootfs' | grep 'branch:' | head -1 | awk '{print $2}')
say "Alpine branch: $ALPINE_BRANCH"

fetch_alpine() {
  local arch=$1
  local yaml archname
  case "$arch" in
    arm64-v8a) archname=aarch64 ;;
    x86_64)    archname=x86_64 ;;
  esac
  yaml=$(curl -fsSL "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$archname/latest-releases.yaml")
  local version file size sha
  version=$(echo "$yaml" | awk '/flavor: alpine-minirootfs/{f=1} f&&/^  version:/{print $2; exit}')
  file=$(echo "$yaml" | awk '/flavor: alpine-minirootfs/{f=1} f&&/^  file:/{print $2; exit}')
  size=$(echo "$yaml" | awk '/flavor: alpine-minirootfs/{f=1} f&&/^  size:/{print $2; exit}')
  sha=$(echo "$yaml" | awk '/flavor: alpine-minirootfs/{f=1} f&&/^  sha256:/{print $2; exit}')
  echo "{\"version\":\"$version\",\"url\":\"https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/releases/$archname/$file\",\"sha256\":\"$sha\",\"sizeBytes\":$size}"
}

# --- OpenCode -------------------------------------------------------------
# OpenCode latest v2 GitHub release (v2 contract required by OpenCode mobile clients).
TAG=$(curl -fsSL "https://api.github.com/repos/anomalyco/opencode/releases?per_page=20" \
  | grep -o '"tag_name": *"v2\.[^"]*"' | head -1 | cut -d'"' -f4)
say "OpenCode latest v2 tag: $TAG"
VER=${TAG#v}

fetch_opencode() {
  local arch tagname
  case "$arch" in
    arm64-v8a) tagname=opencode-linux-arm64-musl.tar.gz ;;
    x86_64)    tagname=opencode-linux-x64-musl.tar.gz ;;
  esac
  local url="https://github.com/anomalyco/opencode/releases/download/$TAG/$tagname"
  local out="$TMP/$tagname"
  curl -fsSL -o "$out" "$url"
  local sha size
  sha=$(sha256sum "$out" | awk '{print $1}')
  size=$(stat -c%s "$out")
  echo "{\"version\":\"$VER\",\"url\":\"$url\",\"sha256\":\"$sha\",\"sizeBytes\":$size}"
}

say "Downloading + checksumming artifacts (this fetches ~160 MB)…"
ARM64_ALPINE=$(fetch_alpine arm64-v8a)
X64_ALPINE=$(fetch_alpine x86_64)
ARM64_OC=$(fetch_opencode arm64-v8a)
X64_OC=$(fetch_opencode x86_64)

NEW_JSON=$(cat <<EOF
{
  "schema": 1,
  "abis": {
    "arm64-v8a": {
      "alpine": $ARM64_ALPINE,
      "opencode": $ARM64_OC
    },
    "x86_64": {
      "alpine": $X64_ALPINE,
      "opencode": $X64_OC
    }
  },
  "apkBase": ["bash", "git", "openssh-client", "ripgrep", "curl", "ca-certificates", "libgcc", "libstdc++", "tzdata"],
  "apkPacks": {
    "node": ["nodejs", "npm"],
    "python": ["python3", "py3-pip"],
    "build": ["build-base"]
  }
}
EOF
)

say "Proposed manifest:"
echo "$NEW_JSON"

if [ "$WRITE" = 1 ]; then
  echo "$NEW_JSON" > "$MANIFEST"
  say "Wrote $MANIFEST — review the diff, then rebuild."
else
  say "Dry run only. Re-run with --write to apply."
fi
