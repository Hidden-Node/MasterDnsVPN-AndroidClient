# کلاینت اندروید MasterDnsVPN

[![Android CI](https://github.com/Hidden-Node/MasterDnsVPN-AndroidClient/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Hidden-Node/MasterDnsVPN-AndroidClient/actions/workflows/android-ci.yml)
[![Go Tests](https://github.com/Hidden-Node/MasterDnsVPN-AndroidClient/actions/workflows/go-test.yml/badge.svg)](https://github.com/Hidden-Node/MasterDnsVPN-AndroidClient/actions/workflows/go-test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[English](readme.md) | فارسی

این ریپوزیتوری کلاینت اندرویدی MasterDnsVPN است که رابط کاربری آن با Kotlin/Jetpack Compose توسعه داده شده و از هسته شبکه Go پروژه اصلی استفاده می کند.

## محدوده پروژه و ریپوی بالادستی

- پروژه اصلی (Upstream): https://github.com/masterking32/MasterDnsVPN
- تمرکز این ریپو: اپلیکیشن اندروید، تجربه کاربری، تنظیمات و بسته بندی انتشار
- منطق هسته Go با `gomobile` به صورت AAR وارد اپ می شود

## قابلیت ها

- کنترل کامل چرخه اتصال VPN (اتصال/قطع/وضعیت)
- نمایش زنده تله متری اتصال (وضعیت، سرعت، پیشرفت اسکن)
- صفحه لاگ ساختاریافته با فیلتر منبع و سطح خطا
- مدیریت چند پروفایل (ایجاد، انتخاب، ویرایش، حذف)
- تنظیمات پروفایل و ابزارهای ایمپورت Resolver
- تنظیمات سراسری برای Proxy Mode، Split Tunneling و Sharing
- ذخیره سازی محلی با Room و DataStore
- پشتیبانی از Android 5.0 به بالا (API 21+)

## صفحات اپ

- Home: وضعیت اتصال، سرعت ترافیک، پروفایل فعال، اکشن سریع
- Profiles: ساخت و مدیریت پروفایل ها
- Settings: تنظیمات پروفایل و تنظیمات سراسری
- Logs: نمایش تایم لاین لاگ ها با ابزار Share/Clear
- Info: اطلاعات نسخه و لینک های پروژه

## دانلود و نصب

1. به صفحه **Releases** همین ریپوزیتوری بروید.
2. آخرین فایل **universal APK** را دانلود کنید (پیشنهادی).
3. فایل را روی دستگاه اندرویدی نصب کنید.

اگر نصب مسدود شد، گزینه **Install unknown apps** را برای مرورگر یا فایل منیجر فعال کنید.

## شروع سریع (داخل برنامه)

برای استفاده، ابتدا باید یک سرور MasterDnsVPN فعال داشته باشید.

1. از تب **Profiles** یک پروفایل بسازید یا ایمپورت کنید.
2. مقادیر الزامی مثل `DOMAINS` و `ENCRYPTION_KEY` را وارد کنید.
3. لیست Resolverها را خط به خط اضافه کنید (`IP` یا `IP:PORT`).
4. پروفایل را ذخیره و انتخاب کنید.
5. به تب **Home** برگردید و **Connect** را بزنید.

## ساخت از سورس

### پیش نیازها

- JDK 17
- Android SDK (سازگار با `compileSdk 35`)
- Go toolchain
- ابزارهای `gomobile` و `gobind`

### بیلد دیباگ

```bash
# From repository root
bash ./android/build_go_mobile.sh
cd android
./gradlew :app:assembleDebug
```

### بیلد ریلیز (لوکال)

برای ریلیز باید متغیرهای امضای اندروید را تنظیم کنید (مطابق فایل workflow به نام `release-manual.yml`):

- `ANDROID_SIGNING_ENABLED=true`
- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- اختیاری: `ANDROID_VERSION_NAME` و `ANDROID_VERSION_CODE`

سپس اجرا کنید:

```bash
cd android
./gradlew :app:assembleRelease
```

## CI/CD

- در فایل `android-ci.yml` بیلد AAR و APK دیباگ برای Push/PR انجام می شود.
- در فایل `go-test.yml` تست های Go روی پکیج های مربوط اجرا می شود.
- در فایل `release-manual.yml` انتشار دستی نسخه امضاشده و آپلود فایل های خروجی انجام می شود.

## نکات امنیتی

- فایل APK را فقط از ریلیزهای معتبر دریافت کنید.
- مقدار `ENCRYPTION_KEY`، اطلاعات ورود و کانفیگ کامل را عمومی نکنید.
- قبل از ارسال اسکرین شات یا لاگ، موارد حساس را مخفی کنید.

## عیب یابی

- **خطای نصب / آپدیت انجام نمی شود**: نسخه قبلی با امضای متفاوت را حذف و دوباره نصب کنید.
- **متصل است ولی اینترنت ندارید**: دامنه، Resolverها و دسترس پذیری سرور را بررسی کنید.
- **اگر VPN سریع قطع می شود**: دسترسی VPN و محدودیت های Battery Optimization را بررسی کنید.
- **لاگ نمایش داده نمی شود**: پروفایل فعال انتخاب کنید و یک بار اتصال را تست کنید.

## سلب مسئولیت

این پروژه برای استفاده های قانونی مربوط به حریم خصوصی و مسیریابی شبکه ارائه شده است. مسئولیت رعایت قوانین محلی، سیاست سازمانی و شرایط سرویس ها بر عهده کاربر است.

## لایسنس

این پروژه تحت لایسنس MIT منتشر شده است. فایل [LICENSE](LICENSE) را ببینید.

## قدردانی

- پروژه اصلی MasterDnsVPN و همه مشارکت کنندگان
- لینک پروژه اصلی: https://github.com/masterking32/MasterDnsVPN
- توسعه دهندگان و تسترهای کلاینت اندروید
