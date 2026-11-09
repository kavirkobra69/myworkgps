# Smart Attendance

اپ اندروید ثبت حضور خودکار با GPS. وقتی به محل کار می‌رسید، ورود شما با زمان دقیق ثبت می‌شود و پیام حضور به ربات تلگرام شما می‌رود. نیازی به زدن دکمه نیست.

- Android (Kotlin، Jetpack Compose)، Geofencing با Google Play services، Room، WorkManager
- نقشه OpenStreetMap (osmdroid): بدون API key و بدون هزینه
- ارسال تلگرام با GitHub Actions (توکن ربات فقط در GitHub Secrets)
- ساخت و انتشار APK امضاشده با GitHub Actions

## معماری (نسخه ۱.۲.۰)

اپ → GitHub API (`workflow_dispatch`) → GitHub Actions → Telegram Bot. دیگر Cloudflare Worker وجود ندارد.

- اپ ورود به محدوده را با Geofencing تشخیص می‌دهد، ۱۰ ثانیه ماندن پیوسته را تأیید می‌کند و workflow به نام `Attendance` را در ریپوی شما اجرا می‌کند.
- ورودی‌های workflow: نام، شناسه کارمندی، محل کار، تاریخ (میلادی/شمسی)، ساعت ورود، مختصات، زبان. متن پیام (فارسی/انگلیسی، لینک OpenStreetMap، وضعیت ARRIVED) داخل workflow ساخته می‌شود.
- توکن ربات و Chat ID فقط در **GitHub Actions Secrets** (`TELEGRAM_BOT_TOKEN`، `TELEGRAM_CHAT_ID`) نگه‌داری می‌شوند. اپ آن‌ها را رمزنگاری‌شده (libsodium sealed box) با API به GitHub می‌فرستد. توکن ربات روی گوشی ذخیره نمی‌شود و در ورودی workflow یا لاگ نمی‌آید.
- توکن GitHub با EncryptedSharedPreferences روی گوشی ذخیره می‌شود.
- دکمه «Connect» فایل `.github/workflows/attendance.yml` را در ریپو می‌سازد یا به‌روز می‌کند و شاخه پیش‌فرض و فعال بودن workflow را بررسی می‌کند.
- جلوگیری از پیام تکراری: هر ورود یک `event_id` دارد. درخواستی که GitHub پذیرفته دوباره فرستاده نمی‌شود، مگر اجرای آن قطعاً شکست خورده باشد (حداکثر ۳ بار). اگر پاسخ درخواست گم شود، قبل از تلاش دوباره اجراهای موجود بررسی می‌شوند.
- وضعیت تحویل (موفق/خطا و لینک اجرا) داخل اپ نمایش داده می‌شود.

### تنظیم یک‌باره

1. یک ریپو بسازید (خصوصی هم می‌شود). Actions باید فعال باشد.
2. در GitHub ← Settings ← Developer settings ← Personal access tokens ← **Fine-grained tokens** توکن بسازید، فقط روی همان ریپو، با دسترسی‌ها: Actions (Read and write)، Contents (Read and write)، Workflows (Read and write)، Secrets (Read and write)، Metadata (Read-only). توکن classic: اسکوپ‌های `repo` و `workflow`.
3. در تلگرام با `@BotFather` ربات بسازید، توکن را کپی کنید و داخل ربات Start بزنید. Chat ID عددی را از `@userinfobot` بگیرید.
4. در اپ (Settings یا ویزارد): توکن GitHub و آدرس ریپو ← **Connect / Test connection**. سپس توکن ربات و Chat ID ← **Save Telegram settings** ← **Send test message**.
5. کلید Automatic attendance را روشن کنید. دیگر نیازی به باز کردن اپ نیست.

هر ارسال یک اجرای GitHub Actions (حدود ۱۰ تا ۲۰ ثانیه) مصرف می‌کند. سهمیه رایگان ریپوی خصوصی ۲۰۰۰ دقیقه در ماه است و ریپوی عمومی نامحدود.

### رفتار اصلی

- **کلید پایش:** روشن = geofence (بدون GPS مداوم) که با بستن اپ و بعد از ریبوت ادامه دارد. خاموش = لغو کامل geofence، watchdog، سرویس و ارسال‌های در صف.
- **ورود:** ۱۰ ثانیه ماندن پیوسته داخل محدوده (loitering delay)، سپس فقط یک پیام. پیام بعدی فقط بعد از خروج و ورود دوباره.
- **زبان و تاریخ:** English/فارسی و میلادی/شمسی در Settings.
- **آپدیت داخل برنامه:** Settings ← Update App از Releases ریپوی `kavirkobra69/myworkgps`.
- بدون Google Play services فقط بررسی هر ۱۵ دقیقه کار می‌کند.

## ساختار ریپو

| مسیر | محتوا |
|---|---|
| `app/` | کد اپ اندروید |
| `.github/workflows/attendance.yml` | workflow ارسال پیام حضور (اپ آن را خودکار در ریپوی شما نصب می‌کند) |
| `.github/workflows/build-release.yml` | ساخت APK و انتشار در Releases |
| `tools/make-keystore.sh` | ساخت keystore امضا (برای Termux یا هر لینوکس) |

## GitHub Secrets برای امضای APK

مسیر: ریپو ← **Settings** ← **Secrets and variables** ← **Actions** ← **New repository secret**

**برای امضای APK (یک بار):**

| Secret | مقدار |
|---|---|
| `KEYSTORE_BASE64` | فایل keystore به‌صورت base64 |
| `KEYSTORE_PASSWORD` | رمز keystore |
| `KEY_ALIAS` | نام alias |
| `KEY_PASSWORD` | رمز کلید (همان رمز keystore) |

ساخت keystore در Termux:

```bash
pkg install openjdk-17 gh
cd ~/myworkgps
REPO=kavirkobra69/myworkgps bash tools/make-keystore.sh
```

اگر `gh` لاگین باشد، چهار Secret خودکار ثبت می‌شوند. وگرنه مقادیر چاپ می‌شوند و محتوای base64 در فایل `.b64` ذخیره می‌شود. فایل `.jks` را در جای امن نگه دارید. اگر گم شود، نسخه‌های بعدی روی نسخه قبلی نصب نمی‌شوند.

اگر این Secretها را ثبت نکنید، workflow یک کلید موقت می‌سازد و ریلیز را **Pre-release** با هشدار علامت می‌زند. APK امضا شده است، ولی برای آپدیت باید نسخه قبلی را حذف کنید.

## مرحله ۴: ساخت APK

1. GitHub ← تب **Actions** ← **Build and Release APK** ← **Run workflow**.
2. workflow این کارها را انجام می‌دهد: تست‌های واحد، ساخت release، امضا، بررسی امضا با `apksigner`، آپلود Artifact و ساخت/به‌روزرسانی Release.
3. بعد از پایان، APK در بخش **Releases** ریپو است: `SmartAttendance-vX.Y.Z.apk`.
4. شماره نسخه از ابتدای `app/build.gradle.kts` خوانده می‌شود (`appVersionName` و `appVersionCode`). برای نسخه جدید، هر دو را زیاد کنید.

## مرحله ۵: نصب و تنظیم

1. APK را نصب کنید (اجازه «نصب از منبع ناشناس» را بدهید).
2. ویزارد اولیه را کامل کنید:
   - نام، شناسه کارمندی (اختیاری)، نام محل کار
   - محل کار روی نقشه (جستجو، لمس نقشه، یا «Use my current location») و شعاع (۵۰ تا ۱۰۰۰ متر، پیش‌فرض ۱۰۰)
   - توکن GitHub، آدرس ریپو، توکن ربات تلگرام، Chat ID و «Send test message» (می‌توانید رد کنید و بعداً در Settings انجام دهید)
   - مجوزها: Precise location، **Allow all the time**، نوتیفیکیشن
3. در بخش Settings بررسی کنید همه وضعیت‌ها سبز باشند (GPS، Background location، Geofence، Telegram).

## پایداری و باتری

اندروید و سازنده‌ها در موارد زیر پایش پس‌زمینه را متوقف می‌کنند. راه‌حل‌ها هم داخل اپ (Settings ← Reliability guide) هست:

| مشکل | راه‌حل |
|---|---|
| بهینه‌سازی باتری (Xiaomi، Huawei، Oppo، Vivo، Samsung) | مصرف باتری اپ را Unrestricted کنید. Autostart را روشن کنید. اپ را در Recents قفل کنید. جزئیات هر برند: dontkillmyapp.com |
| Force stop | یک بار اپ را باز کنید تا پایش برگردد |
| مجوز Location روی «فقط هنگام استفاده» | Settings اپ ← Permissions ← Location ← Allow all the time |
| GPS خاموش | اندروید اجازه نمی‌دهد اپ بی‌صدا GPS را روشن کند. اپ هشدار می‌دهد. دکمه Open Location Settings را بزنید |
| خاموش/روشن شدن گوشی | اپ بعد از boot خودش دوباره geofence را ثبت می‌کند. اگر سازنده auto-start را بسته، بعد از restart یک بار اپ را باز کنید |
| نبودن Google Play services | geofence کار نمی‌کند. اپ هر ۱۵ دقیقه موقعیت را بررسی می‌کند (دقت کمتر) |

## نحوه کار تشخیص ورود

- ثبت geofence با Google Play services (مصرف باتری کم، بدون درخواست مداوم GPS).
- هنگام ورود، یک سرویس foreground کوتاه با نوتیفیکیشن، موقعیت را راستی‌آزمایی می‌کند (دقت، فاصله).
- زمان ورود از رویداد موقعیت گرفته می‌شود، نه از لحظه رسیدن نوتیفیکیشن. منطقه زمانی دستگاه همراه رکورد ذخیره می‌شود.
- جلوگیری از تکرار: هر بازدید یک رکورد با شناسه یکتا دارد، تا خروج از محل رکورد دیگری ساخته نمی‌شود، و بعد از خروج یک بازه کوتاه «cooldown» برای جلوگیری از نوسان GPS اعمال می‌شود.
- پیام‌های ارسال‌نشده در صف (Room) می‌مانند و با WorkManager و backoff دوباره تلاش می‌شوند. هر رویداد یک `event_id` دارد و فقط یک بار workflow را اجرا می‌کند.
- یک watchdog هر ۱۵ دقیقه وضعیت مجوزها، GPS و geofence را بررسی و ترمیم می‌کند.

## چک‌لیست تست دستی

- [ ] پروفایل و محل کار در ویزارد ذخیره می‌شود
- [ ] انتخاب محل روی نقشه و تغییر شعاع، دایره را روی نقشه نشان می‌دهد
- [ ] «Send test message» پیام را در تلگرام می‌رساند
- [ ] نزدیک شدن به محل کار → ورود ثبت و پیام ارسال می‌شود
- [ ] ماندن در محل کار، رکورد یا پیام تکراری نمی‌سازد
- [ ] خروج و بازگشت (بعد از cooldown) رکورد جدید می‌سازد
- [ ] با بستن اپ و حذف از Recents هم تشخیص کار می‌کند
- [ ] بعد از restart گوشی پایش برمی‌گردد
- [ ] خاموش کردن GPS هشدار و دکمه تنظیمات نشان می‌دهد
- [ ] قطع اینترنت → پیام در صف می‌ماند و بعد از اتصال ارسال می‌شود
- [ ] History رکوردها را نشان می‌دهد و خروجی CSV می‌دهد

> برای تست بدون رفتن به محل کار: در Developer options یک Mock location app انتخاب کنید یا شعاع و محل را موقتاً روی نقطه فعلی خودتان بگذارید.

## حریم خصوصی

داده‌های حضور فقط روی گوشی ذخیره می‌شوند. فقط متن پیام حضور با workflow ریپوی شما به چت تلگرام شما می‌رود. نقشه از سرورهای OpenStreetMap و جستجوی آدرس از Nominatim استفاده می‌کند. © OpenStreetMap contributors
