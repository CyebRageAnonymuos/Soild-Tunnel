#!/usr/bin/env bash
#
# Creates a private RELEASE keystore plus a keystore.properties file that the
# build reads automatically. Run once, keep both outputs OUT of git (they are
# already ignored), and back the keystore up somewhere safe: losing it means
# users must uninstall/reinstall to ever receive an update again.
#
# Usage:
#   bash scripts/generate-keystore.sh [keystore-file] [key-alias]
# Defaults:
#   keystore-file = release.keystore   key-alias = soildtunnel-release
set -euo pipefail

KS="${1:-release.keystore}"
ALIAS="${2:-soildtunnel-release}"

if ! command -v keytool >/dev/null 2>&1; then
  echo "ERROR: keytool not found. Install a JDK (it ships with Android Studio too)." >&2
  exit 1
fi

if [ -f "$KS" ]; then
  echo "ERROR: $KS already exists - refusing to overwrite a signing key." >&2
  exit 1
fi

read -r -s -p "Choose a keystore password (min 6 chars): " STOREPASS; echo
read -r -s -p "Repeat the password: " STOREPASS2; echo
if [ "$STOREPASS" != "$STOREPASS2" ]; then
  echo "ERROR: passwords do not match." >&2
  exit 1
fi

keytool -genkeypair -v \
  -keystore "$KS" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity 10950 \
  -storepass "$STOREPASS" -keypass "$STOREPASS" \
  -dname "CN=SoildTunnel Mobile, OU=Release, O=SoildTunnel"

cat > keystore.properties <<EOF
storeFile=$KS
storePassword=$STOREPASS
keyAlias=$ALIAS
keyPassword=$STOREPASS
EOF
chmod 600 keystore.properties "$KS"

echo
echo "Done:"
echo "  - $KS                (your PRIVATE signing key - back it up, never commit)"
echo "  - keystore.properties (read automatically by the build - never commit)"
echo
echo "Build a signed release with:  ./gradlew assembleRelease"
