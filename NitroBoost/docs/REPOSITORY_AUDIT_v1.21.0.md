# تدقيق المستودع وإصدار NitroBoost v1.21.0

**التاريخ:** 2026-10-16

**النطاق:** مشروع Android، الشيفرة الرئيسية والاختبارات والموارد وManifest، مسارات الامتياز والاستعادة والحرارة، CI/CD، التوثيق والملفات المنشورة.

## الملخص التنفيذي

أُنجزت مراجعة إصدار مركزة على السلامة وصحة القياس وجاهزية Android 16. النتيجة الأساسية هي اكتشاف وتصحيح خلل حقيقي في تطبيع قراءات thermal zones: كان التطبيق يفترض أن كل OEM يعرض milli-Celsius، بينما تعرض بعض الأنظمة Celsius مباشرة. قراءة `44` كانت تصبح `0.044°C`، ما قد يمنع raw thermal floor من التدخل في الوقت الصحيح. الإصلاح الجديد يدعم الصيغتين ويستبعد القيم غير المحدودة أو غير المعقولة.

رُفع التطبيق من API 34 إلى API 36، مع تحديث أداة البناء وإدارة insets الإلزامية في Android 15/16. كما شُدد مسار النشر ليفحص هوية APK وإصدارها وtarget SDK وتوقيعها، وينشر SHA-256 بجانبها.

## ما تمت مراجعته

- `AndroidManifest.xml`: الأذونات، المكونات المصدّرة، foreground-service declarations، backup وcleartext.
- محرك `BoostEngine` و`Journal` و`JournaledSystemExecutor`: الكتابة قبل التغيير، الاستعادة العكسية، الاحتفاظ بالفشل، وتقييد أوامر الاستعادة.
- `ThermalGuard` و`ThermalSampler` ومراقب الخدمة: raw floor، فقدان المستشعر أثناء override، والتدرج الحراري.
- `AndroidExecutor` وShizuku/root: التحقق من المدخلات، timeout وحدود stdout/stderr، وترتيب fallback.
- القياس: FPS/framestats، freshness، null semantics، session reports وadaptive evidence.
- دورة `BoosterService` وoverlay وboot/tile/widget، وإبقاء الاستعادة قيد إعادة المحاولة.
- Gradle والتبعيات المثبتة وGitHub Actions وصلاحيات الوظائف وعزل أسرار التوقيع.
- الفحوصات الساكنة، أسرار ظاهرة، الملفات الكبيرة/الثنائية، واتساق README/CHANGELOG/التوزيع.

## النتائج والإجراءات

### 1. مرتفع — تطبيع thermal zones غير متوافق مع بعض OEMs (أُصلح)

- **قبل:** قسمة كل قيمة صحيحة على `1000.0`.
- **الأثر:** قراءة Celsius مباشرة قد تظهر أبرد بألف مرة؛ وهي مشكلة سلامة وصحة telemetry.
- **بعد:** `TemperatureMath.normalizeThermalZoneCelsius` يقبل Celsius وmilli-Celsius، يتحقق من finite/range، ولا يصنع قيمة عند غياب المصدر.
- **التغطية:** اختبارات milli-Celsius وCelsius والقيم الحدية والتالفة وغير الفيزيائية.

### 2. مرتفع للتوزيع — target SDK قديم (أُغلق برمجيًا)

- `compileSdk=36` و`targetSdk=36`، `versionName=1.21.0` و`versionCode=28`.
- AGP 8.13.2، Kotlin 2.2.20، Gradle 8.14.3، Android Build Tools 36.0.0.
- أضيفت معالجة `systemBars + displayCutout` بدل الاعتماد على سلوك layout القديم.
- **المتبقي:** اختبار أجهزة/محاكيات API 26–36 وROMs مختلفة قبل متجر Play.

### 3. متوسط — التحقق من artifact كان يعتمد على نجاح Gradle فقط (أُصلح)

CI يتحقق الآن من:

- package name و`versionCode`/`versionName` عبر `aapt2`؛
- `targetSdkVersion=36`؛
- صحة توقيع debug أو release عبر `apksigner`؛
- عدم وجود `debuggable` في release الموقعة؛
- SHA-256 منشورة بجانب كل APK؛
- بقاء البناء بلا صلاحية كتابة، والتوقيع معزولًا عن Gradle، والنشر من `main` فقط.

### 4. متوسط — APK عامة قديمة داخل Git (أُصلح)

كان `dist/nitroboost-debug.apk` لا يطابق بصمة أحدث Release منشور، ولا يحمل إصدارًا في اسمه. حُذف النسخ الثنائي القديم من Git لمنع تثبيت artifact مبهمة، وأصبحت `dist/README.md` تشير إلى Release الموثقة وملف SHA-256. أضيف ignore لملفات APK/checksum المولدة؛ قناة النشر الرسمية هي GitHub Releases المرتبطة بتشغيل CI.

### 5. صيانة سلسلة التوريد (أُنجز)

- تحديث `actions/upload-artifact` إلى v7.0.2 و`actions/download-artifact` إلى v8.0.2 باستخدام SHA كامل.
- بقيت كل dependencies وActions مثبتة إلى إصدارات/commits صريحة؛ لا توجد dynamic versions.
- GitHub Dependabot security alerts غير مفعلة في إعداد المستودع، لذلك بوابة Python ليست بديلًا عن SCA خارجي.

## التحقق

- `tools/verify.py`: فحص XML/resources/Kotlin references.
- `tools/security_verify.py`: Manifest/dependencies/workflow guardrails.
- Python unit discovery: parsers وفحوصات الأمان.
- GitHub Actions: JVM tests، Android lint، debug/release assembly، وفحوصات APK.
- `git diff --check` وفحص الأسرار النصي والملفات الكبيرة.

نجحت بوابة commit المدمج على الفرع مرتين: تشغيل push رقم [38888622125](https://github.com/jgfv-a11y/S_MVtoER-M3vLk4TpRQP-n32Awxa18CTD_NeLCjN2kQ-1/actions/runs/38888622125) وتشغيل PR رقم [38888628920](https://github.com/jgfv-a11y/S_MVtoER-M3vLk4TpRQP-n32Awxa18CTD_NeLCjN2kQ-1/actions/runs/38888628920). يبقى تشغيل `main` هو بوابة بناء release ونشر APK وبصمتها.

## المخاطر المتبقية

1. لا يوجد جهاز Android فعلي في بيئة العمل؛ لم يُنفذ اختبار install/launch أو Shizuku/root أو دوران الشاشة/الحواف على API 36 فعليًا.
2. `QUERY_ALL_PACKAGES` يحتاج مبرر سياسة Google Play، حتى مع صحة Manifest تقنيًا.
3. لا توجد أسرار توقيع release مهيأة حاليًا بحسب آخر إصدار؛ لا ينبغي إنشاء مفتاح جديد لأنه سيكسر هوية التحديث. لذلك APK debug هي artifact الاختبار المتاحة ما لم تُرجع CI release موقعة.
4. تفاوت thermal zones وsysfs وأوامر OEM باقٍ؛ القيم غير المتاحة تظل `null`، والـoverride يفشل مغلقًا عند فقدان raw sensor.
5. لا benchmark ميداني يثبت زيادة FPS أو تقليل jank؛ هذا الإصدار تصحيح سلامة/توافق وليس ادعاء أداء.

## قرار الإصدار

يُسمح بالنشر بعد نجاح بوابات الفرع و`main` وبناء APK والتحقق من metadata/signature. أي فشل في JVM/lint/assemble أو mismatch في API/version يمنع النشر تلقائيًا.
