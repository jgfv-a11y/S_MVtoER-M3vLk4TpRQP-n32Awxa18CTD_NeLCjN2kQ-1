# تدقيق المستودع وتسليم Phase 1 — PerformanceStateEngine

**تاريخ التدقيق:** 2026-10-05
**النطاق:** مراجعة المستودع والوثائق أولًا، ثم Phase 1 فقط. لم تُنفّذ Phases 2–10 ولم يُستبدل `BoostEngine` أو `AdaptiveLoop` أو Telemetry.

> يصف هذا التقرير شجرة العمل الحالية، بما فيها hardening سابق كان موجودًا قبل Phase 1. لا تُنسب تلك التغييرات السابقة إلى هذا التسليم. تقرير `ARCHITECTURE_AUDIT_v1.13.0.md` مرجع تاريخي، وليس إثباتًا لبناء شجرة العمل الحالية.

## 1. نتائج التدقيق قبل التعديل

| المجال | الموجود المؤكد | الفجوة/القيد الذي يجب إبقاؤه واضحًا |
|---|---|---|
| `BoostEngine` والمهام | مهام مستقلة، فحص capability/privilege، نتائج لكل مهمة، ومسارات استعادة حرارية. | `RamTrimTask` و`RamKillTask` آثار أحادية الاتجاه، وليستا تعديلات RAM قابلة للتراجع. `AppStore.honorLedger()` يعيد تطبيق الفائز قبل تسجيله في Journal، ما يترك نافذة فشل تستحق معالجة منفصلة. |
| Journal والـrollback | حفظ JSON ذري مع fsync ونسخة `.bak`، استعادة عكسية، تحقق قراءة لبعض الكتابات، وقائمة سماح لأوامر الاستعادة. | اختبار التدوير كان يتوقع تفريغ التراجعات عند الحد، خلاف ضمان الاحتفاظ بها. عُدّل الاختبار في Phase 1 ليثبت بقاء الإدخالات الحية والأرشيف؛ **لم يُشغّل** لغياب JVM/Gradle. `restoreAll()` يفشل مغلقًا عند حجب حالة تحميل الدفتر، ما يمنع الكتابة لكنه قد يؤخر الاستعادة. |
| المحرك التكيفي | `AdaptiveLoop` بتجارب baseline/candidate، بوابات صلاحية، سجل دائم، سياق/TTL، وتحليل frametime/thermal. | `BottleneckDetector` القديم يستنتج GPU بالاستبعاد عند هبوط FPS ولا يقدم confidence/evidence. ما زال الكود legacy، لكن Phase 1 أزال استعماله من العرض الحي وإنشاء تقارير الجلسة الجديدة. كما أن مجال وقت `AdaptiveSample` الافتراضي (`nanoTime`) يختلف عن `elapsedRealtime` الذي يمرره monitor؛ يحتاج تدقيقًا مستقلًا. |
| الحرارة | `ThermalGuard` غير قابل للتعطيل، يجمع Android status وأعلى thermal-zone مقروءة، و`ThermalTrend` محدود التاريخ؛ السلامة تسبق الأداء. | أعلى thermal-zone ليست بالضرورة حرارة CPU/SoC؛ قد تكون منطقة أخرى بحسب الجهاز/ROM. غياب القياس لا يعني صفرًا. محرك Phase 1 تشخيصي ولا يغيّر سياسة الحماية. |
| telemetry وSensorFusion | `PerformanceSnapshot` nullable مع timestamps لكل مصدر؛ CPU/per-core، ذاكرة النظام، حرارة، probe شبكي، FPS/frametimes/IntendedVsync، وبيانات الشاشة/الجهاز. | لا يوجد GPU utilization/clock موثوق موصول في المنتج. CPU وRAM نظاميان وليسا قياسًا لعملية اللعبة. `energyMah` غير متاح. الحقول المسطحة القديمة قد تحتفظ بأصفار ملتبسة، بخلاف snapshot nullable ومؤشر التوفر. |
| FPS وframe pacing وvsync | `FpsSampler` يقرأ `gfxinfo` وعداد الإطارات وفواصل IntendedVsync؛ `FramePacingAnalyzer` يحسب إحصاءات/jank ومعدل missed-vsync تقديريًا عند توفر الهدف. | المصدر اختياري ويتأثر بدعم ROM. لا يوجد عداد عام موثوق لـGPU dropped-frames؛ missed-vsync تقدير من الفواصل وليس عدادًا مباشرًا. هدف FPS مضبوط لا يثبت أن اللعبة طلبته. |
| الشبكة | `NetSampler` يقيس زمن اتصال TCP إلى `1.1.1.1:53` ويقرأ تغير TCP retransmissions للنظام. | probe ليس ping أو مسار خادم اللعبة. retransmissions عامة وليست packet loss. لا يوجد packet-loss counter/denominator؛ لذلك `packetLossPct` يبقى `null`. يمكن وصف تباين قياسات الاتصال بأنه probe variability فقط. |
| profiles واكتشاف الألعاب | 17 profile مدمجًا؛ profiles مخصصة تتحقق وتُحفظ ذريًا؛ اكتشاف resumed/paused عبر `UsageEvents` يحتاج Usage Access. توجد قائمة حماية من الإنهاء. | `ProfileStore` قد يحول خطأ JSON إلى قائمة فارغة ويخفي تلف profile. `installedGames()` يتيح تطبيقات المستخدم وليس اكتشافًا دلاليًا للألعاب. Game Space قائمة حزم OEM معروفة ولا تتحكم بتلك التطبيقات. |
| Shizuku/الامتيازات | تفضيل Shizuku ثم root عند التوفر؛ فحص صلاحية للمهام privileged؛ تحقق من package/path/value وأوامر الاستعادة. | `ShizukuShell.ensureBound()` غير متزامن، ومسارات bind/reconnect/unbind/run لا تُسلسل بقفل واحد في كل الحالات؛ تستحق اختبار تزامن مستقلًا. |
| ADPF وFrame Generation | لا إحالات أو تطبيقات لـ`PerformanceHintManager`/ADPF أو Frame Generation في مصدر التطبيق والاختبارات. المحرك التكيفي محلي وحتمي وليس ML/cloud. | لا تعرض هذه القدرات بوصفها ميزات موجودة. |
| الواجهة والوثائق والتقارير | Home يعرض مؤشرات النظام ومسبار TCP ولوحة adaptive وتقرير جلسة؛ README وCHANGELOG ومرجع معماري موجودة. | وُجدت صياغات توحي بأن Game API يضمن حرارة أقل/إطارات أثبت، وأن Ping يمثل اللعبة، كما عُرض GPU بالاستبعاد. Phase 1 صحح الصياغات، وسم مؤشرات الاتصال كمسبار TCP، وأوقف عرض التصنيف القديم؛ لا يوجد اختبار جهاز يثبت أثرًا عامًا. |

## 2. مصادر القياس المقبولة وحدودها

- **CPU:** `utilizationPct` و`perCoreUtilizationPct` المؤرخان؛ حمل نظامي لا حمل اللعبة. تردد السياسة وحده ليس دليل اختناق.
- **GPU:** لا تصنيف `GPU_BOUND` دون قياس مباشر حديث ومتكرر مع أعراض frame pacing. المصدر غير المتاح يبقى null/UNAVAILABLE.
- **الذاكرة:** ضغط Android/`availableBytes` مقارنة بحد متاح إشارات نظامية؛ `usedPct` وحده ليس دليل ضغط مؤكدًا ولا ذاكرة اللعبة.
- **الحرارة:** `osStatus` و`temperatureC` و`slopeCPerMin`/`effectiveStatus` بأوقات مصادرها؛ أعلى thermal-zone ليست تسمية مؤكدة للمعالج.
- **الإطارات والشاشة:** FPS وframe times وفواصل IntendedVsync المقاسة، هدف FPS، ومعدل التحديث الحالي. لا اختلاق FPS أو عداد dropped frames، ولا افتراض دعم أوضاع غير مقروءة.
- **الشبكة:** latency/variability لمسبار TCP وretransmissions على مستوى النظام فقط؛ ليست قياسًا لشبكة اللعبة. فقد الحزم غير متاح.
- **البطارية والجهاز:** بيانات سياقية لا تثبت استهلاك الطاقة أو سبب اختناق بذاتها.

## 3. خطة Phase 1 المنفذة

1. إضافة محرك Kotlin خالص فوق `PerformanceSnapshot` الحالي مع نافذة bounded، إزالة تكرار العينات حسب timestamp، وفصل history عند تغير الحزمة/process epoch أو رجوع الوقت.
2. حساب ضغوط متعددة الحدود للمصادر المتاحة وframe pacing/network-probe variability، من دون إضافة sampler أو امتياز جديد أو تحويل retransmissions إلى packet loss.
3. تصنيف الحالات التسع: `CPU_BOUND`, `GPU_BOUND`, `MEMORY_BOUND`, `THERMAL_BOUND`, `NETWORK_BOUND`, `DISPLAY_BOUND`, `MIXED_BOUND`, `HEALTHY`, `UNKNOWN`. لا يستنتج GPU من FPS. تصنيف NETWORK موسوم بنطاق TCP/system، وDISPLAY يحتاج قياس refresh/cadence، وHEALTHY يحتاج قياس أداء كافيًا عند هدف مضبوط.
4. إرجاع `PerformanceState`, confidence score/level (دعم معاير وليس احتمالًا إحصائيًا)، أدلة ذات رموز/قيم/وحدات/عدد عينات/وقت مصدر/نطاق، وقت النتيجة، `DataQuality` ومصادرها وتحذيراتها، وضغوط/تشخيصات إضافية.
5. ربط النتيجة بـ`AppStore` ولوحة Home فقط؛ لا تغير `BoostEngine` أو سياسة adaptive أو مستوى التعزيز.
6. إضافة اختبارات JVM حتمية وتحديث الوثائق/الادعاءات ذات الصلة. **الكود والاختبارات لم تُثبت compilation بعد**؛ التفاصيل في القسم 5.

## 4. ما أُضيف أو عُدّل في Phase 1

- `app/src/main/java/com/nitroboost/app/core/performance/PerformanceStateEngine.kt`: المحرك، نماذج الحالة والثقة والأدلة وجودة البيانات والضغوط، تجميع تاريخ محدود، freshness، جودة المصادر، قواعد التصنيف، وتحذيرات عدم توفر telemetry. packet loss يظل null، ومصدر GPU الغائب لا يتحول إلى صفر أو استنتاج.
- `app/src/main/java/com/nitroboost/app/AppStore.kt`: تحليل snapshots ضمن دورة المراقبة، تمرير process/game context، إتاحة النتيجة للواجهة، وإعادة ضبط history عند بدء/توقف المراقبة. أزيلت قيمة Bottleneck القديمة من `AdaptiveUi` ولم تعد تقارير الجلسة الجديدة تنشئ هذا الاستنتاج.
- `app/src/main/java/com/nitroboost/app/ui/HomeFragment.kt` و`app/src/main/res/layout/fragment_home.xml`: عرض الحالة والثقة وجودة البيانات وملخص الأدلة. يتضمن ملخص الدليل نطاق القياس وعدد العينات؛ يوضح TCP أنه ليس تدفق اللعبة. عُدّل العرض دون إزالة ملاحظة thermal prediction الموجودة.
- `app/src/main/res/values/strings.xml` و`values-ar/strings.xml`: تسميات الحالة/الدليل والنطاق بالإنجليزية والعربية.
- `app/src/test/java/com/nitroboost/app/core/PerformanceStateEngineTest.kt`: 14 اختبارًا للتصنيفات، غياب GPU وpacket loss، stale data، process epoch، الأداء الصحي، ودمج CPU/thermal.
- `app/src/test/java/com/nitroboost/app/core/JournalTest.kt`: توقع تدوير Journal يثبت بقاء rollback entries والأرشيف بدل افتراض تفريغ السجل.
- `README.md` و`GameApiTask.kt`: توضيح أن Game API طلب platform وليس ضمان أثر، وأن probe الشبكة ليس latency للعبة، وأن العمليات الأحادية الاتجاه لا توصف بأنها قابلة للتراجع. أُبقي كود `BottleneckDetector` القديم دون استعماله في العرض الجديد.

## 5. التحقق والقيود الحالية

- **لم تُشغّل** `./gradlew test`, `./gradlew lint`, أو `./gradlew assembleDebug`: البيئة لا تحتوي Java أو Gradle ولا يوجد `./gradlew`. لا يُدّعى نجاح compile/build أو regression.
- نتيجة CI المنشورة سابقًا تخص v1.13.0 ولا تثبت شجرة العمل الحالية. لا يوجد جهاز فعلي متصل لاختبار Shizuku أو مصادر OEM.
- بعد تعديلات Phase 1، نجح `python3 tools/verify.py`: 43 XML، 123 Kotlin، 105 IDs، و0 أخطاء؛ ونجح `git diff --check`. هذه فحوص static/resource فقط وليست بديلًا عن Kotlin/Android build.
- تعديل اختبار الثقة الصحية إلى `HIGH` مبني على مكونات درجة الثقة الحالية، لكنه غير مؤكد بتشغيل JVM. وكذلك اختبار Journal الجديد.
- لا يبدأ أي Phase لاحق قبل تشغيل الاختبارات/build عند توفر بيئة مناسبة ومراجعة أي إخفاقات.

## 6. متابعة التدقيق — خارج Phase 1

وقت كتابة هذا التدقيق كانت هذه البنود خارج النطاق: write-ahead موحد قبل إعادة تطبيق الفائز في `honorLedger`؛ التحقق من حدود الذاكرة الكلية لقوائم frame؛ تطبيع مجال timestamps في مسار AdaptiveSample؛ معالجة فشل/تزامن Shizuku binding؛ إظهار خطأ JSON في profiles بدل إخفائه؛ فصل الآثار غير القابلة للعكس مثل trim/force-stop بوضوح؛ واختبار الأجهزة الفعلية لمصادر gfxinfo/thermal/OEM. لا توجد إضافة ADPF أو GPU sampler أو packet-loss telemetry.

## 7. متابعة لاحقة — write-ahead للعمليات القابلة للعكس

- أُضيف `JournaledSystemExecutor` task-scoped لتسجيل الاستعادة ذريًا قبل تغييرات settings/sysfs/DND وأوامر النظام المعروفة القابلة للعكس. إذا تعذر حفظ سجل الاستعادة، لا يُنفذ التغيير؛ وإذا فشل التحقق بعد الكتابة يبقى سجل الاستعادة لمحاولة rollback.
- مسار التعزيز العادي، تجارب Adaptive، وإعادة تطبيق الفائز في `honorLedger` تستخدم `BoostEngine.applyWithJournal`؛ تبقى عمليات trim/force-stop أحادية الاتجاه خارج هذا الضمان.
- أضيفت اختبارات JVM لترتيب write-ahead في settings/sysfs وللفشل المغلق عند تعذر حفظ الدفتر. يلزم نجاح CI ومراجعة سلوك OEM الفعلي قبل اعتبار التنفيذ متحققًا على الأجهزة.
