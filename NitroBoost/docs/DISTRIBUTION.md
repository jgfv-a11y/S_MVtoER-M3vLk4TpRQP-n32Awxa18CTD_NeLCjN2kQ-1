# 🌍 الطريق إلى النشر العالمي (Play Store / F-Droid)

الحالة الحالية: المستودع عام على GitHub، وCI يبني APK debug للاختبار؛ توقيع release اختياري ويتطلب الأسرار أدناه. الإطلاق عبر GitHub Release يتم من `main` فقط بعد نجاح بوابات الاختبار والبناء. هذا ليس إثباتًا لجاهزية Google Play؛ راجع متطلبات `targetSdk` واختبارات Android 16 قبل التوزيع على المتجر.

## الخطوة 1 — توليد keystore (مرة واحدة، أنت فقط)

```bash
keytool -genkey -v -keystore nitroboost-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias nitroboost
```

> ⚠️ احفظ الملف وكلمتي المرور في مكان آمن خارج GitHub — لا توجد استعادة.

## الخطوة 2 — وضع السرية في GitHub Actions

`Settings → Secrets and variables → Actions → New repository secret`:

| الاسم | القيمة |
|---|---|
| `NITRO_KEYSTORE_BASE64` | `base64 -w0 nitroboost-release.jks` (محتوى الملف كسطر) |
| `NITRO_KEYSTORE_PASSWORD` | كلمة مرور المخزن |
| `NITRO_KEY_ALIAS` | `nitroboost` |
| `NITRO_KEY_PASSWORD` | كلمة مرور المفتاح |

يبني CI ملف release غير موقّع دون أسرار، ثم يستخدم `apksigner` في وظيفة منفصلة لتوقيعه عند وجود الأسرار الأربعة كلها؛ لا تمر مفاتيح التوقيع إلى Gradle. إذا غابت كلها ينشر CI ملف debug للاختبار فقط، وإذا كانت الأسرار جزئية يفشل النشر بدل تجاهل التهيئة. يمكن للتوقيع المحلي عبر Gradle استخدام متغيرات البيئة الموضحة أدناه.

## الخطوة 3 — ما يفعله CI الحالي

1. يبني ويختبر الـAPK في وظائف ذات `contents: read`، دون أسرار التوقيع.
2. يُبنى release غير موقّع في وظيفة read-only بلا أسرار؛ وظيفة `sign-release` مستقلة تشغّل `apksigner` فقط وتستقبل أسرار keystore دون تشغيل Gradle/build scripts.
3. وظيفة `publish-release` وحدها تملك `contents: write`، وتعمل من `main` بعد نجاح build/verification؛ لا تستقبل أسرار التوقيع.
4. **حارس إلزامي**: أي release APK يحتوي `debuggable=true` يُسقط النشر (فحص `aapt2 dump badging`).
5. تُرفق ملفات APK الناتجة في GitHub Release؛ لا تُدفع artifacts أو سجلات الفشل إلى فروع ثانوية. تفاصيل البوابات في `../../docs/GITHUB_ACTIONS_SETUP.md`.
6. فحص الأمان الآلي الحالي regression gate محدود ولا يعني اجتياز MASTG أو وجود شهادة. ولا توجد نتيجة benchmark ميداني مرفقة بهذا البناء.

للبني محليًا:

```bash
KEYSTORE_BASE64=... KEYSTORE_FILE=nitroboost-release.jks \
KEYSTORE_PASSWORD=... KEY_ALIAS=nitroboost \
KEY_PASSWORD=... ./gradlew assembleRelease
# الناتج: app/build/outputs/apk/release/app-release.apk
```

## الخطوة 4 — المتجر

### Google Play (الحساب 25$ مرة واحدة)
- [Play Console](https://play.google.com/console) → تطبيق جديد
  (`com.nitroboost.app`).
- مطلوب: سياسة خصوصية (صفحة مستضافة)، نموذج Data Safety
  (لا جمع بيانات — التطبيق محلي بالكامل، وهذا أقوى نقطة تسويقية)،
  صور شاشة + أيقونة 512px.
- الأمان: التطبيق يطلب Shizuku — في وصف Data Safety اذكر صراحة
  "لا نجمع أي بيانات؛ كل شيء يُعالج على الجهاز".
- المراجعة تأخذ عادةً أيامًا؛ التطبيق يعمل أثناء المراجعة.

### F-Droid (مجاني، مناسب جدًا لهذا التطبيق)
- شيفرة مفتوحة ✓ (ميتيكا Apache-2.0)، لا تبعيات مغلقة
  (Shizuku مفتوح ✓)، لا تتبع ✓.
- أنشئ [PR في f-droid/f-droiddata](https://github.com/f-droid/f-droiddata)
  بوصفة بناء:
  ```
  RepoType: git
  GitRepo: https://github.com/jgfv-a11y/S_MVtoER-M3vLk4TpRQP-n32Awxa18CTD_NeLCjN2kQ-1.git
  GitTagFormat: nitroboost-v%v
  Build:
    - gradle: NitroBoost
  ```
- المراجعة البشرية تستغرق أسابيع — ابدأ مبكرًا.

## ما يجعل هذا التطبيق "قويًا" في المتاجر

1. **لا جمع بيانات إطلاقًا** (كل شيء محلي) — نادر في فئة المعززات.
2. **شفافية كاملة**: الدفتر (`journal.json`) + دفتر قرارات المحرك
   (`adaptive_ledger.json`) قابلان للفحص من المستخدم.
3. **أمان حراري فوق الأداء** — تصميم معلَن ومختبر.
4. **عربي + إنجليزي** من أول إصدار.
5. **اختبارات JVM** في كل بناء.
