#!/usr/bin/env bash
#
# Fetches the native SOURCE needed by the build. Nothing here is prebuilt:
#   1. hev-socks5-tunnel  -> built with its own Android.mk into libhev-socks5-tunnel.so
#                            (the in-app "tun2socks" core)
#   2. SoildTunnel engine src  -> cross-compiled into libsoildtunnel-core.so
#
# Both are compiled later by scripts/build-natives.sh.
#
# Safe to re-run. All network access happens here / in CI, never on device.
# By default we clone hev's DEFAULT branch. To pin, export HEV_REF; a missing
# ref falls back to the default branch instead of failing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_DIR="${PROJECT_DIR}/.native"
mkdir -p "${NATIVE_DIR}"

HEV_REPO="heiher/hev-socks5-tunnel"
HEV_REF="${HEV_REF:-}"            # empty => default branch
HEV_DIR="${NATIVE_DIR}/hev-socks5-tunnel"

SOILDTUNNEL_SRC="${NATIVE_DIR}/soildtunnel"

# The engine source is VENDORED inside this repo at native/engine so the app's
# own modifications are ALWAYS compiled into libsoildtunnel-core.so with zero
# manual steps and zero network access for the engine.
VENDORED_SOILDTUNNEL="${PROJECT_DIR}/native/engine"

# clone_repo <url> <dir> <ref>
# Tries the pinned ref first (tag or branch); on any failure cleanly falls back
# to the repo's default branch. Always clones submodules recursively.
clone_repo() {
  local url="$1" dir="$2" ref="$3"
  rm -rf "${dir}"
  if [ -n "${ref}" ] && \
     git clone --depth 1 --branch "${ref}" --recursive "${url}" "${dir}" 2>/dev/null; then
    echo "   cloned ${url} @ ${ref}"
    return 0
  fi
  if [ -n "${ref}" ]; then
    echo "   ref '${ref}' not found on ${url}; using default branch"
  fi
  rm -rf "${dir}"
  git clone --depth 1 --recursive "${url}" "${dir}"
  echo "   cloned ${url} @ default branch"
}

echo "==> Fetching hev-socks5-tunnel (tunnel core)"
clone_repo "https://github.com/${HEV_REPO}.git" "${HEV_DIR}" "${HEV_REF}"
if [ ! -f "${HEV_DIR}/Makefile" ]; then
  echo "ERROR: hev-socks5-tunnel checkout has no Makefile at ${HEV_DIR}" >&2
  ls -la "${HEV_DIR}" >&2 || true
  exit 1
fi

echo "==> Providing SoildTunnel engine source"
if ! find "${VENDORED_SOILDTUNNEL}/soildtunnel" -name Cargo.toml -not -path '*/target/*' 2>/dev/null | grep -q .; then
  echo "ERROR: the vendored engine at ${VENDORED_SOILDTUNNEL} has no Cargo.toml." >&2
  exit 1
fi
echo "   using the VENDORED engine bundled in this repo: ${VENDORED_SOILDTUNNEL}"
rm -rf "${SOILDTUNNEL_SRC}"
mkdir -p "${SOILDTUNNEL_SRC}"
# Copy everything except any local build output (target/).
( cd "${VENDORED_SOILDTUNNEL}" && tar --exclude='./target' --exclude='*/target' -cf - . ) \
  | ( cd "${SOILDTUNNEL_SRC}" && tar -xf - )

echo "   found SoildTunnel Cargo manifest(s):"
find "${SOILDTUNNEL_SRC}" -maxdepth 2 -name Cargo.toml -not -path '*/target/*' | sed 's/^/     /'

echo "==> Native sources ready under ${NATIVE_DIR}"
