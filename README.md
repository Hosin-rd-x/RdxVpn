<p align="center">
  <img src="docs/assets/icon.png" width="104" alt="Rdx VPN">
</p>

<h1 align="center">Rdx VPN</h1>

<p align="center">Every way through, in one app — the Android build of ZedSecure.</p>

<p align="center">
  <a href="https://github.com/Hosin-rd-x/RdxVpn/releases/latest"><img src="https://img.shields.io/github/v/release/Hosin-rd-x/RdxVpn?style=flat-square&color=C7F24E&labelColor=15170B&label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-APK%20%C2%B7%204%20ABIs-C7F24E?style=flat-square&labelColor=15170B" alt="Android">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-C7F24E?style=flat-square&labelColor=15170B" alt="AGPL-3.0"></a>
</p>

<p align="center"><b>English</b> · <a href="README.fa.md">فارسی</a></p>

## What is Rdx VPN?

Rdx VPN is an Android VPN and proxy client built on
[ZedSecure](https://github.com/CluvexStudio/ZedSecure) (ZedSource) by CluvexStudio, and it ships with
more than one engine. When one route is blocked, the next one is already installed: Xray and sing-box for the
usual protocols, Psiphon and Tor for when nothing else connects, DNS tunnels for networks that let
little more than DNS through, and WireGuard, AmneziaWG, OpenConnect and IKEv2 for servers you
already run. Engines can be chained through each other, and one tap tests every server and moves
the connection to the fastest one.

<p align="center">
  <img src="docs/assets/showcase.png" width="100%" alt="Rdx VPN: home, servers and settings">
</p>

## Engines

| Engine | Carries |
|---|---|
| Xray | VLESS with Reality, Vision and XHTTP, VMess, Trojan, Shadowsocks, Hysteria2, WireGuard, AmneziaWG |
| sing-box | TUIC, Naive, AnyTLS, ShadowTLS, OpenVPN `.ovpn` files, and plain sing-box JSON |
| Psiphon | Psiphon's own network; no server of yours needed |
| Tor | obfs4, Snowflake and Conjure bridges |
| DNS tunnels | DNSTT, VayDNS and MasterDNS, over UDP, TCP, DoT or DoH |
| OpenConnect | Cisco AnyConnect and compatible gateways |
| IKEv2 | the IPsec client built into Android |
| SSH | on its own, or through any of the above |

Android has all of them. Linux, Windows and macOS carry Xray, sing-box, the DNS tunnels and SSH.

## Features

- Imports share links, subscriptions, Xray and sing-box JSON, `.ovpn`, Amnezia `vpn://` and QR codes
- Auto-select keeps the tunnel on the fastest server and fails over on its own
- Chains in both directions, for example Xray over Tor or Tor over Xray
- Per-app routing, a geoip/geosite rule editor, and SNI spoofing on rooted devices
- Vault: share a config as a `.zsx` file that connects without showing it, with an optional password and expiry date
- Speed test, MTU finder, DNS resolver scanner and a live log
- Material 3 Expressive, light and dark, in English, Persian, Russian and Chinese

## Download

**Android:** the APK for your device from
[Releases](https://github.com/Hosin-rd-x/RdxVpn/releases/latest) — `arm64-v8a` for almost every
phone, `armeabi-v7a` for older ones; each release also carries the ABIs it is built for.

Google Play, the desktop installers and the other platforms come from the upstream
[ZedSecure](https://github.com/CluvexStudio/ZedSecure) project.

## Building

```sh
./tools/fetch-cores.sh           # every engine at its pinned commit
./tools/build-zedcore.sh         # the Android core; needs Go 1.26.3 and NDK 28 or newer
./gradlew :app:assembleRelease   # one APK per ABI
```

The engines we patched are published as forks; [`tools/core-sources.txt`](tools/core-sources.txt)
lists each one with the exact commit a release is built from. Pushing a `v*` tag builds every Android
ABI in CI and attaches the APKs to the release.

## Credits

Rdx VPN is a fork of [ZedSecure (ZedSource)](https://github.com/CluvexStudio/ZedSecure) by
CluvexStudio, reused here under AGPL-3.0. The design, most of the code and the engines listed below
come from that project — thanks to its authors.

The DNSTT and VayDNS modes of the DNS tunnel are built with the
[VayDNS](https://github.com/net2share/vaydns) library, a fork of [dnstt](https://www.bamsoftware.com/software/dnstt/). The MasterDNS mode is a separate engine, credited below.

The main ideas of the tunnel come from [SlipNet](https://github.com/anonvector/SlipNet) by anonvector: DNS over plain TCP (VayDNS does not have this by itself), the fan-out and round-robin resolver modes with spread count, the DNS pool that picks the fastest resolvers on each connect, the global resolver override and prevent-DNS-fallback settings, and the design and text of the DNS and SSH settings.

Versions before 3.0.9 used SlipNet's own engine, including its TCP transport, inside the core. Since 3.0.9, the engine is `zeddns`, written for this app. It has the same features, but with its own code.

Rdx VPN stands on [Xray-core](https://github.com/XTLS/Xray-core), [sing-box](https://github.com/SagerNet/sing-box), [Psiphon](https://github.com/Psiphon-Labs/psiphon-tunnel-core), [Tor](https://www.torproject.org/), [MasterDnsVPN](https://github.com/masterking32/MasterDnsVPN), [AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go), [OpenConnect](https://www.infradead.org/openconnect/), [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), and [zeptun](https://github.com/Noisemux/zeptun). Their licences are in [NOTICE](NOTICE).

## License

[AGPL-3.0](LICENSE). The core also links sing-box, Psiphon and sing-openvpn, which are GPL-3.0;
section 13 of GPL-3.0 allows combining them with AGPL-3.0 code, and each keeps its own licence.
