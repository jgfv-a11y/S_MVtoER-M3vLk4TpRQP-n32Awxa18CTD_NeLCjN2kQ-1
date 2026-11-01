# NitroBoost 1.23.0 — تدقيق قبل تطوير 1.24.0

**التاريخ:** 2026-11-01  
**النطاق:** Kotlin/Android، Gradle، Manifest، طبقات Root/Shizuku، المحرك التكيفي، القياس، الملفات الشخصية، Journal، الخدمات، وواجهة XML.

هذا التقرير هو baseline موثق قبل تغييرات 1.24.0. لا يعتبر أي بند «مشكلة» لمجرد غياب اختبار؛ البنود المصنفة مؤكدة لها مسار تنفيذ واضح في المصدر.

## ملخص الحالة

- الهوية الحالية: `com.nitroboost.app`، `minSdk=24`، `targetSdk=36`، `versionCode=30`، `versionName=1.23.0`.
- البنية: Kotlin + Android Views/Material، بلا Compose، مع 50 صف اختبار JVM وطبقات Core قابلة للاختبار عبر `FakeExecutor`.
- القياس: مصادر nullable مع timestamps في `PerformanceSnapshot`، وقراءة FPS/frame-times من `gfxinfo` عند توفرها فقط.
- الاستعادة: write-ahead Journal ذري مع dedupe وملكية قراءة حالية في v1.23.0.
- القيود: لا يوجد جهاز Android فعلي في بيئة التطوير؛ لا يمكن إثبات سلوك OEM أو توافق توقيع APK أو تحسن FPS ميدانيًا من اختبارات JVM وحدها.

## مشكلات مؤكدة

| الخطورة | الدليل | الأثر | خطة 1.24.0 | التحقق |
|---|---|---|---|---|
| حرجة | `ThermalOverrideTask.apply` ينفذ `cmd thermalservice override-status 0` عند تفعيل الملف | تعطيل/تجاوز حماية Android الحرارية مخالف لمعيار الإصدار، حتى مع وجود backstop حراري | تحويل المهمة إلى `REJECTED` دائمًا؛ إبقاء parser/restore للبيانات القديمة فقط حتى لا تضيع استعادة سجل سابق | اختبار يثبت عدم إرسال الأمر مع privileged وthermal sensor؛ فحص static يمنع الأمر الجديد |
| عالية | `BoostEngine.states` يستدعي `isSupported` لكل refresh، وبعض المهام تنفذ probes shell | ضغط Binder/shell متكرر، وبطء UI، ونتائج capability غير موحدة | Capability Registry بذاكرة قصيرة، حالة موحدة وسبب/وقت ملاحظة؛ لا تخفي عدم التوفر | اختبارات TTL، الحالات، وعدم تكرار probe |
| عالية | `HomeFragment.buildChips` ينشئ `ProfileStore` ويقرأ assets/ملف المستخدم أثناء عرض الشاشة | jank عند التنقل وإعادة بناء chips، خصوصًا مع تخزين كبير | cache ذري صغير في `ProfileStore` + تحميل profiles عبر lifecycle coroutine واحتفاظ بحالة loading | اختبار merge/cache، وStatic check يمنع القراءة المتزامنة من callback UI |
| عالية | `ProfileStore` يقرأ ملفات profile تالفة ويحولها بصمت إلى قائمة فارغة | قد يظن المستخدم أن profiles حُذفت؛ لا توجد حالة فساد/تعـارض قابلة للشرح | envelope versioned + نتيجة import/load صريحة (`valid`, `recovered`, `rejected`, `conflict`) مع checksum | اختبارات JSON تالف، version غير مدعوم، checksum، package conflict |
| متوسطة | `TaskState` يميز supported/pending فقط ولا يميز failed/rejected/applied بسبب واضح | الواجهة تعرض «غير مدعوم» لحالات permission/failure/conflict المختلفة | إضافة `CapabilityState` وreason/source timestamp مع الحفاظ على الحقول القديمة | اختبارات mapping وواجهة row state |
| متوسطة | الأرقام في Dashboard تعرض قيمة `MonitorSnapshot` دون provenance مختصر للمستخدم | يمكن أن تُفهم قيمة cached أو غير متاحة على أنها live | projection موحدة: value + source + freshness + confidence؛ إظهار `--` عند stale | اختبارات freshness وmissing-source، وفحص IDs الجديدة |
| متوسطة | `AdaptiveLoop.stopAndJoinBlocking` يستعمل `runBlocking` في مسار teardown | قد يطيل إيقاف الخدمة؛ لا يفسد القياس لكنه خطر latency | إبقاء cleanup غير قابل للإلغاء، مع منع التجارب المتزامنة وقفل lifecycle وcooldown صريح | اختبارات cancellation/rollback وقياس زمن cleanup في CI |
| متوسطة | `ThermalGuard` موجود لكنه يسمح ببقاء task thermal override في roster القديم | اختلاف بين سياسة الحماية الجديدة وملفات قديمة | task محجوز للتوافق فقط، لا apply، وrestore legacy entry مسموح | test legacy journal restore + no new override |
| منخفضة | `widget_info.updatePeriodMillis=60000` يخالف حد Android العملي ويهدر توقعات التحديث | لا يسبب crash؛ القيمة لا تعني تحديثًا كل دقيقة | ضبطه على قيمة محافظة وتوثيق أن widget tap هو التحديث الحقيقي | lint/manifest review |

## مشكلات محتملة تحتاج قياسًا ميدانيًا

1. توافق `specialUse` وخدمة foreground مع Android 14–16 وقيود OEM.
2. اختلاف مخرجات `cmd game` و`settings` و`thermalservice` بين ROMs؛ لا تستخدم نتيجة exit code وحدها دون read-back.
3. دقة `gfxinfo framestats` عند تبدل process epoch أو تغيّر اللعبة لمشهد workload مختلف.
4. استهلاك الطاقة الحقيقي غير متاح بلا عداد platform موثوق؛ لا يجوز اشتقاقه من FPS أو battery percentage.
5. Android 7/8 لمسارات timeout وBinder يتطلب جهازًا فعليًا؛ اختبارات API-level/static لا تثبت runtime OEM.
6. لا توجد أداة LeakCanary في المشروع؛ لا يمكن إعلان عدم وجود memory leaks من JVM tests وحدها.

## مخاطر التوافق مع بيانات 1.23.0

- يحتفظ `Journal` بصيغته الحالية ويجب أن يظل قادرًا على قراءة entries القديمة. أي entry من `thermal_override` يسمح له بالاستعادة فقط، ولا يسمح بإنشاء entry جديد.
- يحتفظ `profiles_custom.json` القديم بالحقول الحالية؛ الحقول الجديدة optional، والقيم غير الصالحة تعود إلى neutral defaults بدل رفض كل الملف.
- يحتفظ `adaptive_ledger.json` بمفاتيح v1.23؛ أي decision قديم يُعامل stale عندما يتغير `taskRevision` أو `objectivePolicyRevision`.
- لا يجوز تغيير `applicationId` أو `minSdk` أو مفتاح التوقيع. لا يمكن إثبات update compatibility حتى تتوفر APK موقعة بالمفتاح السابق.

## معايير قبول 1.24.0

- لا يرسل التطبيق أمرًا لتعطيل أو تجاوز thermal throttling.
- لكل task حالة capability موحدة وسبب قابل للعرض.
- لا تُستعاد قيمة يملكها طرف خارجي؛ الفشل أو فساد Journal يوقف الاستعادة العمياء.
- profile import/export versioned وchecksum-checked ويعزل الحزم ويكشف التعارض.
- Dashboard لا يعرض measurement غير متاح أو stale كبيان حي.
- اختبارات Core وPython وstatic/security gates و`lintDebug` و`assembleDebug` ناجحة في CI.
- لا يعلن التقرير تحسن FPS/Frame Time أو توافق توقيع دون قياس/تحقق فعلي.
