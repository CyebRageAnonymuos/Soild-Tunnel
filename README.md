<div align="center">

<img src="app/src/main/res/drawable-nodpi/ic_logo.png" width="100" />

# SoildTunnel

### Fast. Private. Uncensored.

A high-performance, censorship-resistant VPN tunnel for Android that defeats
deep packet inspection, ad trackers, and malware — wrapped in a pure-black
liquid-glass interface.

[![Build & Release](https://github.com/CyberRageAnonymous/Soild-Tunnel/actions/workflows/build.yml/badge.svg)](../../actions)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84.svg)]()
[![Min SDK](https://img.shields.io/badge/API-26%2B-green.svg)]()
[![Version](https://img.shields.io/badge/Version-1.0.4-orange.svg)]()

</div>

---

## What is SoildTunnel?

SoildTunnel is not just another VPN. It is a **censorship circumvention tool**
built specifically for users in heavily restricted networks. It wraps
[Cloudflare WARP](https://www.cloudflare.com/1111/) in a multi-protocol
tunnel engine that adapts to whatever DPI (Deep Packet Inspection) your ISP
throws at it — automatically.

No accounts. No logs. No trackers. One tap and you're free.

---

## Features

### Multi-Protocol Engine

| Protocol | How it works |
|----------|-------------|
| **MASQUE** | Disguises tunnel traffic as normal HTTPS/HTTP2 browsing. Hardest to detect. |
| **WireGuard** | Blazing-fast modern VPN protocol. Best speed when not blocked. |
| **GOOL** | Double-tunnel mode (WARP inside WARP) for maximum anti-DPI. |
| **Smart Auto** | Fingerprints your network's DPI and picks the best protocol automatically. |

### Anti-DPI Obfuscation

Six obfuscation levels (`Noize`) that inject junk packets and fake handshake
signatures so DPI boxes cannot fingerprint your tunnel traffic:

`OFF` → `LIGHT` → `FIREWALL` → `BALANCED` → `GFW` → `AGGRESSIVE`

Plus: **TLS ClientHello fragmentation**, **Encrypted Client Hello (ECH)**, and
**custom SNI override** for maximum stealth.

### DNS Filtering

| Feature | Resolver | What it blocks |
|---------|----------|----------------|
| **Ad Blocking** | Cloudflare Family (1.1.1.3) | Ads, trackers, analytics |
| **Malware Protection** | Cloudflare Security (1.1.1.2) | Phishing, malware, exploits |

Works inside the tunnel on all apps. Supports plain DNS, DNS-over-HTTPS (DoH),
and DNS-over-TLS (DoT) with custom endpoints.

### Split Tunneling

Choose which apps go through the VPN and which don't:

- **Include mode** — only selected apps use the tunnel
- **Exclude mode** — everything except selected apps uses the tunnel
- **Per-app blocking** — completely cut internet access for selected apps

### Privacy & Security

| Feature | Description |
|---------|-------------|
| **Kill Switch** | Blocks all traffic if the tunnel drops — nothing leaks direct |
| **Strict Kill Switch** | Stays locked down even after manual disconnect |
| **IPv6 Leak Protection** | Routes IPv6 through tunnel (on by default) |
| **No Logs** | Zero tracking, zero analytics, zero telemetry |

### Smart Reconnection

The engine monitors the tunnel with a watchdog. If it dies, it automatically
restarts with exponential backoff. Network changes (WiFi ↔ mobile) trigger
instant reconnection. Up to 50 retry cycles before giving up.

### Live Telemetry

The connection card shows real-time:

- Connection status and session timer
- Exit IP address and country flag
- Upload and download speeds
- Active protocol and endpoint
- Live latency badge (color-coded)

### Server Selection

Choose from pre-configured Cloudflare WARP edge nodes or let Smart Auto scan
and pick the fastest gateway. Servers are pinged live with color-coded
latency badges.

---

## Architecture

```
┌─────────────────────────────────────────────────┐
│                 SoildTunnel App                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
│  │ Compose  │  │ TUN      │  │ Engine       │  │
│  │ UI       │  │ Service  │  │ Process      │  │
│  │ (Glass)  │  │ (VpnSvc) │  │ (Rust+Go)   │  │
│  └────┬─────┘  └────┬─────┘  └──────┬───────┘  │
│       │              │               │           │
│       │         ┌────┴────┐    ┌─────┴──────┐   │
│       │         │ hev-    │    │ soildtunnel│   │
│       │         │ socks5  │    │ engine     │   │
│       │         │ tunnel  │    │ (MASQUE/WG/│   │
│       │         └─────────┘    │  GOOL)     │   │
│       │                        └────────────┘   │
│       │                                         │
│  ┌────┴─────────────────────────────────────┐   │
│  │          DataStore (Profile)             │   │
│  │          Keystore (Secrets)              │   │
│  └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

**Components:**

- **Engine** (`native/engine/soildtunnel`) — Rust async runtime handling
  MASQUE/WireGuard/GOOL transports, endpoint scanning, identity provisioning,
  and DNS resolution. Compiled with `cargo-ndk` for ARM.
- **hev-socks5-tunnel** — High-performance userspace SOCKS5-to-TUN forwarder
  with per-app UID filtering for split tunneling and app blocking.
- **VPN Service** — Android `VpnService` that builds the TUN interface,
  applies app filters via `addAllowedApplication`/`addDisallowedApplication`,
  and manages the engine lifecycle.
- **Smart Auto** — Network fingerprinter that probes UDP health, SNI DPI
  behavior, and WARP edge reachability before launch. Classifies the network
  into OPEN / SNI_FILTERING / UDP_THROTTLED / HOSTILE and builds an ordered
  strategy ladder.

---

## Download

Grab the latest signed APK from the
[**Releases**](../../releases) page — built automatically by GitHub Actions.

| File | Device | Notes |
|------|--------|-------|
| `SoildTunnel-1.0.4-arm64-v8a.apk` | Modern 64-bit phones | **Recommended** |
| `SoildTunnel-1.0.4-armeabi-v7a.apk` | Older 32-bit phones | |
| `SoildTunnel-1.0.4-universal.apk` | Any Android device | Largest file |

**Installation:**
1. Download the APK for your device architecture
2. Open the APK — Android will prompt to allow installation from unknown sources
3. Grant VPN permission on first connect (system-level, nothing leaves your device)

---

## Building from source

### Requirements

- JDK 17
- Android SDK 35
- NDK 26.3.11579264
- Go 1.22+
- Rust stable + `cargo-ndk`
- Gradle 8.9

### Build

```bash
# Clone
git clone https://github.com/CyberRageAnonymous/Soild-Tunnel.git
cd Soild-Tunnel

# Fetch native sources (hev-socks5-tunnel, etc.)
bash scripts/fetch-natives.sh

# Build native cores
bash scripts/build-natives.sh hev
bash scripts/build-natives.sh soildtunnel

# Build release APK
./gradlew :app:assembleRelease
```

The release build is fully automated via
[`.github/workflows/build.yml`](.github/workflows/build.yml) — push a tag
(`v1.0.4`) and GitHub Actions builds, signs, and publishes the APKs.

---

## Settings Reference

### Transport
| Setting | Default | Description |
|---------|---------|-------------|
| Protocol | Smart Auto | MASQUE, WireGuard, GOOL, or Auto |
| Scan Mode | Balanced | TURBO/BALANCED/THOROUGH/STEALTH/IRONCLAD |
| IP Version | IPv4 | IPv4, IPv6, or Both |
| MASQUE HTTP/2 | Off | Force HTTP/2 for MASQUE (bypasses QUIC blocks) |
| Noize | Off | Anti-DPI obfuscation level |
| Fragment | Off | TLS ClientHello fragmentation |
| ECH | Off | Encrypted Client Hello |

### DNS
| Setting | Default | Description |
|---------|---------|-------------|
| DNS Mode | Plain | Plain, DoH, or DoT |
| DNS Servers | (engine default) | Custom resolvers inside tunnel |
| Ad Blocking | Off | Filter ads via Cloudflare Family DNS |
| Malware Protection | Off | Block malware via Cloudflare DNS security |

### Routing
| Setting | Default | Description |
|---------|---------|-------------|
| Proxy Mode | Off | Local SOCKS5/HTTP proxy instead of system VPN |
| Split Tunneling | Off | Include/Exclude mode for per-app routing |
| Route Block | (none) | Domains/IPs that must never connect |
| Route Direct | (none) | Domains/IPs that bypass the tunnel |
| Route Sniff | On | SNI-based domain routing for Android |

### Security
| Setting | Default | Description |
|---------|---------|-------------|
| Kill Switch | Off | Block traffic if tunnel drops |
| Strict Kill Switch | Off | Stay locked after manual disconnect |
| IPv6 Leak Protection | On | Route IPv6 through tunnel |
| Auto Reprovision | Off | Re-register identity when refused |

### Engine Tuning
| Setting | Default | Description |
|---------|---------|-------------|
| MTU | 1500 | TUN interface Maximum Transmission Unit |
| Keepalive | 0 (engine default) | WireGuard persistent keepalive |
| Fragment Size | (auto) | TLS fragment chunk-size range |
| Fragment Delay | (auto) | Inter-fragment delay range |
| Core Log Level | Warn | Engine verbosity (off/error/warn/info/debug) |

---

## How Smart Auto Works

1. **FINGERPRINT** — Probes the real network before the engine launches:
   - UDP health via DNS queries to 1.1.1.1 / 8.8.8.8
   - SNI DPI detection via a full TLS handshake
   - WARP edge reachability via TCP connect to each IP range
2. **CLASSIFY** — DPI behavior is classified into one of four classes:
   - `OPEN` — no filtering, any protocol works
   - `SNI_FILTERING` — ISP inspects TLS SNI, needs MASQUE with ECH
   - `UDP_THROTTLED` — UDP is throttled, needs HTTP/2 MASQUE
   - `HOSTILE` — heavy DPI, needs maximum obfuscation
3. **PLAN** — Builds an ordered ladder of strategies (most-likely first)
4. **EXECUTE** — Tries each strategy with a time budget; first one that
   passes the self-test wins

Every decision is logged to the in-app diagnostics panel.

---

## Privacy Policy

SoildTunnel collects **zero** data. There are:
- No accounts or registration
- No analytics or telemetry
- No server-side logging
- No third-party SDKs
- No ads

Your connection metadata never leaves your device. The only network calls
the app makes are to Cloudflare WARP endpoints and optional IP geolocation
checks (ip-api.com) through the tunnel itself.

---

## License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE).
Third-party components remain under their own licenses:
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — MIT
- Cloudflare WARP — [Cloudflare ToS](https://www.cloudflare.com/1111/)
