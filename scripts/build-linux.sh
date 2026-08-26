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

# 2. hev-socks5-tunnel standalone binary + helper script
echo "== building tun components =="
HEV_DIR="$ROOT/build/hev-socks5-tunnel"
if [ ! -d "$HEV_DIR" ]; then
	mkdir -p "$(dirname "$HEV_DIR")"
	git clone --depth 1 --recursive https://github.com/heiher/hev-socks5-tunnel.git "$HEV_DIR"
fi
make -C "$HEV_DIR" -j"$(nproc)"
cp "$HEV_DIR"/bin/hev-socks5-tunnel "$OUT/"
install -m 0755 "$ROOT/tun/hev-tun-helper" "$OUT/hev-tun-helper"

# 3. Compose Desktop app: AppImage + deb + fat jar for tar.gz
echo "== building desktop app =="
cd "$ROOT"
gradle :desktop:packageAppImage :desktop:packageDeb :desktop:jar --no-daemon

APP_IMAGE_DIR=$(find "$ROOT/desktop/build/compose/binaries" -type d -name "soildtunnel*" | head -1)
JAR=$(find "$ROOT/desktop/build/libs" -name "*.jar" | head -1)

# tar.gz bundle: fat jar + natives + run script
BUNDLE="$OUT/SoildTunnel-linux-x64"
rm -rf "$BUNDLE" && mkdir -p "$BUNDLE"
cp "$JAR" "$BUNDLE/soildtunnel.jar"
cp "$OUT/soildtunnel-core" "$OUT/hev-socks5-tunnel" "$OUT/hev-tun-helper" "$BUNDLE/"
cat > "$BUNDLE/soildtunnel.sh" <<'EOF'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
export PATH="$DIR:$PATH"
exec java -jar "$DIR/soildtunnel.jar" "$@"
EOF
chmod +x "$BUNDLE/soildtunnel.sh" "$BUNDLE/soildtunnel-core" \
	"$BUNDLE/hev-socks5-tunnel" "$BUNDLE/hev-tun-helper"

# Copy natives into the AppImage payload so the engine can be found.
if [ -n "${APP_IMAGE_DIR:-}" ] && [ -d "$APP_IMAGE_DIR/bin" ]; then
	cp "$OUT/soildtunnel-core" "$OUT/hev-socks5-tunnel" \
		"$OUT/hev-tun-helper" "$APP_IMAGE_DIR/bin/"
	chmod 0755 "$APP_IMAGE_DIR/bin/hev-socks5-tunnel" \
		"$APP_IMAGE_DIR/bin/hev-tun-helper" \
		"$APP_IMAGE_DIR/bin/soildtunnel-core"
fi

# 4. Patch the deb: extract, inject natives + postinst, repack
DEB_SRC=$(find "$ROOT/desktop/build/compose/binaries" -name "*.deb" | head -1 || true)
if [ -n "$DEB_SRC" ]; then
	echo "== patching deb with native binaries =="
	DEB_WORK=$(mktemp -d)
	trap 'rm -rf "$DEB_WORK"' EXIT
	dpkg-deb -R "$DEB_SRC" "$DEB_WORK"

	# Create /usr/lib/soildtunnel/ for native binaries
	mkdir -p "$DEB_WORK/usr/lib/soildtunnel"
	cp "$OUT/soildtunnel-core"   "$DEB_WORK/usr/lib/soildtunnel/"
	cp "$OUT/hev-socks5-tunnel"  "$DEB_WORK/usr/lib/soildtunnel/"
	install -m 0755 "$OUT/hev-tun-helper" "$DEB_WORK/usr/lib/soildtunnel/hev-tun-helper"

	# Symlink /usr/bin/soildtunnel -> /opt/soildtunnel/bin/soildtunnel
	mkdir -p "$DEB_WORK/usr/bin"
	ln -sf /opt/soildtunnel/bin/soildtunnel "$DEB_WORK/usr/bin/soildtunnel"

	# postinst: set exec perms on native binaries
	mkdir -p "$DEB_WORK/DEBIAN"
	cat > "$DEB_WORK/DEBIAN/postinst" <<'POSTINST'
#!/bin/sh
set -e
chmod 0755 /usr/lib/soildtunnel/soildtunnel-core
chmod 0755 /usr/lib/soildtunnel/hev-socks5-tunnel
chmod 0755 /usr/lib/soildtunnel/hev-tun-helper
POSTINST
	chmod 0755 "$DEB_WORK/DEBIAN/postinst"

	dpkg-deb -b "$DEB_WORK" "$OUT/soildtunnel_1.0.3_amd64.deb"
fi

tar -czf "$OUT/SoildTunnel-1.0.3-linux-x64.tar.gz" -C "$(dirname "$BUNDLE")" "$(basename "$BUNDLE")"

echo "== done =="
ls -la "$OUT"
