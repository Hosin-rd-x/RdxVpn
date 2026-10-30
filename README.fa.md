<div dir="rtl">

<p align="center">
  <img src="docs/assets/icon.jpg" width="104" alt="Rdx VPN">
</p>

<h1 align="center">Rdx VPN</h1>

<p align="center">همهٔ راه‌های عبور، در یک برنامه — کلاینت VPN و پروکسی برای اندروید، برای شبکه‌هایی که راه‌های معمول در آن‌ها بسته است.</p>

<p align="center">
  <a href="https://github.com/Hosin-rd-x/RdxVpn/releases/latest"><img src="https://img.shields.io/github/v/release/Hosin-rd-x/RdxVpn?style=flat-square&color=C7F24E&labelColor=15170B&label=release" alt="آخرین نسخه"></a>
  <img src="https://img.shields.io/badge/Android-APK%20%C2%B7%20arm64%20%26%20armeabi--v7a-C7F24E?style=flat-square&labelColor=15170B" alt="اندروید">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-C7F24E?style=flat-square&labelColor=15170B" alt="AGPL-3.0"></a>
</p>

<p align="center"><a href="README.md">English</a> · <b>فارسی</b></p>

## دانلود

**اندروید:** APK را از
[Releases](https://github.com/Hosin-rd-x/RdxVpn/releases/latest) بگیرید — `arm64-v8a` برای تقریباً همهٔ
گوشی‌ها و `armeabi-v7a` برای گوشی‌های قدیمی‌تر.

لینک سریع آخرین APK:
https://shop.hosinrdx.ir/RdxVpn-3.8.7-arm64-v8a.apk

## Rdx VPN چیست؟

Rdx VPN کلاینت VPN و پروکسی برای اندروید است که روی
[ZedSecure](https://github.com/CluvexStudio/ZedSecure) (ZedSource) ساختهٔ CluvexStudio ساخته شده.
به‌جای یک هسته، چند هسته را با هم دارد. وقتی یک مسیر بسته شود، مسیر بعدی از قبل نصب است، و با یک
لمس همهٔ سرورها تست می‌شوند و اتصال روی سریع‌ترینشان می‌رود.

<p align="center">
  <img src="docs/assets/showcase.png" width="100%" alt="Rdx VPN: خانه، سرورها و تنظیمات">
</p>

## چیزهای تازه و مهم

- **ایثر، با یک لمس:** یک کانفیگ ایثر اضافه کنید و با بهترین تنظیماتِ همان اینترنت، خودکار وصل
  شوید. ویرایشگر دستی کامل هم هست، با انتخاب محل خروج و منطقه سایفون از روی فهرست پرچم‌دار.
- **فاکسی با پرچم کشور:** سرورهای فاکسی پرچم واقعی کشورشان را کنار اسمشان نشان می‌دهند، تا با یک
  نگاه کشور را انتخاب کنید.
- **گروه‌های سرور آماده:** RDX - CF (کانفیگ‌های کلادفلر)، RDX - SERVER و سرورهای SSTP؛ هر کدام
  هرجا کشورشان معلوم است پرچم دارند و کانفیگ‌های کلادفلر علامت ☁️ دارند.
- **پیام اطلاع‌رسانی واقعی:** برنامه حتی وقتی بسته است، خبر نسخه تازه را به شما می‌رساند.
- **انتخاب خودکار:** تونل را روی سریع‌ترین سرور نگه می‌دارد و اگر قطع شد خودش جابه‌جا می‌کند.
- **وارد کردن:** لینک، اشتراک، JSON خودِ Xray و sing-box، فایل `.ovpn`، لینک `vpn://` امنزیا و کد QR.

## هسته‌ها

| هسته | چه چیزی را حمل می‌کند |
|---|---|
| ایثر | مسیرهای بر پایه WARP و MASQUE، با حالت خودکار یک‌لمسی و ویرایشگر دستی کامل |
| Xray | VLESS با Reality و Vision و XHTTP، VMess، Trojan، Shadowsocks، Hysteria2، WireGuard، AmneziaWG |
| sing-box | TUIC، Naive، AnyTLS، ShadowTLS، فایل‌های OpenVPN (`.ovpn`) و JSON خودِ sing-box |
| فاکسی / سایفون | رله‌های فاکسی موزیلا و تونل‌های سایفون، با پرچم کشور |
| SSTP | سرورهای SSTP، با پرچم کشور هر سرور |
| تونل DNS | DNSTT، VayDNS و MasterDNS، روی UDP یا TCP یا DoT یا DoH |
| OpenConnect | سرورهای Cisco AnyConnect و سازگارها |
| IKEv2 | کلاینت IPsec داخلی خودِ اندروید |
| SSH | به‌تنهایی، یا از داخل هر کدام از بالایی‌ها |

## امکانات

- وارد کردن لینک، اشتراک، JSON خودِ Xray و sing-box، فایل `.ovpn`، لینک `vpn://` امنزیا و کد QR
- انتخاب خودکار: تونل را روی سریع‌ترین سرور نگه می‌دارد و اگر قطع شد خودش جابه‌جا می‌کند
- زنجیره بین هسته‌ها، مثلاً SSH از داخل Xray یا از داخل sing-box
- مسیریابی برای هر برنامه، ویرایشگر قوانین geoip/geosite، و SNI spoofing روی گوشی‌های روت‌شده
- Vault: کانفیگ را به‌صورت فایل `.zsx` به اشتراک بگذارید تا بدون نمایش محتوا وصل شود، با رمز و تاریخ انقضای اختیاری
- تست سرعت، یافتن MTU، اسکنر DNS و لاگ زنده
- طراحی Material 3 Expressive، روشن و تاریک، به فارسی و انگلیسی

## ساخت از سورس

<div dir="ltr">

```sh
./tools/get-cores.sh            # engines at their pinned commits
./gradlew :app:assembleRelease  # one APK per ABI
```

</div>

زدن تگ `v*` همهٔ ABIهای اندروید را در CI می‌سازد و APKها را به ریلیز می‌چسباند.

## سپاسگزاری

Rdx VPN فورکی از [ZedSecure (ZedSource)](https://github.com/CluvexStudio/ZedSecure) ساختهٔ
CluvexStudio است که با لایسنس AGPL-3.0 اینجا استفاده شده؛ طراحی، بیشترِ کد و هسته‌های پایین از همان
پروژه می‌آیند — سپاس از نویسندگانش.

حالت‌های DNSTT و VayDNS تونل DNS با کتابخانهٔ [VayDNS](https://github.com/net2share/vaydns)
ساخته شده‌اند که فورکی از [dnstt](https://www.bamsoftware.com/software/dnstt/) است. حالت MasterDNS
موتور جداگانه‌ای است که پایین‌تر از آن نام برده شده.

ایده‌های اصلی این تونل از [SlipNet](https://github.com/anonvector/SlipNet) ساختهٔ anonvector
آمده است: DNS روی TCP ساده (که VayDNS به‌تنهایی ندارد)، حالت‌های رزالور fan-out و round-robin
همراه با تعداد پخش، استخر DNS که در هر اتصال سریع‌ترین رزالورها را انتخاب می‌کند، تنظیمات
override سراسری رزالور و جلوگیری از fallback ی DNS، و طراحی و متن تنظیمات DNS و SSH.

نسخه‌های پیش از 3.0.9 موتور خود SlipNet، از جمله لایهٔ انتقال TCP آن، را داخل هسته داشتند. از
نسخهٔ 3.0.9 موتور `zeddns` است که برای همین اپ نوشته شده؛ همان قابلیت‌ها را دارد، اما با کد
خودش.

Rdx VPN همچنین روی
[Xray-core](https://github.com/XTLS/Xray-core)، [sing-box](https://github.com/SagerNet/sing-box)،
[MasterDnsVPN](https://github.com/masterking32/MasterDnsVPN)،
[AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go)،
[OpenConnect](https://www.infradead.org/openconnect/)،
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) و
[zeptun](https://github.com/Noisemux/zeptun) بنا شده است. لایسنس هر کدام در
[NOTICE](NOTICE) آمده است.

## لایسنس

[AGPL-3.0](LICENSE). هستهٔ برنامه sing-box و سایفون و sing-openvpn را هم لینک می‌کند که GPL-3.0
هستند؛ بند ۱۳ لایسنس GPL-3.0 ترکیب آن‌ها با کد AGPL-3.0 را اجازه می‌دهد و هر بخش لایسنس خودش را نگه می‌دارد.

</div>
