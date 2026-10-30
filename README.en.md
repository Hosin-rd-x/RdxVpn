<p align="center">
  <img src="docs/assets/icon.jpg" width="104" alt="Rdx VPN">
</p>

<h1 align="center">Rdx VPN</h1>

<p align="center">Every way through, in one app — an Android VPN and proxy client for networks where the usual routes are blocked.</p>

<p align="center">
  <a href="https://github.com/Hosin-rd-x/RdxVpn/releases/latest"><img src="https://img.shields.io/github/v/release/Hosin-rd-x/RdxVpn?style=flat-square&color=C7F24E&labelColor=15170B&label=release" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-APK%20%C2%B7%20arm64%20%26%20armeabi--v7a-C7F24E?style=flat-square&labelColor=15170B" alt="Android">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-C7F24E?style=flat-square&labelColor=15170B" alt="AGPL-3.0"></a>
</p>

<p align="center"><b>English</b> · <a href="README.md">فارسی</a></p>

## Download

**Android:** get the APK from
[Releases](https://github.com/Hosin-rd-x/RdxVpn/releases/latest) — `arm64-v8a` for almost every
phone, `armeabi-v7a` for older ones.

Fast mirror of the latest APK:
https://shop.hosinrdx.ir/RdxVpn-3.8.7-arm64-v8a.apk

## What is Rdx VPN?

Rdx VPN is an Android VPN and proxy client built on
[ZedSecure](https://github.com/CluvexStudio/ZedSecure) (ZedSource) by CluvexStudio. Instead of
one engine, it ships with several. When one route is blocked, the next one is already installed —
and one tap tests the servers and moves the connection to the fastest one.

<p align="center">
  <img src="docs/assets/showcase.png" width="100%" alt="Rdx VPN: home, servers and settings">
</p>

## Highlights

- **Aether, one tap:** add an Aether config and connect with the best settings for your
  network, automatically. A manual editor is included for advanced users, with an exit-location
  and Psiphon-region picker.
- **Foxy relays with country flags:** Foxy servers show their real country flag next to the
  name, so you can pick a location at a glance.
- **Built-in server groups:** RDX - CF (Cloudflare configs), RDX - SERVER, and SSTP servers,
  each with its country flag where the country is known. Cloudflare configs carry a ☁️ marker.
- **Real push notifications:** the app can notify you when a new version is out, even when
  it is closed.
- **Auto-select:** keeps the tunnel on the fastest server and fails over on its own.
- **Imports:** share links, subscriptions, Xray and sing-box JSON, `.ovpn` files,
  Amnezia `vpn://` links and QR codes.

## Engines

| Engine | Carries |
|---|---|
| Aether | WARP / MASQUE-based routes, with a one-tap auto mode and a full manual editor |
| Xray | VLESS with Reality, Vision and XHTTP, VMess, Trojan, Shadowsocks, Hysteria2, WireGuard, AmneziaWG |
| sing-box | TUIC, Naive, AnyTLS, ShadowTLS, OpenVPN `.ovpn` files, and plain sing-box JSON |
| Foxy / Psiphon | Mozilla Foxy relays and Psiphon tunnels, with country flags |
| SSTP | SSTP servers, with per-server country flags |
| DNS tunnels | DNSTT, VayDNS and MasterDNS, over UDP, TCP, DoT or DoH |
| OpenConnect | Cisco AnyConnect and compatible gateways |
| IKEv2 | the IPsec client built into Android |
| SSH | on its own, or through any of the above |

## Features

- Imports share links, subscriptions, Xray and sing-box JSON, `.ovpn`, Amnezia `vpn://` and QR codes
- Auto-select keeps the tunnel on the fastest server and fails over on its own
- Chains, for example SSH through Xray or through sing-box
- Per-app routing, a geoip/geosite rule editor, and SNI spoofing on rooted devices
- Vault: share a config as a `.zsx` file that connects without showing it, with an optional password and expiry date
- Speed test, MTU finder, DNS resolver scanner and a live log
- Material 3 Expressive, light and dark, in English and Persian

## Building

```sh
./tools/get-cores.sh            # engines at their pinned commits
./gradlew :app:assembleRelease  # one APK per ABI
```

Pushing a `v*` tag builds every Android ABI in CI and attaches the APKs to the release.

## Credits

Rdx VPN is a fork of [ZedSecure (ZedSource)](https://github.com/CluvexStudio/ZedSecure) by
CluvexStudio, reused here under AGPL-3.0. The design, most of the code and the engines listed below
come from that project — thanks to its authors.

The DNSTT and VayDNS modes of the DNS tunnel are built with the
[VayDNS](https://github.com/net2share/vaydns) library, a fork of [dnstt](https://www.bamsoftware.com/software/dnstt/). The MasterDNS mode is a separate engine, credited below.

The main ideas of the tunnel come from [SlipNet](https://github.com/anonvector/SlipNet) by anonvector: DNS over plain TCP (VayDNS does not have this by itself), the fan-out and round-robin resolver modes with spread count, the DNS pool that picks the fastest resolvers on each connect, the global resolver override and prevent-DNS-fallback settings, and the design and text of the DNS and SSH settings.

Versions before 3.0.9 used SlipNet's own engine, including its TCP transport, inside the core. Since 3.0.9, the engine is `zeddns`, written for this app. It has the same features, but with its own code.

Rdx VPN stands on [Xray-core](https://github.com/XTLS/Xray-core), [sing-box](https://github.com/SagerNet/sing-box), [MasterDnsVPN](https://github.com/masterking32/MasterDnsVPN), [AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go), [OpenConnect](https://www.infradead.org/openconnect/), [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel), and [zeptun](https://github.com/Noisemux/zeptun). Their licences are in [NOTICE](NOTICE).

## License

[AGPL-3.0](LICENSE). The core also links sing-box, Psiphon and sing-openvpn, which are GPL-3.0;
section 13 of GPL-3.0 allows combining them with AGPL-3.0 code, and each keeps its own licence.
