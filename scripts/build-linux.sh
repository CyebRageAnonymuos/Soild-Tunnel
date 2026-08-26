#!/usr/bin/env bash
# Builds the Linux desktop artifacts: engine, TUN helper, Compose app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/desktop/dist"
mkdir -p "$OUT"

# 1. Rust engine (x86_64-linux CLI binary)
echo "== building engine =="
cargo build --release --manifest-path "$ROOT/native/engine/soildtunnel/Cargo.toml"
cp "$ROOT/native/engine/soildtunnel/target/release/soildtunnel" "$OUT/soildtunnel-core"

# 2. hev-socks5-tunnel + privileged helper
echo "== building tun helper =="
HEV_DIR="$ROOT/build/hev-socks5-tunnel"
if [ ! -d "$HEV_DIR" ]; then
	mkdir -p "$(dirname "$HEV_DIR")"
	git clone --depth 1 https://github.com/heiher/hev-socks5-tunnel.git "$HEV_DIR"
fi
make -C "$HEV_DIR" -j"$(nproc)"
gcc -O2 -o "$OUT/hev-tun-helper" \
	"$ROOT/tun/hev-tun-helper.c" \
	"$HEV_DIR"/build/libhev-socks5-tunnel.so \
	-Wl,-rpath,'$ORIGIN' \
	-lpthread
cp "$HEV_DIR"/build/libhev-socks5-tunnel.so "$OUT/"

# 3. Compose Desktop app: AppImage + deb + fat jar for tar.gz
echo "== building desktop app =="
cd "$ROOT"
./gradlew :desktop:packageAppImage :desktop:packageDeb :desktop:jar --no-daemon

APP_IMAGE_DIR=$(find "$ROOT/desktop/build/compose/binaries" -type d -name "soildtunnel*" | head -1)
JAR=$(find "$ROOT/desktop/build/libs" -name "*.jar" | head -1)

# tar.gz bundle: fat jar + natives + run script
BUNDLE="$OUT/SoildTunnel-linux-x64"
rm -rf "$BUNDLE" && mkdir -p "$BUNDLE"
cp "$JAR" "$BUNDLE/soildtunnel.jar"
cp "$OUT/soildtunnel-core" "$OUT/hev-tun-helper" "$OUT/libhev-socks5-tunnel.so" "$BUNDLE/"
cat > "$BUNDLE/soildtunnel.sh" <<'EOF'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
export LD_LIBRARY_PATH="$DIR:${LD_LIBRARY_PATH:-}"
exec java -jar "$DIR/soildtunnel.jar" "$@"
EOF
chmod +x "$BUNDLE/soildtunnel.sh" "$BUNDLE/soildtunnel-core" "$BUNDLE/hev-tun-helper"

# Copy helpers next to the AppImage payload so pkexec finds them.
if [ -n "${APP_IMAGE_DIR:-}" ]; then
	cp "$OUT/soildtunnel-core" "$OUT/hev-tun-helper" \
		"$OUT/libhev-socks5-tunnel.so" "$APP_IMAGE_DIR/bin/" 2>/dev/null || true
fi

tar -czf "$OUT/SoildTunnel-1.0.3-linux-x64.tar.gz" -C "$(dirname "$BUNDLE")" "$(basename "$BUNDLE")"

echo "== done =="
ls -la "$OUT"
