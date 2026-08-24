#!/usr/bin/env bash
#
# Builds the native cores and installs them into app/src/main/jniLibs/<abi>/:
#
#   libhev-socks5-tunnel.so <- hev's core, built WITHOUT its bundled JNI layer
#                    (hev-jni.c is stripped from the build — see build_hev).
#                    Only its stable public C API is used.
#   libsoildtunnel-jni.so <- OUR OWN JNI bridge (scripts/soildtunneltun-jni.c).
#                    The app loads THIS library; it binds hev's C API and exports
#                    the Java_* symbols TProxyService.kt declares. Runs the tunnel
#                    IN-PROCESS (the VpnService TUN fd is per-process) on a
#                    native pthread the bridge creates itself.
#   libsoildtunnel-core.so <- the SoildTunnel engine, cross-compiled from Rust
#                    with cargo-ndk.
#
# Usage:  build-natives.sh [hev|soildtunnel|all]   (default: all)
#
# Requires: ANDROID_NDK_HOME, rustup android targets, cargo-ndk.
# Run scripts/fetch-natives.sh first.
set -euo pipefail

TARGET="${1:-all}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NATIVE_DIR="${PROJECT_DIR}/.native"
HEV_DIR="${NATIVE_DIR}/hev-socks5-tunnel"
SOILDTUNNEL_SRC="${NATIVE_DIR}/soildtunnel"
JNI_DIR="${PROJECT_DIR}/app/src/main/jniLibs"

API="${ANDROID_API:-26}"
ABIS=("arm64-v8a" "armeabi-v7a")

if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "${ANDROID_NDK_HOME}" ]; then
  echo "ERROR: ANDROID_NDK_HOME is not set or does not exist." >&2
  exit 1
fi

# Locate the NDK LLVM toolchain (host tag differs per runner OS).
NDK_TOOLCHAIN=""
for host in linux-x86_64 darwin-x86_64 windows-x86_64; do
  if [ -d "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host}/bin" ]; then
    NDK_TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${host}/bin"
    break
  fi
done
if [ -z "${NDK_TOOLCHAIN}" ]; then
  echo "ERROR: could not find the NDK LLVM toolchain under ${ANDROID_NDK_HOME}" >&2
  exit 1
fi
echo "==> NDK toolchain: ${NDK_TOOLCHAIN}"

clang_for_abi() {
  case "$1" in
    arm64-v8a)   echo "${NDK_TOOLCHAIN}/aarch64-linux-android${API}-clang" ;;
    armeabi-v7a) echo "${NDK_TOOLCHAIN}/armv7a-linux-androideabi${API}-clang" ;;
    *) echo "" ;;
  esac
}

# ---------------------------------------------------------------------------
# 1) hev-socks5-tunnel  (Android build via ndk-build)
# ---------------------------------------------------------------------------
# The plain Makefile cross-compiles lwip's UNIX port, whose fd_set typedef
# collides with Android bionic. hev ships an Android build (Android.mk +
# Application.mk, at the REPO ROOT) that configures lwip correctly. We then make
# sure we end up with a runnable executable to spawn, whatever the Android build
# emits (executable / shared lib / static lib), via a tiny wrapper around hev's
# public entry point:  int hev_socks5_tunnel_main(const char *config, int fd);
build_hev() {
  local mk_dir=""
  if [ -f "${HEV_DIR}/Android.mk" ]; then
    mk_dir="${HEV_DIR}"
  elif [ -f "${HEV_DIR}/jni/Android.mk" ]; then
    mk_dir="${HEV_DIR}/jni"
  else
    echo "ERROR: Android.mk not found in ${HEV_DIR} or ${HEV_DIR}/jni." >&2
    ls -la "${HEV_DIR}" >&2 || true
    exit 1
  fi
  local app_mk="${mk_dir}/Application.mk"
  [ -f "${app_mk}" ] || app_mk=""

  local ndkbuild="${ANDROID_NDK_HOME}/ndk-build"
  if [ ! -x "${ndkbuild}" ]; then
    echo "ERROR: ndk-build not found at ${ndkbuild}" >&2
    exit 1
  fi

  # ---- Strip hev's bundled JNI layer (hev-jni.c) OUT of the build. --------
  # FIELD-PROVEN root cause (round 2): even when the app loads OUR bridge
  # (libsoildtunnel-jni.so), ART locates JNI_OnLoad via dlsym() on the loaded
  # library's handle — and dlsym() searches the library AND its DT_NEEDED
  # dependencies. hev's JNI_OnLoad inside libhev-socks5-tunnel.so was found
  # and executed anyway, and its RegisterNatives failed on upstream's drifted
  # 'TProxyStopService()Z' signature, killing System.loadLibrary(). So hev's
  # JNI layer must not exist in the shipped .so AT ALL (the bridge also
  # defines its own JNI_OnLoad as a second line of defense).
  local mkfile
  while IFS= read -r -d '' mkfile; do
    sed -i.soildtunnelbak 's|[^[:space:]]*hev-jni\.c||g' "${mkfile}"
    rm -f "${mkfile}.soildtunnelbak"
  done < <(find "${HEV_DIR}" -name '*.mk' -print0)
  find "${HEV_DIR}" -name 'hev-jni.c' -delete

  echo "==> [hev] ndk-build (${mk_dir}/Android.mk) for ${ABIS[*]} (API ${API})"
  ( cd "${HEV_DIR}" && "${ndkbuild}" \
      NDK_PROJECT_PATH="${HEV_DIR}" \
      APP_BUILD_SCRIPT="${mk_dir}/Android.mk" \
      ${app_mk:+NDK_APPLICATION_MK="${app_mk}"} \
      APP_ABI="${ABIS[*]}" \
      APP_PLATFORM="android-${API}" \
      APP_STL="c++_static" \
      "APP_CFLAGS=-O3" \
      -j"$(nproc 2>/dev/null || echo 2)" )

  # hev must run IN-PROCESS (the VpnService TUN fd is per-process), and it
  # must run on a NATIVE thread. We ship hev's core plus OUR OWN JNI bridge
  # (libsoildtunnel-jni.so); the bridge runs the tunnel event loop on a
  # detached pthread it creates itself.
  #
  # ROOT-CAUSE NOTE: the previous custom wrapper (libhev.so + hev_jni.c)
  # called hev_socks5_tunnel_main directly on a Java (ART-attached) thread.
  # hev-task-system implements coroutines by swapping the thread's stack
  # pointer; doing that on a thread ART manages corrupts what the runtime
  # expects of the stack and kills the whole app with a native SIGSEGV a few
  # seconds after real traffic starts — with nothing in the Java crash log.
  # Running the loop on hev's own pthread (the proven production mode) avoids ART
  # entirely.
  local abi libsdir out dynsyms needed dep
  for abi in "${ABIS[@]}"; do
    libsdir="${HEV_DIR}/libs/${abi}"
    out="${JNI_DIR}/${abi}/libhev-socks5-tunnel.so"
    mkdir -p "${JNI_DIR}/${abi}"
    if [ ! -f "${libsdir}/libhev-socks5-tunnel.so" ]; then
      echo "ERROR: [${abi}] ndk-build did not produce libhev-socks5-tunnel.so" >&2
      ls -la "${libsdir}" 2>/dev/null >&2 || true
      exit 1
    fi
    cp "${libsdir}/libhev-socks5-tunnel.so" "${out}"
    # Never ship stale artifacts from the old wrapper approach.
    rm -f "${JNI_DIR}/${abi}/libhev.so" "${JNI_DIR}/${abi}/libhevcore.so"

    # ---- Hard verification: hev must export its STABLE public C API. ----
    # We no longer use hev's bundled JNI layer (hev-jni.c) at all — see the
    # root-cause note below. Only the C entry points matter, and those have
    # been stable across hev releases for years.
    dynsyms="$("${NDK_TOOLCHAIN}/llvm-nm" --dynamic --defined-only "${out}" 2>/dev/null || true)"
    for sym in hev_socks5_tunnel_main hev_socks5_tunnel_quit hev_socks5_tunnel_stats; do
      if ! echo "${dynsyms}" | grep -qw "${sym}"; then
        echo "ERROR: [${abi}] libhev-socks5-tunnel.so lacks ${sym}." >&2
        exit 1
      fi
    done
    # hev-jni.c was stripped above; if JNI_OnLoad is STILL exported, the strip
    # failed (upstream moved/renamed the file) and its RegisterNatives would
    # run again via the dlsym() dependency search. Never ship that.
    if echo "${dynsyms}" | grep -qw 'JNI_OnLoad'; then
      echo "ERROR: [${abi}] libhev-socks5-tunnel.so still exports JNI_OnLoad — hev-jni.c was not stripped." >&2
      echo "       Update the strip logic in build_hev for the new upstream layout." >&2
      exit 1
    fi

    # ---- Build OUR OWN JNI bridge: libsoildtunnel-jni.so -----------------
    # ROOT CAUSE of "VPN mode never connects while proxy mode works": the app
    # used to load libhev-socks5-tunnel.so directly and rely on hev's bundled
    # hev-jni.c to register the TProxy* natives. Upstream hev CHANGED that
    # JNI ABI (TProxyStartService '(Ljava/lang/String;I)V' -> ')Z'); since we
    # build hev's default branch, RegisterNatives inside its JNI_OnLoad began
    # failing with NoSuchMethodError, System.loadLibrary() threw, and VPN mode
    # died with "hev native library unavailable" (proxy mode never loads hev).
    #
    # PERMANENT FIX: ship our own tiny JNI bridge (scripts/soildtunneltun-jni.c)
    # linked against hev's stable public C API. -Wl,--no-undefined resolves
    # those symbols at BUILD time, so any upstream break fails CI loudly
    # instead of shipping a broken APK. hev's JNI layer is stripped from the
    # build above AND the bridge defines its own JNI_OnLoad (ART finds
    # JNI_OnLoad via dlsym(), which also searches DT_NEEDED dependencies —
    # exactly how hev's one got executed in the field), so upstream JNI ABI
    # drift is harmless now.
    # The tunnel loop still runs on a NATIVE pthread created in the bridge
    # (see the ART SIGSEGV note above).
    clang="$(clang_for_abi "${abi}")"
    if [ -z "${clang}" ] || [ ! -x "${clang}" ]; then
      echo "ERROR: [${abi}] NDK clang not found for this ABI." >&2
      exit 1
    fi
    bridge_src="${SCRIPT_DIR}/soildtunneltun-jni.c"
    bridge_out="${JNI_DIR}/${abi}/libsoildtunnel-jni.so"
    if [ ! -f "${bridge_src}" ]; then
      echo "ERROR: bridge source not found: ${bridge_src}" >&2
      exit 1
    fi
    echo "==> [hev] building libsoildtunnel-jni.so (our own JNI bridge) for ${abi}"
    "${clang}" -O2 -fPIC -shared -Wall -Werror \
      -Wl,-soname,libsoildtunnel-jni.so -Wl,--no-undefined \
      -o "${bridge_out}" "${bridge_src}" \
      -L"${JNI_DIR}/${abi}" -lhev-socks5-tunnel -llog
    bridgesyms="$("${NDK_TOOLCHAIN}/llvm-nm" --dynamic --defined-only "${bridge_out}" 2>/dev/null || true)"
    for sym in \
      JNI_OnLoad \
      Java_com_soildtunnel_app_core_TProxyService_TProxyStartService \
      Java_com_soildtunnel_app_core_TProxyService_TProxyStopService \
      Java_com_soildtunnel_app_core_TProxyService_TProxyGetStats; do
      if ! echo "${bridgesyms}" | grep -qw "${sym}"; then
        echo "ERROR: [${abi}] libsoildtunnel-jni.so lacks ${sym} — Kotlin externals would not resolve." >&2
        exit 1
      fi
    done
    echo "    [${abi}] libsoildtunnel-jni.so verified: all Java_* bridge symbols present"

    # A previous APK contained the main hev library but not one of its runtime
    # dependencies (most commonly libc++_shared.so). Android then refused to
    # load hev, so proxy mode worked while full-device VPN mode failed. We build
    # with the static C++ runtime above and also enforce that every remaining
    # DT_NEEDED entry is either an Android system library or packaged beside it.
    needed="$("${NDK_TOOLCHAIN}/llvm-readelf" -d "${out}" 2>/dev/null \
      | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')"
    while IFS= read -r dep; do
      [ -n "${dep}" ] || continue
      case "${dep}" in
        libc.so|libdl.so|liblog.so|libm.so|libandroid.so|libz.so) ;;
        *)
          if [ -f "${libsdir}/${dep}" ]; then
            cp "${libsdir}/${dep}" "${JNI_DIR}/${abi}/${dep}"
            echo "    [${abi}] packaged hev dependency: ${dep}"
          else
            echo "ERROR: [${abi}] unresolved hev runtime dependency: ${dep}" >&2
            exit 1
          fi
          ;;
      esac
    done <<< "${needed}"
    echo "    [${abi}] libhev-socks5-tunnel.so verified: stable C API (main/quit/stats) OK"
    "${NDK_TOOLCHAIN}/llvm-readelf" -d "${out}" 2>/dev/null | grep NEEDED || true
  done
}

# ---------------------------------------------------------------------------
# 2) SoildTunnel engine  (Rust, cargo-ndk)
# ---------------------------------------------------------------------------
# The repo root has NO Cargo.toml. The binary crate lives in the soildtunnel/
# subdirectory next to the vendored quiche/ library. Detect it: pick the crate
# that has src/main.rs and is NOT under quiche/.
detect_soildtunnel_crate() {
  if [ -f "${SOILDTUNNEL_SRC}/soildtunnel/Cargo.toml" ] && \
     [ -f "${SOILDTUNNEL_SRC}/soildtunnel/src/main.rs" ]; then
    echo "${SOILDTUNNEL_SRC}/soildtunnel"
    return 0
  fi
  local toml d
  while IFS= read -r toml; do
    d="$(dirname "${toml}")"
    case "${d}" in
      *quiche*) continue ;;
    esac
    if [ -f "${d}/src/main.rs" ]; then
      echo "${d}"
      return 0
    fi
  done < <(find "${SOILDTUNNEL_SRC}" -name Cargo.toml -not -path '*/target/*' | sort)
  return 1
}

build_soildtunnel() {
  local crate
  crate="$(detect_soildtunnel_crate || true)"
  if [ -z "${crate}" ]; then
    echo "ERROR: could not find the SoildTunnel binary crate (a Cargo.toml with src/main.rs)." >&2
    echo "Manifests found:" >&2
    find "${SOILDTUNNEL_SRC}" -name Cargo.toml -not -path '*/target/*' >&2 || true
    exit 1
  fi
  echo "==> [soildtunnel] binary crate: ${crate}"

  export CARGO_TARGET_DIR="${SOILDTUNNEL_SRC}/target"

  local bin_name
  bin_name="$(grep -m1 -E '^name[[:space:]]*=' "${crate}/Cargo.toml" \
    | sed -E 's/.*=[[:space:]]*"([^"]+)".*/\1/' || true)"
  echo "    crate name (best-effort): ${bin_name:-<auto-detect>}"

  build_soildtunnel_abi() {
    local abi="$1" triple="$2"
    echo "==> [soildtunnel] building for ${abi} (${triple}, API ${API})"
    ( cd "${crate}" && ANDROID_NDK_ROOT="${ANDROID_NDK_HOME}" cargo ndk -t "${abi}" --platform "${API}" build --release )

    local reldir="${CARGO_TARGET_DIR}/${triple}/release"
    local artifact=""
    if [ -n "${bin_name}" ] && [ -x "${reldir}/${bin_name}" ]; then
      artifact="${reldir}/${bin_name}"
    else
      artifact="$(find "${reldir}" -maxdepth 1 -type f -perm -u+x \
        ! -name '*.so' ! -name '*.d' ! -name '*.rlib' ! -name '*.rmeta' \
        2>/dev/null | head -n1)"
    fi
    if [ -z "${artifact}" ] || [ ! -f "${artifact}" ]; then
      echo "ERROR: could not locate a built SoildTunnel executable in ${reldir}" >&2
      ls -la "${reldir}" 2>/dev/null >&2 || true
      exit 1
    fi

    mkdir -p "${JNI_DIR}/${abi}"
    cp "${artifact}" "${JNI_DIR}/${abi}/libsoildtunnel-core.so"
    "${NDK_TOOLCHAIN}/llvm-strip" "${JNI_DIR}/${abi}/libsoildtunnel-core.so" 2>/dev/null || true
    echo "    installed libsoildtunnel-core.so for ${abi}"
  }

  build_soildtunnel_abi "arm64-v8a"   "aarch64-linux-android"
  build_soildtunnel_abi "armeabi-v7a" "armv7-linux-androideabi"
}

case "${TARGET}" in
  hev)    build_hev ;;
  soildtunnel) build_soildtunnel ;;
  all)    build_hev; build_soildtunnel ;;
  *) echo "Usage: build-natives.sh [hev|soildtunnel|all]" >&2; exit 2 ;;
esac

echo "==> Done (${TARGET}). Installed libs:"
find "${JNI_DIR}" -type f -name '*.so' -exec ls -la {} + 2>/dev/null || true
