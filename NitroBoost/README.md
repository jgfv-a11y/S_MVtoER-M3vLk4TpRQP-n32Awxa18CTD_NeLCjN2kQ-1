# 🚀 NitroBoost — Game Booster


**أحدث إصدار منشور: v1.15.2 (versionCode 21)**؛ راجع [GitHub Releases](https://github.com/jgfv-a11y/S_MVtoER-M3vLk4TpRQP-n32Awxa18CTD_NeLCjN2kQ-1/releases) لتنزيله.
تطبيق معزّز ألعاب Android احترافي، عربي + إنجليزي، مبني بـ **Kotlin** و**Material 3**
(بدون Compose) على Shizuku API 13.1.5.

المبدأ الأساسي: **ضبط قابل للتخصيص مع استعادة موثقة للتغييرات القابلة للعكس** — يسجّل Journal
الكتابات المدعومة مع قيمة سابقة وأمر استعادة. بعض العمليات، مثل trim/force-stop،
أحادية الاتجاه ولا يمكن التراجع عن أثرها؛ وتظل النتائج معتمدة على الجهاز وقيود الـROM.

---

## ✨ المزايا

- **محرك تعزيز بمهام منفصلة** (DND، Game Mode، Governor، GPU Boost، الشاشة،
  Battery Saver، RAM Trim/Kill، Data Saver، Thermal Override، الحركات) —
  كل مهمة مستقلة: فشل واحدة لا يوقف البقية.
- **سجلّ تعديلات (Journal)**: يحفظ القيمة السابقة والجديدة وأمر الاستعادة
  للتغييرات القابلة للعكس في `filesDir/journal.json`؛ العمليات أحادية الاتجاه لا توصف بأنها قابلة للاستعادة.
- **استعادة تلقائية** عند خروج اللعبة + **استعادة يدوية** من أي مكان.
- **حماية تدرّجية من الحرارة (Thermal De-escalation)**: عند ارتفاع الحرارة
  يُراجع المحرك التعديلات بالترتيب (أقوى → أضعف) ويحفظ ما يمكن حفظه بدل
  إيقاف كل شيء.
- **FpsOverlay**: شريط فوق اللعبة يعرض FPS وCPU وRAM والحرارة وقياس اتصال TCP؛ هذا القياس لا يمثل زمن استجابة خادم اللعبة.
- **Game API (أندرويد 12+)**: يطلب عامل تخفيض رسم قدره 80% عبر Game Manager
  الرسمي (`cmd game`) — استجابة النظام/اللعبة والنتيجة الفعلية تختلفان حسب الجهاز؛
  يُسجَّل طلب التغيير ويُستعاد تلقائيًا (`cmd game reset`).
- **المحرك التكيفي A/B**: يقارن كل مرشح بخط أساس جديد قريب زمنيًا، يحتفظ
  بأدلة كل متغيّر وفاصل ثقته، ويصحح تعدد المقارنات قبل اختيار أي فائز.
  بوابة جودة الجلسة ترفض العينات القديمة أو المتغيرة حراريًا، والتجربة
  غير الحاسمة تبقى MORE_DATA. لا تُستخدم أزمنة الإطارات أو الطاقة إلا من
  مصدر قياس حقيقي؛ غياب المصدر يعني غياب ذلك المؤشر، لا تقديره من FPS.
  يشمل محرك Phase 1 لحالة الأداء بأدلة وثقة وجودة بيانات من المصادر المتاحة؛
  GPU لا يُصنّف بلا قياس مباشر، وقياس TCP ليس تدفق اللعبة. تبقى الحماية الحرارية
  التنبؤية ومسح الدقة (0.9/0.8/0.7) والمحكّم
  (performance/schedutil) وتقديرًا متبقيًا (ETA).
- **مستويات التضخيم (v1.5 Turbo Kit)**: 3 مستويات — أساسي (بدون
  صلاحيات) / قياسي / أقصى — مع 5 مهام تسريع جديدة: إبقاء كل الأنوية
  نشطة، جدول I/O منخفض التأخير، سطوع ذروة أثناء اللعب، إعفاء اللعبة من
  خمول النظام (Doze)، ووضع الأداء العالي الرسمي (Game API، أندرويد 12+).
- **Shizuku تلقائي بالكامل** + **نافذة منبثقة للصلاحيات** عند أول تشغيل.
- **كشف Game Space الخاص بالصانع** (OPlus، MI Game Turbo، سامسونج، HONOR،
  vivo…) مع نصيحة بتفعيل وضع لعبته أيضًا.
- **تقرير أداء بعد كل جلسة**: متوسط/أدنى FPS (gfxinfo الحقيقي)، ذروة
  الحرارة، أقل زمن لمسبار TCP (ليس خادم اللعبة)، ذروة RAM، والفارق عن الجلسة السابقة.
- **17 ملف ألعاب جاهز** (Genshin Global، PUBG، COD، Fortnite، Star Rail، Wild Rift، Roblox، Minecraft…) +
  **ملفات مخصصة** لكل لعبة (DPI، معدل تحديث، سقف FPS، DND، قتل الذاكرة…).
- **قائمة حماية** للعمليات الحساسة (يوتيوب، واتساب، المتصفح…) لا يلمسها القتل.
- **لوحة معلومات حية**: CPU، RAM، الحرارة، البطارية، FPS، ومسبار TCP + نقاط تعزيز.
- **إحصائيات RAM للعمليات** مع زر قتل فردي آمن.
- **إشعار خلفية دائم** بحالة الجلسة + استعادة فورية من الإشعار.
- **تفعيل تلقائي** عند بدء اللعبة (اختياري) عبر مراقبة نشاط النظام.
- **عربي + إنجليزي** كامل (RTL/LTR).

---

## Adaptive measurement and scoring

The adaptive engine measures deterministic paired baseline/candidate blocks using full quality-gated windows, alternating order (`baseline → candidate`, then `candidate → baseline`). Each attempt's order is persisted before measurement; accepted observations retain their order, timestamps, duration, and temporal separation. Incomplete, stale, unstable, thermally contaminated, or otherwise invalid blocks request `MORE_DATA` and add no statistical evidence. The game package, process epoch, thermal tier, monitor freshness, sample counts, sampling gaps, and measured FPS coefficient of variation must pass the existing quality gates. A candidate is reverted from the durable Journal after measurement unless a statistically supported `KEEP` is safely re-applied and finalized.

Paired interleaving reduces temporal and workload confounding, but it cannot prove that the game executed identical internal workload in both arms. It does not manufacture workload equivalence or claim device benchmark results.

Each measured sweep arm keeps its own baseline/candidate observations and confidence interval. The engine assesses every arm with a 95% family-wise confidence target using Bonferroni correction (`alpha / numberOfArms`) before ranking any eligible `KEEP` arms. It does not calculate an interval only for the post-hoc winner. Resolved local decisions expire after 30 days and are bound to a local hashed device/capability key, Android/app revision, game/profile, boost level, coarse thermal-temperature/slope bands, and objective-policy identity/revision.

The normalized block score is bounded to `[-1, 1]`. Weight profiles are explicit and normalized: `balanced-v1` uses performance/temperature/slope/tier weights `0.60/0.15/0.15/0.10`; the declared `thermal-cautious-v1` profile uses `0.50/0.20/0.20/0.10`. The latter is selected only when the verified effective thermal tier is LIGHT or higher and at least eight valid prior pairs exist for the same task/context (for a sweep, for every required arm). This is fixed policy/configuration, not learning from small samples. Invalid, non-finite, negative, out-of-range, or zero-total weight inputs fall back to the balanced defaults; each policy identity is isolated in the ledger/cache.

- Performance gain is the equal-weight mean of normalized average-FPS and lower-tail-FPS gains, plus any available real frame-time stability/hitch, system-memory-pressure, or energy-measurement gains. Each gain is clipped to `[-1, 1]`; an unavailable metric is omitted, never synthesized.
- Performance contributes its configured weight times gain and the thermal-headroom factor. The factor is clipped to `[0, 1]` over a six-degree headroom range below the 44 °C raw safety floor.
- Temperature, temperature-slope, and thermal-tier risks are subtracted using the active normalized profile. Missing temperature/slope metrics are omitted; the score is normalized over only applicable objective weights.
- A positive result is not enough by itself: the corrected confidence interval must clear the configured useful-effect threshold (`0.015`) and the sample-quality/thermal-safety gates.

Frame times are read only from `dumpsys gfxinfo ... framestats` when exposed by the device. Energy remains absent until a real platform counter is available. The v1.13 telemetry snapshot aligns the available CPU, memory, thermal, battery, network, frame, and public device/display readings; GPU utilization/renderer and any other unsupported source remain null. `FramePacingAnalyzer` calculates FPS mean/median from observed FPS samples, frame-time percentiles/variance from framestats, and an **estimated** missed-vsync rate only from measured IntendedVsync gaps plus a valid configured target. Stability/smoothness scores are deterministic diagnostics, not benchmark promises. No FPS gain, benchmark result, or device measurement is assumed by the model.

## 📦 المتطلبات

- Android 8.0 (API 26) أو أحدث — target SDK 34.
- **Android Studio** Koala (2024.1) أو أحدث + JDK 17 (مدمج في Android Studio).
- **Shizuku** (اختياري لكن ضروري للمزايا الكاملة):
  بدون Shizuku يعمل التطبيق على ما تسمحه صلاحيات النظام العامة (DND،
  الحركات، Battery Saver، بيانات الشبكة…) — ومع Shizuku تُفعل التعديلات
  العميقة (Governor، GPU، DPI، Thermal Override، قتل العمليات…).

### تثبيت Shizuku (خطوة بخطوة)

1. ثبّت [Shizuku](https://shizuku.rikka.app/) من GitHub.
2. شغّله بإحدى طريقتين:
   - **ADB**: `adb shell app_process -Djava.class.path=$(pm path rikka.app.enhancedmode | cut -d= -f2) rikka.app.enhancedmode.Init`
   - **الجذر**: إذا جهازك Magisk — شغّل Shizuku من وضع root مباشرة.
3. **NitroBoost يفعل الباقي تلقائيًا (v1.3.2)**: يفتح Shizuku ويعرض
   نافذة السماح ويربط الخدمة بنفسه عند التشغيل — فقط اضغط «السماح» عندما
   تظهر. الحالة ستظهر «جاهز ✅» في الإعدادات.

---

## 🛠️ البناء

### الطريقة الأسهل — Android Studio

1. `File → Open` واختر مجلد `NitroBoost/`.
2. انتظر اكتمال **Gradle Sync** (ينزّل التبعيات من Maven Central تلقائيًا).
3. اضغط **Run ▶** على جهاز حقيقي (المحاكاة لا تدعم Shizuku).

### من الطرفية (اختياري)

المشروع لا يحوي `gradle-wrapper.jar` (ملف ثنائي). مرّة واحدة فقط:

```bash
cd NitroBoost
gradle wrapper --gradle-version 8.7   # إذا كان Gradle 8.7 مثبتًا
./gradlew assembleDebug
./gradlew test                        # اختبارات JVM للنواة (المحرك، السجل، الملفات)
```

APK الناتج في `app/build/outputs/apk/debug/`.

> 📲 **APK منشورة (v1.15.2)**: [تنزيل nitroboost-debug.apk](https://github.com/jgfv-a11y/S_MVtoER-M3vLk4TpRQP-n32Awxa18CTD_NeLCjN2kQ-1/releases/download/nitroboost-v1.15.2/nitroboost-debug.apk) — نسخة debug للاختبار؛ هذا الإصدار لا يتضمن APK موقّعة للتوزيع.
> (أو من صفحة [Releases](https://github.com/jgfv-a11y/S_MVtoER-M3vLk4TpRQP-n32Awxa18CTD_NeLCjN2kQ-1/releases) —
> يُعاد بناؤه تلقائيًا عبر GitHub Actions عند كل تغيير).

---

## 📜 سجل الإصدارات

### v1.13.0 — telemetry موحّدة وتحليل Frame Pacing
- **الإصدار 16**؛ ينشر GitHub Actions APK بعد نجاح التحقق والاختبارات وlint والبناء. APK release موقّع يتطلب أسرار التوقيع.
- إضافة `PerformanceSnapshot` و`SensorFusion` في `core/telemetry/`؛ القراءات غير المتاحة تبقى `null` مع حالة المصدر بدل اعتبار الصفر قياسًا.
- دمج قراءات CPU/sysfs للـfrequency عندما تكون مقروءة دون امتيازات، ضغط الذاكرة النظامي، حرارة/تيار البطارية والبيانات الثابتة المتاحة عن الجهاز والشاشة. GPU utilization وrenderer والطاقة تبقى `null` دون مصدر موثوق.
- إضافة فواصل IntendedVsync من سجلات framestats الفعلية، وقياسات FPS mean/median وframe-time median/P95/P99/variance وjank. معدل الإطارات الساقطة **تقديري** من فجوات VSync المقاسة والهدف المحدد فقط.
- درجات الثبات والسلاسة مؤشرات تشخيصية حتمية ولا تظهر قبل خمس عينات frametime فعلية؛ لا تتغير أوزان أو قرارات محرك A/B في هذا الإصدار.
- اختبارات JVM جديدة للتطبيع وحالات عدم التوفر وفواصل VSync والتحليل وحفظ الدفتر؛ لا يوجد ادعاء benchmark أو FPS على جهاز فعلي دون قياس.
- تقرير التدقيق: `docs/ARCHITECTURE_AUDIT_v1.13.0.md`.

### v1.12.0 — حماية الإعدادات والاستعادة وتحسين كفاءة الجلسة
- **الإصدار 15**؛ ينشر GitHub Actions APK بعد نجاح الاختبارات والبناء.
- قيم الملف الشخصي تُتحقق وتُنقّى عند الحفظ والتحميل، مع حفظ ذري لملفات profiles المخصصة.
- التنظيف التلقائي يستثني اللعبة المحددة والحزم المحمية والتطبيقات ذات الأنشطة الظاهرة (عند توفر Usage Access)، ولا ينفذ force-stop دون امتيازات.
- معدل التحديث يبقى على سياسة الجهاز عند القيمة `0`؛ المعدلات الصريحة لا تتجاوز سقف اللوحة المبلّغ عنه، وتُستعاد كثافة/إعدادات العرض الأصلية بدقة. السطوع الأقصى صار خيار المستوى 3 بسبب كلفته على البطارية والحرارة.
- قراءة خرج الأوامر محدودة الذاكرة، ومدخلات الحزم ومسارات sysfs وأوامر الاستعادة المحفوظة تتحقق قبل التنفيذ.
- ملخص الجلسة يستخدم ذاكرة ثابتة؛ تحديث FPS المرئي يحتفظ بقراءة حديثة بين الاستطلاعات؛ المراقبة تتوقف عند عدم وجود مستهلك.
- أضيفت اختبارات JVM للتغييرات؛ لا ندّعي تحسن FPS على كل جهاز أو نتيجة benchmark دون قياس فعلي.

### v1.11.0 — تقوية التجارب التكيفية
- **الإصدار 14**: أوزان أهداف معلنة ومطبّعة، وهوية سياسة تمنع خلط الأدلة بين ملفات الأوزان.
- **قياس زوجي متداخل**: ترتيب حتمي متبادل بين baseline والمرشح، مع حفظ ترتيب المحاولة وبيانات الزمن والجودة؛ النوافذ غير الصالحة لا تضيف دليلًا.
- **سياسة حرارية معلنة وليست تعلّمًا**: لا تُستخدم إلا مع سياق حراري موثّق وثمانية أزواج صالحة على الأقل لكل ذراع مطلوب.
- التداخل يقلّل الالتباس الزمني وتغيّر workload لكنه **لا يثبت تطابق workload اللعبة داخليًا**؛ لا ندّعي نتائج benchmark على أجهزة.

### v1.9.0 — ثبات ملفات Adaptive وإدارة profiles
- **حفظ ذري لدفتر Adaptive** مع `fsync` قبل الاستبدال، حتى لا تضيع قرارات التجارب عند انقطاع التطبيق.
- **حذف profile آمن**: حذف profile المفعّل يختار تلقائيًا profile بديلًا بدل ترك التطبيق على حزمة غير موجودة.
- **تحديث توثيق الإصدار** وإزالة ملاحظات CI القديمة من وصف النشرة.

### v1.8.0 — مراجعة الاستعادة والتخزين والصلاحيات
- **حارس الاستعادة المستمر**: يظل فعالًا حتى عندما يبدأ التطبيق بدفتر فارغ، فيلتقط أي جلسة لاحقة تموت دون استعادة.
- **كشف Usage Stats صحيح**: لم يعد يعتمد خطأً على صلاحية الرسم فوق التطبيقات.
- **مطابقة Doze دقيقة**: لا تعتبر حزمة مشابهة حزمة اللعبة نفسها.
- **حفظ ذري لملفات التعريف المخصصة** مع `fsync` قبل الاستبدال لتقليل تلف الملف عند الانقطاع.

### v1.7.0 — ثبات، أمان حراري، واستعادة مضمونة
- **CI أخضر من جديد**: أرشيف الدفتر باسم `{filename}_{timestamp}` — كان هذا الاختبار الوحيد الأحمر منذ إضافة اختبارات التدوير.
- **استعادة عند قتل الخدمة**: `onDestroy` + حارس السجل القديم يعيدان كل تعديل متبقٍ؛ لا تبقى حوكمة/DND/DPI بعد موت العملية.
- **إعادة ربط شيزوكو مستخدمة فعليًا**: المنفّذ يعيد المحاولة قبل التراجع إلى الروت/الوضع الآمن.
- **رصد يعالج نفسه عند التوقف**: إعادة تشغيل المحور إذا توقف ورود العيّنات (>20 ث) لا فقط عند لقطة فارغة.
- **دفتر أقوى**: fsync قبل الاستبدال، نسخة `.bak`، وتجاهل نوع مجهول بدل مسح الملف كله.
- **ملفات ألعاب أكثر**: Genshin Global، Star Rail، COD Global، Free Fire MAX، Wild Rift، Roblox، Minecraft.
- **بدون نسخ احتياطي أعمى**: `allowBackup=false` حتى لا يُعاد تشغيل دفتر لا يطابق الجهاز.

### v1.5.1 — Hardening: استعادة مضمونة + حد حرارة لا يُتجاوَز + صلاحيات أدنى
*(رد مباشر على تقرير المراجعة الأمنية 2026-09-26)*
- **استعادة عند الموت**: لا تترك جلسة متوقفة (إعادة تشغيل، قتل العملية،
  إيقاف الخدمة) أي تعديل مطبَّقًا — حارس «سجل قديم» يعيد كل ما تبقى عند
  فتح التطبيق ويستمر بالمحاولة حتى جاهزية شيزوكو، وكتابة السجل ذرّية
  (لا يتلف عند القتل أثناء الكتابة).
- **حد حرارة صلب**: تجاوز التبريد (اختياري) كان يجعل حالة النظام «لا يوجد
  تقييد» فيعمي الحارس — الآن الحارس يقرأ حرارة المستشعرات مباشرة
  (أرضيات 44/48/52°م) ويأخذ الأعلى — حد لا تستطيع الواجهة ولا الملفات
  ولا التجاوز نفسه تعطيله.
- **صلاحيات أقل**: حُذفت `WAKE_LOCK` و`ACCESS_NETWORK_STATE` غير
  المستخدَمتين. `INTERNET` تستخدمها أداة اتصال TCP فقط — لا تتبع ولا
  سحابة؛ الملفات محلية بالكامل.
- **خط إنتاج release**: بناء موقّع اختياري عبر أسرار GitHub + حارس CI
  يُسقط البُني إذا ظهر `debuggable=true` في أي release APK.
- **نظافة المستودع**: تطبيق المرجع المفكك (RevBoost 1.8.1-debug) نُقل
  إلى `reference/` — لم يعد في الجذر حيث يمكن الخلط بينه وبين
  NitroBoost.

### v1.5.0 — Turbo Kit: مستويات التضخيم + 5 مهام تسريع جديدة
- **مُختار مستوى التضخيم (1/2/3)**: تحكّم بنفسك في شدة المحرك —
  1 = الأساسيات الآمنة (بدون صلاحيات)، 2 = الأداء القياسي (افتراضي)،
  3 = العدواني. «الأقصى» صار اختيارًا صريحًا لا افتراضيًا.
- **إبقاء كل أنوية المعالج نشطة**: يوقف توقيت الأنوية أثناء الجلسة
  فيزيل تقطيعات الاستيقاظ القصيرة (1–3 إطارات) عند ذروة الأحمال.
- **جدول I/O منخفض التأخير**: يحوّل تخزين البيانات إلى وضع `none`
  (mq direct) أثناء اللعب — قراءات الملفات الصغيرة (خرائط/مواد) أسرع
  استجابة؛ ثم يعيد الجدول السابق عند الخروج.
- **سطوع ذروة أثناء اللعب**: يثبّت السطوع على الأقصى ويوقف التلقائي
  للجلسة — لا تخفوت في المشاهد المظلمة. يعمل حتى بدون شيزوكو عند
  منح صلاحية WRITE_SETTINGS.
- **إعفاء اللعبة من خمول النظام (Doze)**: يضيف اللعبة لقائمة استثناء
  Device Idle — لا تقييد للشبكة أو المهام الخلفية أثناء التركيز على اللعبة.
- **وضع الأداء العالي (Game API، أندرويد 12+)**: يطلب من سياسات
  الجدولة/الحرارة الرسمية أولوية أداء كاملة للعبة عبر `cmd game`.
- **إصلاح خطأ**: الملفات المدمجة لم تكن تُفعّل وحدة GPU، فكان طلب
  Game API والمسح التكيّفي خلفه مطفيّين بصمت للّعب المدمجة. الآن يُفعّل
  طلب 80% افتراضيًا؛ قبول الطلب لا يثبت أن اللعبة طبقته أو أن الأداء تحسن.
- كل مهمة جديدة مسجَّلة (Journal) وقابلة للتكرار والاستعادة؛ وحجب
  الـROM يظهر «تجاوز» لا «فشل».

### v1.4.0 — واجهة جديدة كليًا + اتصال شيزوكو/روت تلقائي
- **واجهة جديدة كليًا**: 4 تبويبات بسيطة (الرئيسية / التحسينات / الملفات /
  الإعدادات)، زر تحسين واحد كبير، إحصائيات حية، وكل شيء على ضغطة واحدة.
- **آلة حالات شيزوكو**: التطبيق يعرف الآن بالظبط أين وصلت التهيئة
  (مثبّت / شغّال / مسموح / مربوط) ويعرض بطاقة إرشاد في الشاشة الرئيسية
  مع الإجراء الصحيح لكل حالة.
- **بديل الروت**: الأجهزة المجذورة بدون شيزوكو تعمل تلقائيًا عبر `su`
  (فحص حذر ومخزّن مؤقتًا، وكل الكتابة تسجَّل وتُستعاد كما كان).
- **حالات مهام صادقة**: «بانتظار شيزوكو» بدل «غير مدعوم» عندما لا يمكن
  التحقق من العتاد بعد (لغياب صلاحية).
- **المؤشر العائم**: يبدأ تلقائيًا مع الجلسة، نافذة صلاحية واحدة عند
  الغياب، وحماية من الانهيار بدون الصلاحية.
- **النقاط «الحالية / الممكنة»**: بدل صفر غامض قبل أول تحسين.
- **ملفات بلا تكرار**: الملف المخصص يحل مكان الملف المدمج للعبة نفسها.
- **أخف**: فحص الصلاحيات مخزّن مؤقتًا (2 ثوانٍ) — لا ضغط على binder مع
  كل تحديث للواجهة.

### v1.3.2 — محرك أسرع وأدق وأقوى + إعداد ذاتي
- **جلسات تكيفية أسرع ~2×**: نوافذ 12 ثانية، استقرار 2 ثانية، والـ
  **baseline يُقاس مرة واحدة في الجلسة ويُعاد استخدامه لكل مرشح** (يُعاد
  القياس فقط بعد 5 دقائق أو تغيّر الحالة الحرارية).
- **إحصاء أدق**: قصّ القيم الشاذة (أعلى/أدنى 10%) قبل فاصل الثقة 95%،
  مع محاذاة عيّنات FPS والحرارة زوجًا-بزوج لكل نبضة.
- **قرارات واعية بالحرارة**: أي «ربح» FPS يرفع حرارة المعالج درجة كاملة
  يُنزل إلى NEUTRAL — لا أرباح تُدفع بالتخثث (Throttling).
- **A/B لوضع المحكّم (Governor)**: اختبار `performance` مقابل `schedutil`
  تلقائيًا، والفائز (بمتغيّره الدقيق) يُستعاد في كل جلسة.
- **رصد 1Hz** + مركز رصد **يعالج نفسه** عند أي توقف + **تقدير متبقٍ (ETA)**
  حي أثناء العمل.
- **نافذة منبثقة للصلاحيات عند أول تشغيل** (مع طلب POST_NOTIFICATIONS
  الحقيقي على أندرويد 13+).
- **Shizuku تلقائي بالكامل**: التطبيق يفتح Shizuku ويعرض نافذة السماح ويربط
  الخدمة بنفسه — عند التشغيل وبعد كل عودة، بلا أي خطوة يدوية.
- **سجل الإعدادات يعمل الآن**: قائمة حيّة بأحدث 14 تعديلًا (القيمة القديمة
  ← الجديدة) تتجدد تلقائيًا.
- **تقرير الجلسة أغنى**: أدنى FPS وذروة RAM؛ لا يعرض تصنيف الاختناق القديم الذي كان يستنتج GPU بالاستبعاد.
- **تصحيح الشريط العائم**: الإحصائيات تتلوى بدل أن تختفي من الحافة، و`--`
  عند غياب مستشعر الحرارة.
- **حالات مهام صادقة**: الكتابة المحجوبة من النظام تُعرض «تخطي — الجهاز
  يحظرها» بدل «فشل».

### v1.3.0 — مسح الدقة الديناميكي (إصدار مستقر)
- **مسح مستويات تخفيف الدقة (0.9 / 0.8 / 0.7)**: المحرك يقيس كل مستوى
  قانوني مقابل baseline مشترك واحد على جهازك، ويبقي **الفائز** — تحكم حقيقي
  في الدقة الديناميكية لتطبيق خارجي.
- المستوى الفائز يُحفظ في دفتر القرارات ويُستعاد تلقائيًا في كل جلسة تالية.
- **إصدار عادي (مستقر)** — لم يعد تجريبيًا.
- تجهيز النشر العالمي: توقيع release اختياري عبر CI +
  [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md) (خارطة Play Store / F-Droid).
- 67 اختبار JVM في خط البناء.

### v1.2.0 — المحرك التكيفي
- **محرك تكيفي A/B كامل**: كل تعديل أداء يُختبر على جهازك الحقيقي
  (baseline مع الإرجاع مقابل applied)، ويُحتفظ فقط بما أثبتته الإحصاءات:
  فاصل ثقة 95% مرفق (paired t-test) على فروق FPS القياسية — لا "تحسينات"
  وهمية.
- **دفتر قرارات دائم** (`adaptive_ledger.json`): القرارات تتراكم عبر
  الجلسات حتى تتقارب القياسات (NEEDS_MORE → KEEP/DROP/NEUTRAL).
- **احترام القرار**: المهمة التي قُيس أثرها ضارًا (DROP) لا يُعاد تطبيقها
  أبدًا — لا تعارض حتى مع قياساتنا السابقة.
- **حالة أداء حيّة مع دليل**: CPU/GPU/ذاكرة/شبكة/حرارة/شاشة عند توافر
  القياسات الموثوقة؛ قد تبقى النتيجة UNKNOWN إذا كانت المصادر غير كافية.
- **هامش حراري تنبؤي**: ميل الحرارة قد يخفض المستوى قبل أن يقلب النظام
  حالته الحرارية — قبل ضياع الإطارات لا بعدها.
- بطاقة المحرك في اللوحة + مفتاح تفعيل في الإعدادات.
- 62 اختبار JVM في خط البناء (كانت 30).

### v1.1.0
- **Game API (أندرويد 12+)** — `cmd game set --downscale 0.8` عبر Game
  Manager الرسمي (مُدار بالكامل: يُفحص قبل التنفيذ، يُسجَّل، ويُستعاد
  بـ `cmd game reset`).
- **كشف تطبيقات Game Space** الخاصة بالصانع + تنبيه ذكي.
- **تقرير جلسة مقاس**: متوسط/أدنى FPS، ذروة حرارة، أقل زمن لمسبار TCP، ذروة RAM،
  والدلتا عن الجلسة السابقة (أول خطوة نحو محرك أ/ب قياس حقيقي).
- **16 مهمة تعزيز** (كانت 15): + `game_api_downscale`.
- 26 اختبار JVM في خط البناء (كانت 20).

### v1.0.0
- الأساس: 11 مهمة تعزيز + 4 مهام أضيفت لاحقًا في دورة ما قبل الإصدار
  (Doze whitelist، WALT، CPU floor، Touch boost) — سجلّ كامل، حماية
  حرارية تدرّجية، FpsOverlay، ملفات ألعاب جاهزة، عربي/إنجليزي.

> **ملاحظة**: لا يوجد شريط تقدم وهمي — كل عملية تُنفَّذ فعلًا وتُفحص
> نتيجتها، وما يفشل يُسجَّل فقط ولا يُحتسب في النقاط.

---

## 🧭 هيكل الكود

```
app/src/main/java/com/nitroboost/app/
├── NitroApp.kt                  # Application — التهيئة
├── AppStore.kt                  # الحالة العامة (جلسة، ملفات، سجل، منطق)
├── core/
│   ├── Model.kt                 # TaskIds، Modules، ShellResult، DndFilters
│   ├── BoostContext.kt          # لقطة بيانات لنبضة المحرك
│   ├── BoostEngine.kt           # المحرك: تنفيذ / استعادة / تدرّج حراري
│   ├── Journal.kt               # سجل التعديلات (JSON)
│   ├── AppProfile.kt            # موديل ملف اللعبة + تحقق المدخلات
│   ├── ThermalGuard.kt          # مصفوفة التدرّج الحراري
│   ├── ScoreEngine.kt           # حساب نقاط التعزيز
│   ├── CpuMath.kt / SnmpMath.kt # تحليل CPU وTCP retransmissions/القياسات الشبكية
│   ├── SystemExecutor.kt        # واجهة أوامر النظام (عزل الاختبار)
│   └── tasks/                   # 11 مهمة (DND، Game Mode، Governor، GPU،
│                                #  Display، RAM×2، Power، Network، Animations،
│                                #  Thermal Override، All)
├── data/
│   ├── Prefs.kt                 # التفضيلات
│   ├── ProfileStore.kt          # ملفات الألعاب (assets + مخصصة)
│   └── SessionLog.kt            # سجل الجلسة
├── platform/
│   ├── AndroidExecutor.kt       # التنفيذ الفعلي (Settings API + Shizuku)
│   ├── ShizukuShell.kt          # جسر Shizuku (UserService + AIDL)
│   ├── Monitor.kt / MonitorHub.kt # الرصد الحي (CPU/RAM/حرارة/بطارية)
│   └── FpsSampler.kt / NetSampler.kt  # عيّنتا FPS وTCP probe
├── service/
│   ├── BoosterService.kt        # خلفية: حلقة المحرك كل 5 ثوانٍ
│   ├── FpsOverlayService.kt     # شريط FPS فوق اللعبة
│   ├── QuickTileService.kt      # زر Quick Settings
│   ├── WidgetProvider.kt        # ويدجت سطح المكتب
│   ├── NitroUserService.kt      # خدمة Shizuku (تعمل بهوية shell)
│   └── BootReceiver.kt          # بدء تلقائي (اختياري)
├── ui/                          # الشاشات (findViewById، بدون Compose)
└── shizuku/INitroService.aidl   # واجهة AIDL لخدمة Shizuku
```

---

## 🛡️ نموذج الاستعادة وحدوده

1. **Journal للتغييرات القابلة للعكس**: يحفظ القيمة السابقة والجديدة وأمر
   الاستعادة في المسارات المدعومة؛ توجد مسارات لا تملك تسجيلًا موحدًا قبل الكتابة.
2. **استعادة عكسية**: تُستعاد الإدخالات المسجلة من الأحدث إلى الأقدم، وتبقى
   العناصر التي فشلت في الاستعادة ظاهرة لإعادة المحاولة.
3. **عمليات أحادية الاتجاه**: trim وforce-stop لا يمكن إعادة أثرهما؛ لا تعني
   الحماية أو السجل أن هذه الآثار قابلة للتراجع.
4. **الحرارة أولًا**: مسار Thermal De-escalation يتدرج حسب الحالة الحرارية
   المرصودة؛ غياب القراءة لا يعني حرارة صفرية.
5. **عزل المهام**: يجري التعامل مع فشل المهمة منفردًا، لكن هذا لا يضمن غياب
   كل أخطاء النظام أو تعارضات ROM.
6. **قائمة الحماية**: تمنع الإنهاء عبر المسار الذي يفحص القائمة، ولا تلغي
   قيود Android/OEM الأخرى.

---

## 🧪 الاختبارات

```bash
./gradlew test
```

اختبارات JVM تغطي: المحرك (تنفيذ/استعادة/تدرّج)، السجل (JSON، قيم null،
تراس الاستعادة)، الملفات (تحميل assets، تحقق المدخلات)، والتسجيل (Scoring).

---

## ⚖️ مقارنة سريعة مع Game Speed X

| | Game Speed X | NitroBoost |
|---|---|---|
| الاستعادة | يدوية جزئية | تلقائية + يدوية كاملة + سجل |
| الحرارة | إيقاف شامل | تراجع تدرّجي ذكي |
| ملفات الألعاب | عامة | 17 ملف + مخصصة (DPI/FPS/تحديث) |
| الرصد فوق اللعبة | ❌ | ✅ FPS/CPU/RAM/حرارة/مسبار TCP |
| حماية عمليات | ❌ | ✅ قائمة قابلة للتعديل |
| ويدجت + Quick Tile | ❌ | ✅ |
| عربي | ❌ | ✅ |

---

## 📄 الترخيص

مشروع تعليمي/بحثي. استخدام Shizuku يخضع لشروطه. لا نتحمل مسؤولية تعديلات
النظام على الأجهزة غير المدعومة — استخدم على مسؤوليتك، والأمان هنا
(السجل + الاستعادة) موجودًا لهذا السبب.
