# Smart Attendance

اپ اندروید ثبت حضور خودکار با GPS. وقتی به محل کار می‌رسید، ورود شما با زمان دقیق ثبت می‌شود و پیام حضور به ربات تلگرام شما می‌رود. نیازی به زدن دکمه نیست.

- Android (Kotlin، Jetpack Compose)، Geofencing با Google Play services، Room، WorkManager
- نقشه OpenStreetMap (osmdroid): بدون API key و بدون هزینه
- ارسال تلگرام از طریق Cloudflare Worker (توکن ربات فقط داخل Worker نگه‌داری می‌شود)
- ساخت و انتشار APK امضاشده با GitHub Actions

## تغییرات نسخه ۱.۱.۰

- **کلید پایش پس‌زمینه:** روشن = ثبت geofence (بدون GPS مداوم) که با بستن اپ و بعد از ریبوت ادامه دارد. خاموش = لغو کامل geofence، watchdog و سرویس، و هیچ رویدادی پردازش نمی‌شود. هنگام روشن کردن، مجوزها و معافیت باتری درخواست می‌شود.
- **ورود:** پیش‌فرض ۱۰ ثانیه ماندن پیوسته داخل محدوده (loitering delay)، سپس فقط یک پیام. پیام بعدی فقط بعد از خروج و ورود دوباره.
- **زبان پیام:** English یا فارسی (اعداد بدون تغییر) و **فرمت تاریخ:** میلادی یا شمسی، در Settings.
- **راه‌اندازی راهنما‌دار تلگرام/Worker:** در Settings و ویزارد. توکن ربات و توکن Cloudflare را وارد می‌کنید؛ اپ Worker را روی حساب خودتان دیپلوی می‌کند، کلید دسترسی تصادفی می‌سازد و فقط URL و کلید را ذخیره می‌کند (توکن‌ها ذخیره نمی‌شوند). «Detect my chat» شناسه چت را پیدا می‌کند. «Send test message» چهار مرحله را جدا بررسی می‌کند: Worker، کلید، ربات، چت.
- **آپدیت داخل برنامه:** Settings ← Update App. از Releases ریپوی `kavirkobra69/myworkgps` نسخه جدید را بررسی می‌کند، changelog نشان می‌دهد، APK را دانلود می‌کند، SHA-256، نام پکیج، versionCode، minSdk و امضا را چک می‌کند و نصب‌کننده سیستم را باز می‌کند. برای آپدیت روی نسخه قبلی باید با همان keystore امضا شده باشد، و برای هر نسخه جدید `appVersionName` و `appVersionCode` را زیاد کنید.
- **فیلترینگ:** اگر `workers.dev` در شبکه شما باز نمی‌شود، اپ خطای مشخص می‌دهد (با DNS-over-HTTPS هم تلاش می‌کند). در این حالت یک دامنه شخصی روی Worker وصل کنید و آن را در Worker URL بگذارید. بدون VPN بودن تضمین نمی‌شود و باید با «Send test message» روی شبکه خودتان تست شود.
- بدون Google Play services، فقط بررسی هر ۱۵ دقیقه (دقت و تأخیر کمتر).

## ساختار ریپو

| مسیر | محتوا |
|---|---|
| `app/` | کد اپ اندروید |
| `worker/` | کد Cloudflare Worker و `wrangler.toml` |
| `.github/workflows/build-release.yml` | ساخت APK و انتشار در Releases |
| `.github/workflows/deploy-worker.yml` | دیپلوی Worker از داخل GitHub |
| `tools/make-keystore.sh` | ساخت keystore امضا (برای Termux یا هر لینوکس) |

## مرحله ۱: ساخت ربات تلگرام

1. در تلگرام با `@BotFather` چت کنید و `/newbot` بزنید. نام و username ربات را بدهید.
2. توکن ربات را کپی کنید (شبیه `123456:ABC...`). آن را فقط در GitHub Secrets یا Cloudflare بگذارید. در کد، چت یا اپ نگذارید.
3. به ربات خودتان در تلگرام بروید و `/start` بزنید. بدون این کار ربات نمی‌تواند به شما پیام بدهد.
4. Chat ID را بگیرید. ساده‌ترین راه: با `@userinfobot` چت کنید و عدد `Id` را کپی کنید. برای گروه، ربات را عضو گروه کنید و Chat ID گروه (عدد منفی) را وارد کنید.

## مرحله ۲: دیپلوی Cloudflare Worker

### راه A: با GitHub Actions (پیشنهادی)

1. در cloudflare.com حساب بسازید. `Account ID` را از داشبورد (صفحه Workers & Pages) کپی کنید.
2. در Cloudflare: My Profile → API Tokens → Create Token → قالب **Edit Cloudflare Workers** را انتخاب کنید و توکن را کپی کنید.
3. یک رشته تصادفی طولانی به‌عنوان کلید دسترسی اپ بسازید (مثلاً ۴۰ حرف). این همان «Access key» داخل اپ است.
4. Secretهای جدول بخش بعد را در GitHub ثبت کنید.
5. در GitHub: تب **Actions** ← **Deploy Cloudflare Worker** ← **Run workflow**.
6. در لاگ، آدرس Worker چاپ می‌شود: `https://smart-attendance-relay.<subdomain>.workers.dev`. همین را در اپ وارد می‌کنید.

### راه B: با wrangler (در Termux یا کامپیوتر)

```bash
cd worker
npx wrangler login
npx wrangler deploy
npx wrangler secret put BOT_TOKEN
npx wrangler secret put APP_KEY
```

### تست Worker

```bash
curl -H "X-App-Key: YOUR_APP_KEY" https://YOUR-WORKER.workers.dev/health
```

پاسخ موفق: `{"ok":true,"bot":"your_bot_username"}`

### امنیت Worker

- توکن ربات فقط در Secret به نام `BOT_TOKEN` است و هیچ‌وقت در پاسخ یا لاگ نمی‌آید.
- هر درخواست باید هدر `X-App-Key` درست داشته باشد. مقایسه کلید زمان‌ثابت است.
- محدودیت نرخ: پیش‌فرض ۳۰ درخواست در دقیقه برای هر IP، و ۱۰ تلاش ناموفق در ۱۰ دقیقه.
- جلوگیری از پیام تکراری با `event_id` (هر ورود فقط یک بار ارسال می‌شود).
- اختیاری: متغیر `ALLOWED_CHAT_IDS` فقط به Chat IDهای مجاز اجازه ارسال می‌دهد.
- اختیاری ولی پیشنهادی: یک KV namespace (دستور آن در `worker/wrangler.toml` نوشته شده) برای محدودیت نرخ و جلوگیری از تکرار در همه دیتاسنترها.

> **نکته درباره فیلتر:** Worker فقط یک رله است و تضمین نمی‌کند در هر شبکه محدودشده‌ای کار کند. اگر آدرس `workers.dev` از شبکه شما باز نمی‌شود، یک دامنه شخصی روی Worker وصل کنید. حتماً قبل از استفاده، «Send test message» را در اپ بزنید.

## مرحله ۳: GitHub Secrets

مسیر: ریپو ← **Settings** ← **Secrets and variables** ← **Actions** ← **New repository secret**

**برای دیپلوی Worker:**

| Secret | مقدار |
|---|---|
| `CLOUDFLARE_API_TOKEN` | توکن Cloudflare (قالب Edit Cloudflare Workers) |
| `CLOUDFLARE_ACCOUNT_ID` | Account ID |
| `TELEGRAM_BOT_TOKEN` | توکن ربات از BotFather |
| `WORKER_APP_KEY` | کلید دسترسی اپ (رشته تصادفی) |

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
   - Chat ID، آدرس Worker، Access key، و «Send test message»
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
- پیام‌های ارسال‌نشده در صف (Room) می‌مانند و با WorkManager و backoff دوباره تلاش می‌شوند. `event_id` در Worker مانع پیام تکراری می‌شود.
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

داده‌های حضور فقط روی گوشی ذخیره می‌شوند. فقط متن پیام حضور از طریق Worker شما به چت تلگرام شما می‌رود. نقشه از سرورهای OpenStreetMap و جستجوی آدرس از Nominatim استفاده می‌کند. © OpenStreetMap contributors
