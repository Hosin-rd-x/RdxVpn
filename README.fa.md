<div dir="rtl">

<p align="center">
  <img src="docs/assets/icon.jpg" width="104" alt="Rdx VPN">
</p>

<h1 align="center">Rdx VPN</h1>

<p align="center">همهٔ راه‌های عبور، در یک برنامه — کلاینت <span dir="ltr">VPN</span> و پروکسی برای اندروید، برای شبکه‌هایی که راه‌های معمول در آن‌ها بسته است.</p>

<p align="center">
  <a href="https://github.com/Hosin-rd-x/RdxVpn/releases/latest"><img src="https://img.shields.io/github/v/release/Hosin-rd-x/RdxVpn?style=flat-square&color=C7F24E&labelColor=15170B&label=release" alt="آخرین نسخه"></a>
  <img src="https://img.shields.io/badge/Android-APK%20%C2%B7%20arm64%20%26%20armeabi--v7a-C7F24E?style=flat-square&labelColor=15170B" alt="اندروید">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-C7F24E?style=flat-square&labelColor=15170B" alt="AGPL-3.0"></a>
</p>

<p align="center"><a href="README.en.md">English</a> · <b>فارسی</b></p>

## دانلود

**اندروید:** <span dir="ltr">APK</span> را از
[Releases](https://github.com/Hosin-rd-x/RdxVpn/releases/latest) بگیرید — `arm64-v8a` برای تقریباً همهٔ
گوشی‌ها و `armeabi-v7a` برای گوشی‌های قدیمی‌تر.

لینک سریع آخرین <span dir="ltr">APK</span>:
<div dir="ltr" align="left">https://shop.hosinrdx.ir/RdxVpn-3.8.7-arm64-v8a.apk</div>

## Rdx VPN چیست؟

<span dir="ltr">Rdx VPN</span> کلاینت وی‌پی‌ان و پروکسی برای اندروید است که بر پایهٔ پروژهٔ زدسیکیور ساخته شده است.
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
- **گروه‌های سرور آماده:** <span dir="ltr">RDX - CF</span> (کانفیگ‌های کلادفلر)، <span dir="ltr">RDX - SERVER</span> و سرورهای <span dir="ltr">SSTP</span>؛ هر کدام
  هرجا کشورشان معلوم است پرچم دارند و کانفیگ‌های کلادفلر علامت ☁️ دارند.
- **پیام اطلاع‌رسانی واقعی:** برنامه حتی وقتی بسته است، خبر نسخه تازه را به شما می‌رساند.
- **انتخاب خودکار:** تونل را روی سریع‌ترین سرور نگه می‌دارد و اگر قطع شد خودش جابه‌جا می‌کند.
- **وارد کردن:** لینک، اشتراک، <span dir="ltr">JSON</span> خودِ <span dir="ltr">Xray</span> و <span dir="ltr">sing-box</span>، فایل `.ovpn`، لینک `vpn://` امنزیا و کد <span dir="ltr">QR</span>.

## هسته‌ها

| هسته | چه چیزی را حمل می‌کند |
|---|---|
| ایثر | مسیرهای بر پایه <span dir="ltr">WARP</span> و <span dir="ltr">MASQUE</span>، با حالت خودکار یک‌لمسی و ویرایشگر دستی کامل |
| <span dir="ltr">Xray</span> | <span dir="ltr">VLESS</span> با <span dir="ltr">Reality</span> و <span dir="ltr">Vision</span> و <span dir="ltr">XHTTP</span>، <span dir="ltr">VMess</span>، <span dir="ltr">Trojan</span>، <span dir="ltr">Shadowsocks</span>، <span dir="ltr">Hysteria2</span>، <span dir="ltr">WireGuard</span>، <span dir="ltr">AmneziaWG</span> |
| <span dir="ltr">sing-box</span> | <span dir="ltr">TUIC</span>، <span dir="ltr">Naive</span>، <span dir="ltr">AnyTLS</span>، <span dir="ltr">ShadowTLS</span>، فایل‌های <span dir="ltr">OpenVPN</span> (`.ovpn`) و <span dir="ltr">JSON</span> خودِ <span dir="ltr">sing-box</span> |
| فاکسی / سایفون | رله‌های فاکسی موزیلا و تونل‌های سایفون، با پرچم کشور |
| <span dir="ltr">SSTP</span> | سرورهای <span dir="ltr">SSTP</span>، با پرچم کشور هر سرور |
| تونل <span dir="ltr">DNS</span> | <span dir="ltr">DNSTT</span>، <span dir="ltr">VayDNS</span> و <span dir="ltr">MasterDNS</span>، روی <span dir="ltr">UDP</span> یا <span dir="ltr">TCP</span> یا <span dir="ltr">DoT</span> یا <span dir="ltr">DoH</span> |
| <span dir="ltr">OpenConnect</span> | سرورهای <span dir="ltr">Cisco AnyConnect</span> و سازگارها |
| <span dir="ltr">IKEv2</span> | کلاینت <span dir="ltr">IPsec</span> داخلی خودِ اندروید |
| <span dir="ltr">SSH</span> | به‌تنهایی، یا از داخل هر کدام از بالایی‌ها |

## امکانات

- وارد کردن لینک، اشتراک، <span dir="ltr">JSON</span> خودِ <span dir="ltr">Xray</span> و <span dir="ltr">sing-box</span>، فایل `.ovpn`، لینک `vpn://` امنزیا و کد <span dir="ltr">QR</span>
- انتخاب خودکار: تونل را روی سریع‌ترین سرور نگه می‌دارد و اگر قطع شد خودش جابه‌جا می‌کند
- زنجیره بین هسته‌ها، مثلاً <span dir="ltr">SSH</span> از داخل <span dir="ltr">Xray</span> یا از داخل <span dir="ltr">sing-box</span>
- مسیریابی برای هر برنامه، ویرایشگر قوانین <span dir="ltr">geoip/geosite</span>، و <span dir="ltr">SNI spoofing</span> روی گوشی‌های روت‌شده
- گاوصندوق: کانفیگ را به‌صورت فایل <span dir="ltr">.zsx</span> به اشتراک بگذارید تا بدون نمایش محتوا وصل شود، با رمز و تاریخ انقضای اختیاری
- تست سرعت، یافتن <span dir="ltr">MTU</span>، اسکنر <span dir="ltr">DNS</span> و لاگ زنده
- طراحی <span dir="ltr">Material 3 Expressive</span>، روشن و تاریک، به فارسی و انگلیسی

## ساخت از سورس

<div dir="ltr">

```sh
./tools/get-cores.sh            # engines at their pinned commits
./gradlew :app:assembleRelease  # one <span dir="ltr">APK</span> per <span dir="ltr">ABI</span>
```

</div>

زدن تگ `v*` همهٔ ABIهای اندروید را در <span dir="ltr">CI</span> می‌سازد و <span dir="ltr">APK</span>ها را به ریلیز می‌چسباند.

## سپاسگزاری

برنامهٔ <span dir="ltr">Rdx VPN</span> بر پایهٔ یک پروژهٔ آزاد دیگر ساخته شده است و با همان مجوز آزاد آن استفاده می‌شود. طراحی، بیشترِ کد و هسته‌ها از همان پروژه می‌آیند؛ سپاس از نویسندگانش.

<div dir="ltr">

[ZedSecure (ZedSource)](https://github.com/CluvexStudio/ZedSecure) by CluvexStudio — AGPL-3.0

</div>

حالت‌های تونل دی‌ان‌اس با یک کتابخانهٔ آزاد ساخته شده‌اند که شاخه‌ای از یک پروژهٔ قدیمی‌تر است. حالت مستر‌دی‌ان‌اس هم یک موتور جداگانه است.

<div dir="ltr">

[VayDNS](https://github.com/net2share/vaydns) (fork of [dnstt](https://www.bamsoftware.com/software/dnstt/)) · MasterDNS

</div>

ایده‌های اصلی این تونل از پروژهٔ اسلیپ‌نت آمده است: دی‌ان‌اس روی تی‌سی‌پی ساده، حالت‌های پخش و چرخشی، استخری که در هر اتصال سریع‌ترین پاسخ‌دهنده‌ها را انتخاب می‌کند، تنظیم جایگزینی سراسری و جلوگیری از برگشت به حالت پیش‌فرض، و طراحی و متن تنظیمات دی‌ان‌اس و اس‌اس‌اچ.

<div dir="ltr">

[SlipNet](https://github.com/anonvector/SlipNet) by anonvector

</div>

نسخه‌های پیش از <span dir="ltr">3.0.9</span> موتور خود اسلیپ‌نت، از جمله لایهٔ انتقال تی‌سی‌پی آن، را داخل هسته داشتند. از نسخهٔ <span dir="ltr">3.0.9</span> موتور <span dir="ltr">zeddns</span> است که برای همین برنامه نوشته شده؛ همان قابلیت‌ها را دارد، اما با کد خودش.

این برنامه همچنین روی چند پروژهٔ آزاد دیگر بنا شده است که مجوز هر کدام در فایل نکات حقوقی آمده است.

<div dir="ltr">

- [Xray-core](https://github.com/XTLS/Xray-core)
- [sing-box](https://github.com/SagerNet/sing-box)
- [MasterDnsVPN](https://github.com/masterking32/MasterDnsVPN)
- [AmneziaWG](https://github.com/amnezia-vpn/amneziawg-go)
- [OpenConnect](https://www.infradead.org/openconnect/)
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
- [zeptun](https://github.com/Noisemux/zeptun)

</div>

## لایسنس

مجوز این برنامه همان مجوز آزاد بالاست. هستهٔ برنامه چند پروژهٔ دیگر را هم به خود وصل می‌کند که مجوزشان جداست؛ بند سیزدهم آن مجوز، ترکیب آن‌ها را با این مجوز اجازه می‌دهد و هر بخش مجوز خودش را نگه می‌دارد.

<div dir="ltr">

[AGPL-3.0](LICENSE) · GPL-3.0 (sing-box, Psiphon, sing-openvpn)

</div>

</div>
