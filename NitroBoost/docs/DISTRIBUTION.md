# 🌍 الطريق إلى النشر العالمي (Play Store / F-Droid)

الحالة اليوم: المستودع عام على GitHub، وAPK موقّع بتوقيع debug (يُثبَّت
مباشرة على أي جهاز). الخطوة التالية = توقيع release + رفع للمتجر.

## الخطوة 1 — توليد keystore (مرة واحدة، أنت فقط)

```bash
keytool -genkey -v -keystore nitroboost-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias nitroboost
```

> ⚠️ احفظ الملف وكلمتي المرور في مكان آمن خارج GitHub — لا توجد استعادة.

## الخطوة 2 — وضع السرية في GitHub Actions

`Settings → Secrets and variables → Actions → New repository secret`
(أو Variables):

| الاسم | القيمة |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 nitroboost-release.jks` (محتوى الملف كسطر) |
| `KEYSTORE_PASSWORD` | كلمة مرور المخزن |
| `KEY_ALIAS` | `nitroboost` |
| `KEY_PASSWORD` | كلمة مرور المفتاح |

`app/build.gradle.kts` مهيأ بالفعل: عند وجود `KEYSTORE_BASE64` يُنشأ
`signingConfig("release")` ويُربط بـ release build تلقائيًا.

## الخطوة 3 — بناء APK موقّع

```bash
KEYSTORE_BASE64=... KEYSTORE_PASSWORD=... KEY_ALIAS=nitroboost \
KEY_PASSWORD=... ./gradlew assembleRelease
# الناتج: app/build/outputs/apk/release/app-release.apk
```

أو أضف خطوة CI اختيارية:

```yaml
- name: Build signed release (if keystore provided)
  if: env.KEYSTORE_BASE64 != ''
  env:
    KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
    KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
    KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
    KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
  run: |
    echo "$KEYSTORE_BASE64" | base64 -d > release.jks
    KEYSTORE_FILE=release.jks ./gradlew assembleRelease --no-daemon
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
5. **62 اختبار JVM** في كل بناء.
