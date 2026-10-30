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

<p align="center"><a href="README.en.md">English</a> · <b>فارسی</b></p>

## دانلود

**اندروید:** APK را از
[Releases](https://github.com/Hosin-rd-x/RdxVpn/releases/latest) بگیرید — `arm64-v8a` برای تقریباً همهٔ
گوشی‌ها و `armeabi-v7a` برای گوشی‌های قدیمی‌تر.

لینک سریع آخرین APK:
<div dir="ltr" align="left">https://shop.hosinrdx.ir/RdxVpn-3.8.7-arm64-v8a.apk</div>

## Rdx VPN چیست؟

Rdx VPN کلاینت VPN و پروکسی برای اندروید است که روی
[ZedSecure](https://github.com/CluvexStudio/ZedSecure) (<span dir="ltr">ZedSource</span>) ساختهٔ <span dir="ltr">CluvexStudio</span> ساخته شده.
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
- **وارد کردن:** لینک، اشتراک، JSON خودِ <span dir="ltr">Xray</span> و <span dir="ltr">sing-box</span>، فایل `.ovpn`، لینک `vpn://` امنزیا و کد QR.

## هسته‌ها

| هسته | چه چیزی را حمل می‌کند |
|---|---|
| ایثر | مسیرهای بر پایه <span dir="ltr">WARP</span> و <span dir="ltr">MASQUE</span>، با حالت خودکار یک‌لمسی و ویرایشگر دستی کامل |
| <span dir="ltr">Xray</span> | <span dir="ltr">VLESS</span> با <span dir="ltr">Reality</span> و <span dir="ltr">Vision</span> و <span dir="ltr">XHTTP</span>، <span dir="ltr">VMess</span>، <span dir="ltr">Trojan</span>، <span dir="ltr">Shadowsocks</span>، <span dir="ltr">Hysteria2</span>، <span dir="ltr">WireGuard</span>، <span dir="ltr">AmneziaWG</span> |
| <span dir="ltr">sing-box</span> | <span dir="ltr">TUIC</span>، <span dir="ltr">Naive</span>، <span dir="ltr">AnyTLS</span>، <span dir="ltr">ShadowTLS</span>، فایل‌های <span dir="ltr">OpenVPN</span> (`.ovpn`) و JSON خودِ <span dir="ltr">sing-box</span> |
| فاکسی / سایفون | رله‌های فاکسی موزیلا و تونل‌های سایفون، با پرچم کشور |
| <span dir="ltr">SSTP</span> | سرورهای <span dir="ltr">SSTP</span>، با پرچم کشور هر سرور |
| تونل <span dir="ltr">DNS</span> | <span dir="ltr">DNSTT</span>، <span dir="ltr">VayDNS</span> و <span dir="ltr">MasterDNS</span>، روی <span dir="ltr">UDP</span> یا <span dir="ltr">TCP</span> یا <span dir="ltr">DoT</span> یا <span dir="ltr">DoH</span> |
| <span dir="ltr">OpenConnect</span> | سرورهای <span dir="ltr">Cisco AnyConnect</span> و سازگارها |
| <span dir="ltr">IKEv2</span> | کلاینت <span dir="ltr">IPsec</span> داخلی خودِ اندروید |
| <span dir="ltr">SSH</span> | به‌تنهایی، یا از داخل هر کدام از بالایی‌ها |

## امکانات

- وارد کردن لینک، اشتراک، JSON خودِ <span dir="ltr">Xray</span> و <span dir="ltr">sing-box</span>، فایل `.ovpn`، لینک `vpn://` امنزیا و کد QR
- انتخاب خودکار: تونل را روی سریع‌ترین سرور نگه می‌دارد و اگر قطع شد خودش جابه‌جا می‌کند
- زنجیره بین هسته‌ها، مثلاً <span dir="ltr">SSH</span> از داخل <span dir="ltr">Xray</span> یا از داخل <span dir="ltr">sing-box</span>
- مسیریابی برای هر برنامه، ویرایشگر قوانین <span dir="ltr">geoip/geosite</span>، و <span dir="ltr">SNI spoofing</span> روی گوشی‌های روت‌شده
- <span dir="ltr">Vault</span>: کانفیگ را به‌صورت فایل `.zsx` به اشتراک بگذارید تا بدون نمایش محتوا وصل شود، با رمز و تاریخ انقضای اختیاری
- تست سرعت، یافتن MTU، اسکنر <span dir="ltr">DNS</span> و لاگ زنده
- طراحی <span dir="ltr">Material 3 Expressive</span>، روشن و تاریک، به فارسی و انگلیسی

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
<span dir="ltr">CluvexStudio</span> است که با لایسنس AGPL-3.0 اینجا استفاده شده؛ طراحی، بیشترِ کد و هسته‌های پایین از همان
پروژه می‌آیند — سپاس از نویسندگانش.

حالت‌های <span dir="ltr">DNSTT</span> و <span dir="ltr">VayDNS</span> تونل <span dir="ltr">DNS</span> با کتابخانهٔ [VayDNS](https://github.com/net2share/vaydns)
ساخته شده‌اند که فورکی از [dnstt](https://www.bamsoftware.com/software/dnstt/) است. حالت <span dir="ltr">MasterDNS</span>
موتور جداگانه‌ای است که پایین‌تر از آن نام برده شده.

ایده‌های اصلی این تونل از [SlipNet](https://github.com/anonvector/SlipNet) ساختهٔ <span dir="ltr">anonvector</span>
آمده است: <span dir="ltr">DNS</span> روی <span dir="ltr">TCP</span> ساده (که <span dir="ltr">VayDNS</span> به‌تنهایی ندارد)، حالت‌های رزالور fan-out و round-robin
همراه با تعداد پخش، استخر <span dir="ltr">DNS</span> که در هر اتصال سریع‌ترین رزالورها را انتخاب می‌کند، تنظیمات
override سراسری رزالور و جلوگیری از fallback ی <span dir="ltr">DNS</span>، و طراحی و متن تنظیمات <span dir="ltr">DNS</span> و <span dir="ltr">SSH</span>.

نسخه‌های پیش از 3.0.9 موتور خود <span dir="ltr">SlipNet</span>، از جمله لایهٔ انتقال <span dir="ltr">TCP</span> آن، را داخل هسته داشتند. از
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

[AGPL-3.0](LICENSE). هستهٔ برنامه <span dir="ltr">sing-box</span> و سایفون و sing-openvpn را هم لینک می‌کند که GPL-3.0
هستند؛ بند ۱۳ لایسنس GPL-3.0 ترکیب آن‌ها با کد AGPL-3.0 را اجازه می‌دهد و هر بخش لایسنس خودش را نگه می‌دارد.

</div>
