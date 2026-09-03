# Decimen Android Receiver

نسخه‌ی Native اندروید برای دریافت فایل از جریان QR متحرک پروژه‌ی
`decimen-optical-transfer`.

## وضعیت نسخه 0.1

این نسخه **گیرنده** است، نه محصول نهایی دوطرفه. قابلیت‌های فعلی:

- Kotlin + Jetpack Compose
- CameraX با `STRATEGY_KEEP_ONLY_LATEST`
- رمزگشایی QR با ZXing Core
- استخراج صحیح `BYTE_SEGMENTS` برای داده‌ی باینری
- پیاده‌سازی سازگار LT Fountain Code
- ورود به انتقال از وسط جریان، بدون handshake
- حذف فریم‌های تکراری
- نمایش پیشرفت بر اساس تعداد فریم‌های جدید
- بازسازی فایل و بررسی FNV-1a
- تشخیص اولیه PNG، JPEG، GIF، WebP، PDF، ZIP/APK، MP4، GZip و MP3
- ذخیره با Storage Access Framework؛ بدون مجوز گسترده حافظه
- رابط فارسی و راست‌به‌چپ

## محدودیت‌های عمدی

- فقط دریافت؛ فرستنده اندرویدی هنوز اضافه نشده است.
- پروتکل اصلی نام فایل و MIME type را انتقال نمی‌دهد؛ نام خروجی تولید می‌شود.
- برای جلوگیری از OutOfMemory، نسخه‌ی حافظه‌ای فعلی حداکثر 8192 بلوک و 32 MiB
  را می‌پذیرد. پشتیبانی درست از فایل‌های بزرگ نیازمند segmentation و نوشتن روی
  فایل موقت است، نه صرفاً بالا بردن عدد محدودیت.
- FNV-1a فقط برای تشخیص خرابی انتقال است و امضای امنیتی محسوب نمی‌شود.
- هنوز روی مجموعه‌ای از گوشی‌های واقعی benchmark نشده است.

## ساخت پروژه

نیازمندی‌ها:

- Android Studio با JDK 17
- Android SDK Platform 36
- اتصال اینترنت در اولین Sync

پروژه را در Android Studio باز کنید و Gradle Sync را اجرا کنید. اسکریپت‌های
`gradlew` و `gradlew.bat` یک bootstrap متنی هستند؛ در اولین اجرا Gradle 8.13 را
دانلود و SHA-256 آن را بررسی می‌کنند.

ساخت از خط فرمان:

```bash
./gradlew testDebugUnitTest assembleDebug
```

خروجی APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## آزمون انتقال

1. فرستنده‌ی وب اصلی را اجرا کنید.
2. یک فایل کوچک انتخاب و جریان QR را شروع کنید.
3. اپ اندروید را باز کنید و دسترسی دوربین بدهید.
4. QR را در کادر نگه دارید تا پیشرفت به 100٪ برسد.
5. پس از تأیید هش، دکمه ذخیره را بزنید.

برای تست اولیه، payload حدود 700 تا 1465 بایت و 12 تا 24 FPS منطقی‌تر است.
اگر نمایشگر یا نور ضعیف است، ابتدا FPS یا تراکم QR را پایین بیاورید؛ افزایش
هم‌زمان هر دو معمولاً نرخ خطا را بدتر می‌کند.

## تست سازگاری الگوریتم

بردارهای مرجع JavaScript در `tools/reference-vectors.txt` نگهداری می‌شوند.
تست‌های Kotlin همان sequence، session ID و block count را بررسی می‌کنند.
همچنین یک round-trip با حذف عمدی بخشی از فریم‌ها اجرا می‌شود.

نتیجه‌ی تست مستقل انجام‌شده هنگام تولید این نسخه:

```text
PASS: Kotlin fountain/protocol core matches JavaScript vectors and round-trip test
framesNew=86, solved=51/51
```

## ساختار

```text
app/src/main/java/io/decimen/android/
├── camera/      CameraX و ZXing
├── core/        پروتکل، Fountain Code و تشخیص نوع فایل
├── receiver/    وضعیت و منطق دریافت
└── ui/          رابط Compose
```

## قدم بعدی فنی

قدم بعدی باید فرستنده‌ی Android سازگار با پروتکل v1 باشد. بعد از آن، پروتکل v2
با metadata، SHA-256، segmentation، resume و رمزگذاری اختیاری اضافه می‌شود.
پیاده‌سازی مستقیم فایل‌های بزرگ با همین decoder حافظه‌ای تصمیم اشتباهی است.

## مجوز

MIT. پیاده‌سازی پروتکل و Fountain Code از پروژه اصلی مشتق شده و اعلان آن در
`LICENSE` و `THIRD_PARTY_NOTICES.md` حفظ شده است.
