<div align="center">

# SoildTunnel

**A fast, private, censorship-resistant VPN tunnel for Android.**

[![Build & Release](https://github.com/CyebRageAnonymuos/Soild-Tunnel/actions/workflows/build.yml/badge.svg)](../../actions)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg)]()
[![Min SDK](https://img.shields.io/badge/API-26%2B-green.svg)]()

</div>

---

## About

SoildTunnel is a secure tunnel client for Android. It routes all of your
device traffic through a hardened tunnel engine with a full system-VPN mode,
a proxy-only mode, smart auto-connect, per-app routing and live traffic
insights — wrapped in a pure-black liquid-glass interface.

## Features

- **Full VPN mode** — TUN-based routing for every app on the device
- **Proxy mode** — local SOCKS5 / HTTP listeners for selective apps
- **Smart Auto** — automatic endpoint selection with health probing
- **Per-app routing** — tunnel only the apps you choose, block the ones you don't
- **Live traffic panel** — real-time up/down speeds, session timer, exit IP
- **Quick-settings tile + home-screen widget** — one-tap connect
- **Kill switch** — traffic never leaks outside the tunnel
- **IPv6 leak protection** — on by default
- **Pure-black glass UI** — always dark, animation-light, fast on low-end phones

## Download

Grab the latest signed APK from the
[**Releases**](../../releases) page — built automatically by GitHub Actions.

Install the APK that matches your device:

| File | Device |
|------|--------|
| `arm64-v8a` | modern phones (recommended) |
| `armeabi-v7a` | older 32-bit phones |
| `universal` | runs everywhere |

## Building from source

Requirements: JDK 17, Android SDK 35, NDK 26.3, Go 1.22, Rust stable +
`cargo-ndk`. Everything (Rust engine included) builds inside
`.github/workflows/build.yml` — push a commit and collect the APK from
Actions or Releases.

```bash
./gradlew assembleRelease   # requires keystore.properties (see keystore.properties.example)
```

## License

This project is licensed under the [GNU AGPL v3](LICENSE).
Third-party components remain under their own licenses.
