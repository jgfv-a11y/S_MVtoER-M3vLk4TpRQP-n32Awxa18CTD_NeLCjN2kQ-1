# NitroBoost — Autonomous Development Progress

## Current Phase
**Phase 4 — القياس التكيفي والتحليل القابل للتدقيق**

## Completed Phases
- **P0 Safety:** rollback journal، الاستعادة بعد قتل العملية، الحماية الحرارية الصلبة، Shizuku/root fallback، bounded shell execution، منع استعادة أوامر غير موثوقة.
- **P1 Correctness:** اختبارات JVM للمحرك والدفتر والحرارة والاستعادة، quality-gated paired experiments، صلاحية القياسات، UsageEvents lifecycle، واستعادة فشل البدء.
- **P2 Performance Intelligence:** telemetry موحد، frame-time analysis، IntendedVsync tracking، frame pacing diagnostics، stability/smoothness scores، thermal trend، ومراقبة الطلب.
- **P3 Learning foundations:** context-bound decision ledger، objective identity، evidence windows، confidence/quality gates، وfamily-wise correction للـsweeps.
- **P4 Hardware compatibility:** profile validation، display policy preservation، RAM/process safety، bounded outputs، CPU frequency best-effort، null semantics للقدرات غير المتاحة.
- **P5 UI foundations:** dashboard، profiles، settings، overlay، reports، state-preserving navigation، ورسائل capability الصادقة.

## Current Task
إصدار v1.22.0: دعم Android 7.0 / API 24 مع الإبقاء على `targetSdk=36`، وتصحيح نطاق مقارنة FPS لكل لعبة، ثم اجتياز CI ونشر APK مع SHA-256.

## Completed This Session — 2026-10-04
- راجعت `main` وتقارير التقدم؛ لم يكن ملف الاستمرارية موجودًا.
- اكتشفت وسوم v1.10.0 وv1.11.0 وv1.12.0 وv1.13.0 وفرع التطوير المقابل.
- راجعت `ARCHITECTURE_AUDIT_v1.13.0.md` ونتيجة CI الموثقة.
- دمجت الفرع إلى `main` باستخدام fast-forward فقط حتى commit `8d63452`.
- أصبح الإصدار الحالي `1.13.0` و`versionCode=16`.
- التحقق الساكن المحلي: `43 XML`, `120 Kotlin`, `104 IDs`, `0 errors`.
- اختبار stress Python: `10 tests`, كلها ناجحة.
- CI v1.13 الموثق نجح في `test`, `lintDebug`, و`assembleDebug` ونشر APK.


## v1.14.0 Work in This Session
- أضيف اكتشاف رجوع `FrameCompleted` عند إعادة تشغيل process/clock epoch دون انخفاض عداد الإطارات التراكمي.
- أضيفت إعادة parsing آمنة ومنع خلط الأدلة بين epochs.
- أصبح IntendedVsync tracker يعيد تأسيس epoch عند الطابع الزمني الراجع.
- أضيفت اختبارات regression للـparser والـtracker.
- الإصدار المستهدف `1.14.0` و`versionCode=17`.


## Completed This Session — 2026-10-05
- تم التأكد أن ScheduleTask اليومية موجودة ومفعلة يوميًا الساعة 08:00 بتوقيت `Africa/Cairo`؛ لم تُنشأ جدولة مكررة.
- أُصلحت `BoostFragment.kt`: استخدام `Prefs.taskEnabled`, احترام اختيار اللغة `en/ar/auto`, وإزالة imports/state غير المستخدمة.
- الفحص الساكن نجح: `43 XML`, `120 Kotlin`, `104 IDs`, `0 errors`; و`git diff --check` نجح.
- Gradle المحلي غير متاح لغياب Android SDK؛ CI هو بوابة `test`, `lintDebug`, و`assembleDebug`.


## Completion Update — 2026-10-05
- CI لإصلاح `BoostFragment.kt` نجح بالكامل على commit `bb914c3`: `test`, `lintDebug`, و`assembleDebug`.
- تمت مراجعة `MonitorHub.kt` و`SensorFusion.kt` بحثًا عن stale timestamps وnull-semantics؛ لم يظهر خلل مؤكد قابل للإصلاح دون جهاز Android، لذلك لم يُدخل تعديل تخميني.
- بقيت تغييرات الكود محصورة في إصلاح واجهة Boost، مع توثيق التشغيل فقط.


## Stability Review — 2026-10-06
- تمت مراجعة MonitorDemand، دورة AppStore monitor clients، MonitorHub start/stop، وfreshness في SensorFusion.
- لم يظهر خلل P0/P1 مؤكد؛ لم تُجرَ تغييرات تخمينية على telemetry.
- تم تجهيز maintenance release `1.14.1` و`versionCode=18` دون ميزات جديدة.
- CI نجح ونشر APK: `6,428,830 bytes`, SHA-256 `37ef8abbbb14fffcc3815e8654680eebbb49b950ba47c83a0b4cb2371886ba29`.
- Measurement unavailable: لا يوجد جهاز Android لقياسات أداء حقيقية.


## Stability Review — 2026-10-07
- تم دمج v1.15.0 الموجود مسبقًا على `origin/main` باستخدام fast-forward فقط؛ لم تتم إعادة تنفيذ تغييرات التشغيل الآلي.
- شملت المراجعة إضافات `JournaledSystemExecutor` و`PerformanceStateEngine` وsecurity regression gate وقيود workflow الأقل صلاحية.
- Static verifier نجح: `43 XML`, `126 Kotlin`, `107 IDs`, `0 errors`.
- Security verifier نجح: `12 security regression check groups`, `0 findings`، مع تحذيري Play policy المعروفين فقط.
- اختبار Python الكامل عبر discovery نجح: `25 tests`.
- CI و`assembleDebug` نجحا مسبقًا على commit `018ab36`، وAPK v1.15.0 منشور.
- فشل أمر unittest الأول بسبب تشغيله من جذر المستودع؛ إعادة التشغيل من `NitroBoost/` بالطريقة المطابقة لـCI نجحت، ولا يوجد خلل كودي.
- Measurement unavailable: لا يوجد جهاز Android فعلي لقياس FPS/CPU/RAM/الحرارة.


## P1 Rollback Fix — 2026-10-08
- اكتُشف خلل مؤكد في `Journal.restore`: حذف system/secure/global لم يكن يتحقق من اختفاء القيمة بعد نجاح delete.
- أُصلح التحقق بعد الحذف، وأضيف regression test لمنصة تبلغ نجاحًا كاذبًا.
- التحقق الساكن والأمني واختبارات Python المحلية نجحت؛ Gradle محجوب محليًا بغياب Android SDK.
- تم تجهيز v1.15.1/versionCode 20 دون إضافة ميزات.
- CI نجح ونشر APK: `6,527,642 bytes`, SHA-256 `df16d4f400de2b7226629ed8efeaba4ecf7d6ef333f7d43e759f6a7154b68f87`.


## P0 Thermal Safety Fix — 2026-10-09
- اكتُشفت فجوة: `ThermalGuard.rawStatusFor(null)` كان يعيد nominal حتى أثناء thermal override، رغم أن override يعتمد على raw sensor كخط حماية مستقل.
- أُضيف overload يرفع الحالة إلى critical عند فقدان المستشعر مع override نشط، واستُخدم في مسارات AppStore الحرارية.
- السلوك الطبيعي بدون override لم يتغير، وأضيف regression test.
- التحقق المحلي الساكن والأمني و25 اختبار Python نجح؛ Gradle محجوب محليًا.
- تم تجهيز v1.15.2/versionCode 21 دون ميزات.
- CI نجح ونشر APK: `6,528,014 bytes`, SHA-256 `5e3ea5359c7cbaba1fbf876fb601c010b6f914fef2f4baa0a1f66d31c278a25d`.


## v1.16.0 Measured Frame Intelligence — 2026-10-09
- استُخدم التحديث المرفق لاختيار تحسين محدود: إضافة 1% Low FPS إلى `FramePacingMetrics`.
- القيمة هي متوسط أبطأ 1% من عينات FPS الصحيحة فقط، وتبقى null قبل خمس عينات.
- أضيف regression test؛ لم تُضف ميزات privileged أو fake optimization أو frame generation.
- تم تجهيز versionCode 22.
- CI نجح ونشر APK: `6,528,594 bytes`, SHA-256 `f92d26e5369be519a7463187ebe95fc656df17f9176e00f3fbf1d4b224d4c606`.


## Stability Review — 2026-10-10
- تمت مراجعة Journal/rollback وBoostEngine restore/de-escalation وThermalGuard/ThermalOverride وAdaptiveLoop cancellation.
- لم يظهر خلل P0/P1 مؤكد؛ لا تغييرات كودية تخمينية.
- بوابات static/security/Python نجحت: 43 XML، 126 Kotlin، 107 IDs، 0 errors؛ 12 groups/0 findings؛ 25 tests.
- تم تجهيز v1.16.1/versionCode 23 كإصدار صيانة لتوفير APK جديد بعد المراجعة.
- CI نجح ونشر APK: `6,528,626 bytes`, SHA-256 `866fd619ff3ea98367a778a88cd58a0a0676090ed76a1b595348e6d42a9ba64b`.


## Proactive Product Fix — 2026-10-10
- اكتُشف أن `onePercentLowFps` من v1.16 لا يُحفظ في DecisionLedger، فتضيع القيمة بعد restart/reload.
- أُصلح serialization وparsing مع اختبار round-trip؛ ledgers القديمة تبقى متوافقة لأن الحقل اختياري.
- البوابات المحلية نجحت: static 0 errors، security 0 findings، Python 25 tests.
- تم تجهيز v1.17.0/versionCode 24.
- CI نجح ونشر APK: `6,528,554 bytes`, SHA-256 `7746f8c94c27f77c0ff159228d6678c1cc880b99da563516097c667b3c6c2d2b`.


## Proactive UI/Telemetry Integration — 2026-10-11
- اكتمل المسار المؤجل: `PerformanceStateResult.framePacing` أصبح يصل إلى `AdaptiveUi` ثم Home.
- تعرض Home 1% Low وP95/P99 وعدد العينات من telemetry الحقيقية فقط، مع empty-state عند غيابها.
- أضيف `FramePacingDisplay` واختبارات mapping؛ لا تغيير في safety أو automatic tuning.
- البوابات المحلية نجحت: static 0 errors، security 0 findings، Python 25 tests.
- نجح CI (test + lintDebug + assembleDebug) ونُشر APK v1.18.0/versionCode 25 باسم nitroboost-debug.apk.


## Persistent Session Frame Pacing — 2026-10-11
- أُكمل مسار القياس: آخر `FramePacingMetrics` موثوق يُحفظ في `SessionReport` بذاكرة O(1).
- JSON يحفظ ويستعيد الحقول كاملة، مع optional null-safe parsing للتقارير القديمة.
- Home يعرض 1% Low/P95/P99 في تقرير آخر جلسة.
- static/security/Python نجحت محليًا؛ Kotlin وAPK بانتظار CI.
- الإصدار المستهدف v1.19.0/versionCode 26.

## Repository Review and Android 16 Update (v1.21.0) — 2026-10-16
- راجعت بنية المشروع وManifest ومسارات Journal/rollback والحرارة وShizuku/root ودورة الخدمات والـworkflow والتبعيات المثبتة والملفات الثنائية والتوثيق.
- اكتُشف خلل مؤكد: `ThermalSampler` كان يقسم كل قراءة على 1000؛ بعض OEMs تعرض Celsius مباشرة، ما يحوّل `44` إلى `0.044°C` ويضعف raw thermal floor. أضيف تطبيع pure Kotlin واختبارات regression للقيم المباشرة والملي-درجة والقيم التالفة.
- رُفع `compileSdk` و`targetSdk` إلى 36، وأضيف تعامل edge-to-edge/cutout صريح، وحُدّث AGP/Kotlin/Gradle.
- حُدثت Actions الخاصة بالـartifacts إلى الإصدارات ذات الاختبارات الخضراء، وأضيف تحقق package/version/target/signature وبصمة SHA-256 قبل النشر.
- الإصدار المستهدف `v1.21.0` / `versionCode 28`؛ نجحت بوابتا push/PR للـcommit المدمج (runs 38888622125 و38888628920)، وبقي إطلاق `main`.

## Android 7 Compatibility and FPS Comparison Fix (v1.22.0) — 2026-10-17
- خُفّض `minSdk` إلى API 24 مع الإبقاء على `targetSdk=36`؛ استُبدلت عمليات `java.nio.file` بمساعد استبدال داخل المجلد نفسه، وحُرست notification channels عند API 26 وخدمة SPECIAL_USE عند API 34.
- أضيفت مقارنات FPS حسب اسم حزمة اللعبة، بمخزن محدود إلى 32 لعبة. التقارير التاريخية غير المرتبطة بحزمة لا تعرض delta؛ والنص يوضح أن الفارق وصفي وليس نتيجة A/B سببية.
- أُضيفت اختبارات Kotlin لتخزين/عزل baselines واستبدال الملفات، وفحوص ثابتة لحد API والحراسات؛ كما حُدثت وثائق التوزيع والمراجعة العكسية.
- نجحت محليًا `tools/verify.py` (134 Kotlin، صفر أخطاء)، و`security_verify.py` (12 مجموعة، صفر findings؛ تحذير Play المعتاد عن QUERY_ALL_PACKAGES)، و25 اختبار Python، و`git diff --check`.
- **لم يُشغّل Kotlin/Gradle/lint/build محليًا** لغياب Java وGradle وAndroid SDK. لم تُنشأ APK أو Release بعد؛ يلزم CI على الفرع ثم إطلاق `main`، واختبار جهاز API 24 فعلي غير متاح.

## Architecture Decisions
- القياسات غير المتاحة تبقى `null` ولا تُحوّل إلى أصفار مضللة.
- لا يتم الادعاء بتحسن FPS دون benchmark على جهاز فعلي.
- `Journal` و`ThermalGuard` وprivilege gates أعلى أولوية من الأداء.
- telemetry وframe pacing تشخيصيان ولا يغيران أوزان AdaptivePolicy تلقائيًا في هذه المرحلة.
- لا cloud inference، ولا نموذج AI كبير داخل APK، ولا رفع بيانات شخصية.
- لا يتم دمج ملفات APK أو reference app كمصدر كود.

## Blocked Tasks
- **Device benchmark/integration test:** يحتاج جهاز Android فعليًا مع Usage Access وShizuku أو root؛ غير متاح في Sandbox.
- **Signed release APK:** يحتاج أسرار keystore غير المتوفرة؛ debug APK فقط منشور.
- **Local Gradle build:** Android SDK غير موجود في Sandbox الحالية؛ CI هو بوابة البناء.

## Known Risks / Remaining Problems
- لا يوجد benchmark فعلي يثبت تحسن الأداء أو frame pacing على أجهزة مختلفة.
- GPU utilization/renderer والطاقة تبقى unavailable عندما لا يوفرها النظام.
- الاختلافات بين OEM وROM قد تؤثر على أوامر settings/deviceidle/game.
- تحتاج جلسة لاحقة إلى مراجعة regression مركزة على telemetry وMonitorHub بعد الدمج الكبير.

## Next Automatic Task
1. commit ودفع v1.22.0 إلى فرع Arena الثابت، ثم فتح PR وانتظار بوابات `test` و`lintDebug` و`assembleDebug`.
2. إذا نجح CI، دمج PR بالـmerge ومتابعة Workflow على `main` حتى إنشاء Release `nitroboost-v1.22.0` والتحقق من APK وبيانات API 24/36 وبصمة SHA-256.
3. إذا فشل Gradle أو lint، أصلح السبب وأعد CI قبل الدمج/النشر.
4. عند توفر جهاز، اختبار التثبيت والإطلاق وShizuku/overlay والاستعادة على Android 7/API 24، ثم توسيع مصفوفة API 24–36.

## Overall Progress
- **Phase:** 4 — القياس التكيفي والتحليل القابل للتدقيق
- **Task:** v1.22.0 Android 7/API 24 and per-game session comparison
- **Completion:** التنفيذ والفحوصات الساكنة وPython اكتملت؛ Kotlin/Android CI وPR وRelease لم تُنفذ بعد. لا توجد APK جديدة أو تجربة جهاز فعلي حتى الآن.
