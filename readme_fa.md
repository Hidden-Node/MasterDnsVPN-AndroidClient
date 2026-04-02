# کلاینت اندروید MasterDnsVPN (نسخه اشتراکی)

این ریپازیتوری یک بسته اشتراکی برای کلاینت اندروید است که بر پایه پروژه اصلی MasterDnsVPN آماده شده است.

- پروژه اصلی: https://github.com/masterking32/MasterDnsVPN
- این ریپو شامل سورس اندروید + سورس هسته Go + CI آماده است.

## امکانات این ریپازیتوری

- سورس اپ اندروید (`android/`)
- سورس هسته Go (`mobile/`، `internal/`، `cmd/`)
- GitHub Actions برای:
  - ساخت AAR هسته Go
  - ساخت APK اندروید
  - اجرای تست‌های Go
- خروجی APK به صورت split شبیه SlipNet:
  - `arm64-v8a`
  - `armeabi-v7a`
  - `x86`
  - `universal`

## آپلود فقط با سایت GitHub (بدون ترمینال)

1. یک ریپازیتوری خالی در GitHub بسازید.
2. وارد صفحه همان ریپو شوید.
3. از بالا روی **Add file** -> **Upload files** بزنید.
4. همه فایل‌ها و پوشه‌های داخل `github_publish_package` را در مرورگر drag & drop کنید.
5. Commit انجام دهید.
6. وارد تب **Actions** شوید و منتظر تکمیل بیلد بمانید.
7. خروجی‌ها را از بخش artifacts دانلود کنید.

## خروجی‌های GitHub Actions

ورک‌فلو: `.github/workflows/android-ci.yml`

Artifactها:
- `app-debug-apk-splits` (خروجی‌های ABI + universal)
- `masterdnsvpn-aar` (فایل AAR هسته Go)


## انتشار دستی با تگ دلخواه شما

اگر می‌خواهید تگ‌ها را خودتان مشخص کنید، از ورک‌فلو زیر استفاده کنید:

- `.github/workflows/release-manual.yml`

مراحل:
1. وارد **Actions** شوید و **Manual Release** را انتخاب کنید
2. روی **Run workflow** بزنید
3. تگ دلخواه (مثلا `v1.0.0`) را وارد کنید
4. در صورت نیاز عنوان Release را وارد کنید
5. اجرا کنید

این ورک‌فلو AAR و APKهای split را می‌سازد و در GitHub Releases با همان تگ منتشر می‌کند.

## بیلد لوکال (اختیاری)

ساخت AAR:

```bash
./android/build_go_mobile.sh
```

ساخت APK:

```bash
cd android
./gradlew :app:assembleDebug
```

## نکات

- فایل `android/local.properties` عمدا داخل git نیست.
- برای بیلد gomobile، نصب Android SDK/NDK الزامی است.
- در CI از Java 17 + Go + Android SDK استفاده می‌شود.

## قدردانی

این پروژه بر پایه MasterDnsVPN اصلی است:

- https://github.com/masterking32/MasterDnsVPN
