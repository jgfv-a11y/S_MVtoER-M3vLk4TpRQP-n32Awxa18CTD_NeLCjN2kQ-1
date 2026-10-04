# تدقيق معماري — NitroBoost 1.13.0

**الحالة:** اكتمل التدقيق والتنفيذ الموثق أدناه؛ نجح verifier البنيوي محليًا، بينما ينتظر build والاختبارات تأكيد GitHub Actions.
**النطاق المنفذ:** المرحلة 1 (التدقيق)، والمرحلة 2 (`core/telemetry/`)، والمرحلة 3 (تحليل Frame Pacing)، دون استبدال المحرك التكيفي أو تغيير وصفات التعزيز.

## 1. خريطة النظام الحالي

| المسار | المسؤولية الحالية | قرار التنفيذ |
|---|---|---|
| `core/BoostEngine.kt` و`core/tasks/AllTasks.kt` | تشغيل قائمة المهام مع فحوص privilege ومستوى التعزيز، تسجيل التغييرات الناجحة، واستعادة التغييرات الحرارية | خارج التعديل الوظيفي لهذه المراحل |
| `core/Journal.kt` | دفتر تغييرات دائم، استعادة عكسية، وإبقاء الإدخالات التي فشلت استعادتها | يبقى مصدر rollback كما هو |
| `core/ThermalGuard.kt` و`core/adaptive/ThermalTrend.kt` | حد حراري خام غير قابل للتعطيل وتصعيد استباقي لاتجاه الحرارة | موجود بالفعل؛ لا ننشئ منطقًا حراريًا موازيًا |
| `platform/Monitor.kt` و`platform/MonitorHub.kt` | عينات CPU/RAM/الحرارة/البطارية/الشبكة، ولقطة دورية كل ثانية | نقطة الربط مع snapshot الموحد مع إبقاء الحقول المسطحة المتوافقة |
| `platform/FpsSampler.kt` و`core/adaptive/FrameTimeAnalysis.kt` | FPS من `gfxinfo` عند توافره، وأزمنة إطارات `FrameCompleted - IntendedVsync` مقاسة فعليًا | نضيف فواصل IntendedVsync المقاسة ونبني التحليل عليها |
| `core/adaptive/AdaptiveLoop.kt`, `AdaptivePolicy.kt`, `DecisionLedger.kt` | نوافذ A/B مقترنة، بوابات جودة، تعويض تعدد المقارنات، وتحليل قائم على القياسات المتاحة | نمدّ العينات بالقياسات الجديدة مع الحفاظ على الأوزان والقرارات الحالية |
| `AppStore.kt` | توصيل المراقبة بحلقة A/B والحرارة وواجهة التطبيق | يحتفظ بتكامل `MonitorSnapshot` الحالي؛ لا تغيير سلوكي للتجارب |

## 2. الموجود المؤكد قبل التنفيذ

- `FrameTimeAnalysis` يحسب median وP95/P99 والتباين ومعدل الإطارات المتعثرة من أزمنة إطارات حقيقية؛ ولا ينشئ frametimes عند غياب `gfxinfo`.
- حلقة A/B تستخدم بالفعل متوسط/ذيل FPS، وثبات frametimes، ومعدل hitches في تقييمها. `DecisionLedger` يحفظ نوافذ القياس، لذا أي إضافة محفوظة إليه يجب أن تكون اختيارية ومتوافقة مع السجلات القديمة.
- `ThermalTrend` و`ThermalGuard` يقدمان اتجاهًا حراريًا وحدودًا خامًا؛ يجب أن تبقى الحماية أعلى أولوية من الأداء.
- `BottleneckDetector` قائم، لكنه يصنّف GPU بالاستبعاد ولا يعيد confidence؛ تحسينه مرحلة منفصلة ولا يدخل في هذا النطاق.
- توجد 36 ملف اختبار JVM قبل بدء التنفيذ. إعداد التطبيق: `compileSdk 34`, `minSdk 26`, `targetSdk 34`, Java 17 وKotlin 1.9.24؛ الإصدار عند التدقيق `1.12.0` / `versionCode 15`.

## 3. فجوات ودقة القياس

1. `MonitorSnapshot` يضع أصفارًا افتراضية لـCPU/RAM/البطارية، فلا يمكن دائمًا التمييز بين قراءة حقيقية تساوي صفرًا وقراءة غير متاحة. يلزم تمرير حالة التوفر إلى طبقة telemetry الجديدة، مع إبقاء الحقول القديمة للواجهة.
2. FPS وframetimes اختياريان بالفعل. parser لا يحتفظ حاليًا بفواصل IntendedVsync بين الإطارات؛ لذلك لا يوجد أساس موثوق لمعدل الإطارات الساقطة بعد.
3. لا يوجد مصدر موثوق حاليًا لقياس GPU utilization أو renderer. CPU frequency ليس حقلاً في `MonitorSnapshot`؛ يمكن تجربته قراءةً فقط من cpufreq sysfs المتاح دون privilege، وإلا يبقى `null` مع حالة المصدر.
4. الذاكرة المتاحة من `ActivityManager` تمثل استخدام/ضغط ذاكرة النظام، وليست قياسًا مباشرًا لذاكرة اللعبة. تُوسم حقولها وفق معناها الفعلي، ولا تُعرض كذاكرة عملية اللعبة.
5. حالة الحرارة من `PowerManager` قد تكون غير مدعومة، وقراءة thermal zones قد تفشل حسب ROM والصلاحيات؛ `null` تعني عدم التوفر، لا درجة صفر.
6. ping الحالي اتصال TCP اختياري إلى endpoint ثابت. فشله أو غياب الاتصال يجب أن يبقى `null`، ولا توجد حاجة لخدمة سحابية أو inference عبر الشبكة.

## 4. حدود الأمان والتوافق غير القابلة للتفاوض

- لا تغيير على `BoostEngine` أو مهام `AllTasks` أو قوائم الأوامر allow-list أو فحوص privilege.
- لا تغيير على ترتيب Journal/rollback أو Shizuku أو سياسة A/B أو حد مستويات التعزيز.
- لا تعطيل أو تخفيف لـ`ThermalGuard`؛ كل مسار الأداء يظل خاضعًا له.
- البيانات محلية؛ لا نموذج AI كبير أو رفع قياسات أو جمع بيانات شخصية.
- كل قيمة غير مقاسة تبقى `null`. لا ادعاء بتحسن FPS دون benchmark على جهاز فعلي.

## 5. تصميم التنفيذ المقترح

- إضافة نماذج Kotlin خالصة في `core/telemetry/` لـ`PerformanceSnapshot` وCPU/GPU/memory/thermal/battery/network/frame/device. ستبقى متوافقة مع JVM ولا تعتمد على Android.
- إضافة `SensorFusion` لتطبيع وتوحيد القيم الواردة من مصادر Android الحالية، وربط الناتج بلقطة المراقبة دون إزالة خصائصها القديمة. يتضمن `PerformanceSnapshot` وقت الالتقاط وأوقاتًا مستقلة لكل مصدر حتى لا تظهر القياسات المخزنة مؤقتًا (مثل البطارية والحرارة والشبكة) كأنها قُرئت في اللحظة نفسها.
- CPU policy frequency يُقرأ best-effort من sysfs العام read-only عند إمكان القراءة. GPU utilization/renderer التي لا تملك مصدرًا موثوقًا تبقى `null` مع حالة غير متاحة؛ بيانات الجهاز الثابتة تُقرأ فقط من واجهات Android العامة المتاحة، وبقية البيانات `null`.
- إضافة IntendedVsync intervals من الصفوف الحقيقية في `gfxinfo`، مع reset عند تغير الحزمة/عداد العملية. لا يُحسب dropped-frame rate إلا بوجود فواصل مقاسة وهدف FPS صالح.
- إضافة `FramePacingAnalyzer` لإحصاءات FPS المأخوذة من عينات FPS فقط، وإحصاءات frame-time من framestats فقط. يكون معدل الهبوط/الجَنَك والثبات والسلاسة قابلًا للتدقيق؛ عند غياب المدخلات المناسبة تبقى النتيجة `null`.
- تمرير تحليل frame pacing إلى نافذة القياس التكيفية باعتباره قياسًا إضافيًا، مع إبقاء سياسة القرار/الأوزان الحالية كما هي في هذه المرحلة. تكون إضافة السجل اختيارية ومتوافقة مع JSON القديم.

## 6. التحقق والإصدار

CI في `.github/workflows/build-apk.yml` يشغّل `tools/verify.py` ثم `test`, `lintDebug`, و`assembleDebug`، ويولّد release موقّعًا فقط عند وجود أسرار التوقيع. لا يوجد `gradlew` مشحون في المستودع، وبيئة العمل الحالية لا تحتوي Java؛ لذلك لا يمكن إعلان نجاح اختبار أو APK محلي. يلزم تشغيل التحقق عبر CI بعد التنفيذ، وفصل نتيجة البناء عن benchmark على جهاز فعلي.

الإصدار التالي المخطط له `1.13.0` / `versionCode 16`، ويُنشر APK عبر مسار CI بعد نجاح البناء. لا يثبت هذا التدقيق وحده صلاحية تغييرات الأداء على أي جهاز بعينه.

## 7. متابعة التنفيذ بعد التدقيق

- أضيفت `core/telemetry/TelemetryModels.kt`, `SensorFusion.kt` و`IntendedVsyncIntervalTracker.kt`. أصبحت قراءة CPU/RAM/البطارية موسومة بالتوفر بدل اعتبار fallback `0` قياسًا صالحًا. CPU frequency تُقرأ فقط من سياسات cpufreq القابلة للقراءة؛ GPU utilization/clock/model والـrenderer تبقى null مع سبب عدم التوفر. بيانات SoC/الشاشة تُقرأ محليًا من `Build` وواجهات Display العامة.
- `PerformanceSnapshot` مرفق بـ`MonitorSnapshot` مع الحفاظ على حقوله القديمة. `ThermalSnapshot` يُغنى باتجاه `ThermalTrend` فقط عند وجود قراءة حرارة حالية، و`ThermalGuard` لم يتغير.
- `FramePacingAnalyzer` يعيد استخدام `FrameTimeAnalysis.distribution/summarize`: FPS mean/median من قيم FPS الحقيقية؛ percentile/variance من frametimes المقاسة. الـjank هو تعريف النظام الحالي `> 2 × frame budget`. dropped rate اسمه `estimatedDroppedFrameRate` ويحسب missed slots من IntendedVsync gaps مقارنةً بهدف FPS؛ لا يظهر دون الفواصل والهدف.
- درجتا stability/smoothness ضمن `[0,100]` وتُحجبان حتى خمس frametimes مقاسة على الأقل. Stability يطبّع معامل اختلاف frame-time على 0.50 بوزن 0.50، ويضيف jank/missed-vsync بوزني 0.25/0.25 عند توافرهما. Smoothness يطبّع تجاوز P95/P99 للميزانية (من الميزانية إلى ضعفيها) بأوزان 0.40/0.30، ثم jank/drop بوزني 0.20/0.10؛ يعاد تطبيع الأوزان عند غياب مكوّن. هذه diagnostics لا تغيّر `AdaptivePolicy`.
- أضيفت نتائج Frame Pacing اختيارية إلى `WindowMetrics` ودفتر القرار، مع parser يقبل عدم وجود الحقل في السجلات القديمة. زادت اختبارات JVM من 36 إلى 39 ملفًا.
- `python3 tools/verify.py` نجح محليًا بعد التنفيذ: **43 XML، 120 Kotlin، 104 IDs، 0 errors**. لم تنجح/تُشغّل أوامر Gradle محليًا لأن `gradlew` غير موجود (وJava غير مثبتة)؛ لم يُبنَ APK محليًا. يلزم اعتماد CI قبل اعتبار v1.13.0 منشورًا.
