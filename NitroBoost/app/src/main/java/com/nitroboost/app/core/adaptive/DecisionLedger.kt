package com.nitroboost.app.core.adaptive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections

/**
 * Local decision cache and complete paired-trial ledger.
 *
 * V1.x records still load. V2 records are context-bound and keep each sweep arm's
 * paired window summaries independently. A context mismatch or expired entry
 * is pending, never an implicit KEEP/DROP. The ledger is local-only.
 */
class DecisionLedger(private val file: File, private val maxPairs: Int = 40) {

    val entries: MutableMap<String, LedgerEntry> = Collections.synchronizedMap(linkedMapOf())

    @Synchronized
    fun snapshotEntries(): List<LedgerEntry> = entries.values.toList()

    @Synchronized
    fun load() {
        entries.clear()
        if (!file.exists()) return
        val liveText = try {
            file.readText()
        } catch (_: Exception) {
            return
        }
        try {
            entries.putAll(parseEntries(liveText))
            return
        } catch (_: Exception) {
            // Preserve the bad bytes for diagnosis, then try the last known-good snapshot.
            try {
                Files.copy(
                    file.toPath(),
                    File(file.parentFile, file.name + ".corrupt").toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
            }
        }

        val backup = File(file.parentFile, file.name + ".bak")
        val recovered = try {
            if (!backup.exists()) emptyMap() else parseEntries(backup.readText())
        } catch (_: Exception) {
            emptyMap()
        }
        entries.putAll(recovered)
        if (recovered.isNotEmpty()) persistLocked(createBackup = false)
    }

    /** Legacy API. A supplied assessment for the merged evidence is authoritative. */
    @Synchronized
    fun record(
        taskId: String,
        taskTitle: String,
        newDeltas: List<Double>,
        outcome: TrialOutcome,
        nowMs: Long,
        cfg: TrialConfig,
        detail: String? = null
    ): LedgerEntry {
        val prev = entries[taskId]
        val base = prev?.deltas ?: emptyList()
        val deltas = (base + newDeltas).takeLast(maxPairs.coerceAtLeast(1))
        // Preserve a caller's post-safety assessment when it describes exactly
        // the merged samples. Re-assessing it used to erase ThermalGuard's verdict.
        val final = when {
            newDeltas.isEmpty() -> outcome
            outcome.pairs == deltas.size -> outcome
            else -> AdaptivePolicy.assess(deltas, cfg)
        }
        val merged = LedgerEntry(
            taskId = taskId,
            taskTitle = taskTitle.ifEmpty { taskId },
            decision = final.decision,
            deltas = deltas,
            meanDelta = final.meanDelta,
            ciLow = final.ciLow,
            ciHigh = final.ciHigh,
            pairs = deltas.size,
            sessions = (prev?.sessions ?: 0) + if (newDeltas.isNotEmpty()) 1 else 0,
            evaluatedAt = nowMs,
            detail = detail ?: prev?.detail,
            score = final.meanScore,
            confidenceLevel = final.confidenceLevel,
            comparisonCount = final.comparisonCount,
            reason = final.reason
        )
        entries[taskId] = merged
        save()
        return merged
    }

    /**
     * Adds valid observations for one arm and assesses that arm only against
     * its own baseline. Thermal safety may downgrade KEEP or stop a candidate
     * deterministically; statistical intervals are never recalculated after it.
     */
    @Synchronized
    fun recordVariant(
        taskId: String,
        taskTitle: String,
        variantId: String,
        detail: String,
        newObservations: List<PairObservation>,
        comparisonCount: Int,
        context: TrialContext,
        nowMs: Long,
        cfg: TrialConfig,
        safetyLimit: Decision? = null
    ): VariantTrialRecord {
        val previousTask = entries[taskId]?.takeIf { it.context == context }
        val previousVariant = previousTask?.variants?.get(variantId)
        val priorValidObservations = (previousVariant?.observations ?: emptyList())
            .filter { isValidObservation(context, it) }
        val seenIds = priorValidObservations.mapTo(HashSet()) { it.sampleId }
        val fresh = newObservations.filter {
            isValidObservation(context, it) && it.sampleId !in seenIds
        }
        val observations = (priorValidObservations + fresh).takeLast(maxPairs.coerceAtLeast(1))
        val validObservations = observations
        val assessed = AdaptivePolicy.assessScores(
            validObservations.map { it.score.score }, cfg, comparisonCount.coerceAtLeast(1)
        )
        val finalDecision = applySafetyLimit(assessed.decision, safetyLimit)
        val reason = if (finalDecision != assessed.decision) {
            safetyReason(safetyLimit)
        } else assessed.reason
        val latestObservation = observations.maxByOrNull { it.blockIndex }
        val previousAttemptIsNewer = (previousVariant?.lastAttemptIndex ?: -1) >
            (latestObservation?.blockIndex ?: -1)
        val record = VariantTrialRecord(
            variantId = variantId,
            detail = detail,
            observations = observations,
            decision = finalDecision,
            meanScore = assessed.meanScore,
            ciLow = assessed.ciLow,
            ciHigh = assessed.ciHigh,
            confidenceLevel = assessed.confidenceLevel ?: AdaptivePolicy.FAMILY_WISE_CONFIDENCE,
            comparisonCount = comparisonCount.coerceAtLeast(1),
            sessions = validObservations.map { it.sessionId }.distinct().size,
            evaluatedAt = nowMs,
            reason = reason,
            attemptCount = maxOf(
                previousVariant?.attemptCount ?: 0,
                observations.maxOfOrNull { it.blockIndex + 1 } ?: 0
            ),
            lastAttemptIndex = maxOf(previousVariant?.lastAttemptIndex ?: -1, latestObservation?.blockIndex ?: -1),
            lastAttemptOrder = if (previousAttemptIsNewer) previousVariant?.lastAttemptOrder
                else latestObservation?.order ?: previousVariant?.lastAttemptOrder,
            lastAttemptAtMs = if (previousAttemptIsNewer) previousVariant?.lastAttemptAtMs ?: nowMs else nowMs,
            lastInvalidReason = if (previousAttemptIsNewer) previousVariant?.lastInvalidReason else null
        )
        val variants = previousTask?.variants?.toMutableMap() ?: linkedMapOf()
        variants[variantId] = record
        val previousPreview = variants.values
            .filter { arm -> arm.meanScore?.isFinite() == true &&
                arm.observations.any { isValidObservation(context, it) }
            }
            .maxByOrNull { it.meanScore ?: Double.NEGATIVE_INFINITY }
        val preview = previousPreview ?: record
        entries[taskId] = LedgerEntry(
            taskId = taskId,
            taskTitle = taskTitle.ifEmpty { taskId },
            // A sweep is unresolved until every arm has corrected evidence and
            // the selected arm has actually been restored/applied safely.
            decision = Decision.MORE_DATA,
            deltas = preview.observations.filter { isValidObservation(context, it) }
                .map { it.candidate.fpsMean - it.baseline.fpsMean },
            meanDelta = preview.observations
                .filter { isValidObservation(context, it) }
                .takeIf { it.isNotEmpty() }?.map {
                    it.candidate.fpsMean - it.baseline.fpsMean
                }?.average(),
            ciLow = preview.ciLow,
            ciHigh = preview.ciHigh,
            pairs = preview.observations.count { isValidObservation(context, it) },
            sessions = variants.values.sumOf { arm -> arm.observations.filter {
                isValidObservation(context, it)
            }.map { it.sessionId }.distinct().size },
            evaluatedAt = nowMs,
            detail = previousTask?.detail,
            context = context,
            score = preview.meanScore,
            confidenceLevel = preview.confidenceLevel,
            comparisonCount = comparisonCount.coerceAtLeast(1),
            reason = "sweep arm recorded; final selection pending",
            variants = variants
        )
        save()
        return record
    }

    /**
     * Persist a deterministic alternating order before touching the candidate.
     * Counting attempts (including later-invalid blocks) prevents retries from
     * always starting with baseline after an interrupted/contaminated pair.
     */
    @Synchronized
    fun beginPairAttempt(
        taskId: String,
        taskTitle: String,
        variantId: String,
        detail: String,
        comparisonCount: Int,
        context: TrialContext,
        nowMs: Long
    ): PairAttempt? {
        val previousTask = entries[taskId]?.takeIf { it.context == context }
        val variants = previousTask?.variants?.toMutableMap() ?: linkedMapOf()
        val previousVariant = variants[variantId]
        val index = maxOf(
            previousVariant?.attemptCount ?: 0,
            (previousVariant?.observations?.maxOfOrNull { it.blockIndex + 1 } ?: 0)
        )
        val order = if (index % 2 == 0) PairOrder.BASELINE_THEN_CANDIDATE
            else PairOrder.CANDIDATE_THEN_BASELINE
        val attempt = PairAttempt(index, order, nowMs)
        val placeholder = previousVariant ?: VariantTrialRecord(
            variantId = variantId,
            detail = detail,
            observations = emptyList(),
            decision = Decision.MORE_DATA,
            meanScore = null,
            ciLow = null,
            ciHigh = null,
            confidenceLevel = AdaptivePolicy.FAMILY_WISE_CONFIDENCE,
            comparisonCount = comparisonCount.coerceAtLeast(1),
            sessions = 0,
            evaluatedAt = nowMs,
            reason = "paired block pending"
        )
        variants[variantId] = placeholder.copy(
            detail = detail,
            decision = Decision.MORE_DATA,
            comparisonCount = comparisonCount.coerceAtLeast(1),
            evaluatedAt = nowMs,
            reason = "paired block pending",
            attemptCount = index + 1,
            lastAttemptIndex = index,
            lastAttemptOrder = order,
            lastAttemptAtMs = nowMs,
            lastInvalidReason = null
        )
        val task = (previousTask ?: emptyEntry(taskId, taskTitle, context, nowMs)).copy(
            decision = Decision.MORE_DATA,
            evaluatedAt = nowMs,
            context = context,
            reason = "paired block in progress",
            variants = variants
        )
        entries[taskId] = task
        return attempt.takeIf { save() }
    }

    /** Valid prior pairs for context-policy qualification; old objective revisions never qualify. */
    @Synchronized
    fun validObservationCounts(taskId: String, context: TrialContext): Map<String, Int> {
        val entry = entries[taskId] ?: return emptyMap()
        val storedContext = entry.context ?: return emptyMap()
        if (storedContext.objectivePolicyRevision != context.objectivePolicyRevision ||
            storedContext.copy(objectivePolicyKey = context.objectivePolicyKey) != context
        ) return emptyMap()
        return entry.variants.mapValues { (_, variant) ->
            variant.observations.count { isValidObservation(storedContext, it) }
        }
    }

    private fun isValidObservation(context: TrialContext, observation: PairObservation): Boolean {
        val expectedWeights = ObjectiveWeightResolver.weightsForProfile(context.objectivePolicyKey)
            ?: return false
        return observation.qualityValid && observation.invalidReason == null &&
            observation.order != PairOrder.LEGACY_SEQUENTIAL && observation.blockIndex >= 0 &&
            observation.temporalDistanceMs > 0L && observation.blockDurationMs > 0L &&
            observation.score.score.isFinite() && observation.score.objectiveWeights == expectedWeights
    }

    /** Mark a failed/invalid session as unresolved without adding bad evidence. */
    @Synchronized
    fun markMoreData(
        taskId: String,
        taskTitle: String,
        context: TrialContext,
        reason: String,
        nowMs: Long,
        variantId: String? = null,
        detail: String? = null
    ): LedgerEntry {
        val previous = entries[taskId]?.takeIf { it.context == context }
        val variants = previous?.variants?.toMutableMap() ?: linkedMapOf()
        if (variantId != null) {
            val old = variants[variantId]
            variants[variantId] = if (old == null) {
                VariantTrialRecord(
                    variantId, detail ?: variantId, emptyList(), Decision.MORE_DATA,
                    null, null, null, AdaptivePolicy.FAMILY_WISE_CONFIDENCE,
                    maxOf(1, variants.size), 0, nowMs, reason,
                    attemptCount = 0,
                    lastAttemptIndex = -1,
                    lastAttemptAtMs = nowMs,
                    lastInvalidReason = reason
                )
            } else {
                old.copy(
                    decision = Decision.MORE_DATA,
                    evaluatedAt = nowMs,
                    reason = reason,
                    lastInvalidReason = reason
                )
            }
        }
        val entry = (previous ?: emptyEntry(taskId, taskTitle, context, nowMs)).copy(
            decision = Decision.MORE_DATA,
            evaluatedAt = nowMs,
            context = context,
            detail = detail ?: previous?.detail,
            reason = reason,
            variants = variants
        )
        entries[taskId] = entry
        save()
        return entry
    }

    /** Finalize a single-arm trial only after its KEEP state is journaled. */
    @Synchronized
    fun finalizeSingle(
        taskId: String,
        taskTitle: String,
        variantId: String,
        context: TrialContext,
        nowMs: Long,
        detail: String? = null
    ): LedgerEntry {
        val previous = entries[taskId]?.takeIf { it.context == context }
            ?: return markMoreData(taskId, taskTitle, context, "no matching measured variant", nowMs, variantId)
        val variant = previous.variants[variantId]
            ?: return markMoreData(taskId, taskTitle, context, "no matching measured variant", nowMs, variantId)
        val validObservations = variant.observations.filter { isValidObservation(context, it) }
        val entry = previous.copy(
            taskTitle = taskTitle.ifEmpty { taskId },
            decision = if (validObservations.size >= 2) variant.decision else Decision.MORE_DATA,
            deltas = validObservations.map { it.candidate.fpsMean - it.baseline.fpsMean },
            meanDelta = validObservations.takeIf { it.isNotEmpty() }?.map {
                it.candidate.fpsMean - it.baseline.fpsMean
            }?.average(),
            ciLow = variant.ciLow.takeIf { validObservations.size >= 2 },
            ciHigh = variant.ciHigh.takeIf { validObservations.size >= 2 },
            pairs = validObservations.size,
            sessions = validObservations.map { it.sessionId }.distinct().size,
            evaluatedAt = nowMs,
            detail = detail ?: variant.detail,
            score = variant.meanScore,
            confidenceLevel = variant.confidenceLevel,
            comparisonCount = variant.comparisonCount,
            reason = variant.reason
        )
        entries[taskId] = entry
        save()
        return entry
    }

    /**
     * A variant sweep can finish only when every arm was measured in a valid
     * paired session and every corrected comparison is resolved. Then (and
     * only then) select among individually family-wise-corrected KEEP arms.
     */
    @Synchronized
    fun finalizeSweep(
        taskId: String,
        taskTitle: String,
        expectedVariantIds: List<String>,
        context: TrialContext,
        nowMs: Long,
        completeSweep: Boolean
    ): LedgerEntry {
        val previous = entries[taskId]?.takeIf { it.context == context }
            ?: return markMoreData(taskId, taskTitle, context, "sweep context unavailable", nowMs)
        val arms = expectedVariantIds.mapNotNull(previous.variants::get)
        val allPresent = arms.size == expectedVariantIds.size &&
            arms.all { it.comparisonCount == expectedVariantIds.size }
        val allResolved = arms.size == expectedVariantIds.size && arms.all { arm ->
            arm.decision.resolved && arm.observations.count { isValidObservation(context, it) } >= 2
        }
        val winner = if (completeSweep && allPresent && allResolved) {
            arms.filter { it.decision == Decision.KEEP }
                .maxWithOrNull(compareBy<VariantTrialRecord> { it.meanScore ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.variantId })
        } else null
        val decision = when {
            !completeSweep || !allPresent || !allResolved -> Decision.MORE_DATA
            winner != null -> Decision.KEEP
            arms.all { it.decision == Decision.DROP } -> Decision.DROP
            else -> Decision.NEUTRAL
        }
        val preview = winner ?: arms.maxByOrNull { it.meanScore ?: Double.NEGATIVE_INFINITY }
        val entry = previous.copy(
            taskTitle = taskTitle.ifEmpty { taskId },
            decision = decision,
            deltas = preview?.observations?.filter { isValidObservation(context, it) }
                ?.map { it.candidate.fpsMean - it.baseline.fpsMean } ?: emptyList(),
            meanDelta = preview?.observations?.filter { isValidObservation(context, it) }
                ?.takeIf { it.isNotEmpty() }?.map {
                    it.candidate.fpsMean - it.baseline.fpsMean
                }?.average(),
            ciLow = preview?.ciLow,
            ciHigh = preview?.ciHigh,
            pairs = preview?.observations?.count { isValidObservation(context, it) } ?: 0,
            sessions = arms.sumOf { arm -> arm.observations.filter {
                isValidObservation(context, it)
            }.map { it.sessionId }.distinct().size },
            evaluatedAt = nowMs,
            detail = winner?.detail,
            score = preview?.meanScore,
            confidenceLevel = preview?.confidenceLevel,
            comparisonCount = expectedVariantIds.size.coerceAtLeast(1),
            reason = when (decision) {
                Decision.KEEP -> "best individually corrected variant: ${winner?.variantId}"
                Decision.DROP -> "all tested variants were harmful"
                Decision.NEUTRAL -> "all corrected variant comparisons resolved without a useful winner"
                else -> "one or more variants need valid additional data"
            }
        )
        entries[taskId] = entry
        save()
        return entry
    }

    @Synchronized
    fun decisionFor(taskId: String): Decision? = entries[taskId]?.decision

    /** Backward-compatible, context-free lookup for old callers/tests. */
    @Synchronized
    fun isResolved(taskId: String): Boolean = entries[taskId]?.resolved == true

    private fun hasCompatibleResolvedEvidence(entry: LedgerEntry, context: TrialContext): Boolean {
        if (!entry.decision.resolved) return true
        if (context.objectivePolicyRevision < TrialContext.OBJECTIVE_POLICY_REVISION) return false
        val arms = entry.variants.values
        return arms.isNotEmpty() && arms.size == entry.comparisonCount.coerceAtLeast(1) &&
            arms.all { arm -> arm.decision.resolved &&
                arm.observations.count { isValidObservation(context, it) } >=
                    ObjectiveWeightResolver.MIN_CONTEXT_EVIDENCE_PAIRS
            }
    }

    @Synchronized
    fun decisionFor(taskId: String, context: TrialContext, nowMs: Long, ttlMs: Long): Decision? {
        val e = entries[taskId] ?: return null
        return e.decision.takeIf {
            e.context == context && isFresh(e.evaluatedAt, nowMs, ttlMs) &&
                hasCompatibleResolvedEvidence(e, context)
        }
    }

    @Synchronized
    fun isResolved(taskId: String, context: TrialContext, nowMs: Long, ttlMs: Long): Boolean =
        decisionFor(taskId, context, nowMs, ttlMs)?.let {
            it == Decision.KEEP || it == Decision.DROP || it == Decision.NEUTRAL
        } == true

    @Synchronized
    fun isCurrent(entry: LedgerEntry, context: TrialContext, nowMs: Long, ttlMs: Long): Boolean =
        entry.context == context && isFresh(entry.evaluatedAt, nowMs, ttlMs) &&
            hasCompatibleResolvedEvidence(entry, context)

    /** Unresolved candidates the loop should keep measuring. */
    @Synchronized
    fun pendingIds(): List<String> = entries.values.filter { !it.resolved }.map { it.taskId }

    /** Atomic, fsync-before-rename save. Returns false without truncating the last good file. */
    @Synchronized
    fun save(): Boolean = persistLocked(createBackup = true)

    private fun persistLocked(createBackup: Boolean): Boolean {
        return try {
            file.parentFile?.mkdirs()
            val data = serializeEntries().toByteArray(Charsets.UTF_8)
            val tmp = File(file.parentFile, file.name + ".tmp")
            writeAndSync(tmp, data)
            if (createBackup && file.exists() && isParseable(file)) {
                val backup = File(file.parentFile, file.name + ".bak")
                val backupTmp = File(file.parentFile, file.name + ".bak.tmp")
                try {
                    writeAndSync(backupTmp, file.readBytes())
                    atomicReplace(backupTmp, backup)
                } catch (_: Exception) {
                    backupTmp.delete()
                    // The live file remains intact until the already-synced tmp replaces it.
                }
            }
            atomicReplace(tmp, file)
            true
        } catch (_: Exception) {
            try {
                File(file.parentFile, file.name + ".tmp").delete()
            } catch (_: Exception) {
            }
            false
        }
    }

    private fun writeAndSync(target: File, bytes: ByteArray) {
        FileOutputStream(target).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    private fun atomicReplace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Same-filesystem replacement remains preferable to a direct copy;
            // if this fails, retain the existing live file and report failure.
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isParseable(candidate: File): Boolean = try {
        parseEntries(candidate.readText())
        true
    } catch (_: Exception) {
        false
    }

    private fun parseEntries(text: String): Map<String, LedgerEntry> {
        if (text.isBlank()) return emptyMap()
        val arr = JSONArray(text)
        val loaded = linkedMapOf<String, LedgerEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            try {
                val taskId = o.getString("taskId")
                val deltasJson = o.optJSONArray("deltas")
                val deltas = ArrayList<Double>(deltasJson?.length() ?: 0)
                if (deltasJson != null) for (j in 0 until deltasJson.length()) {
                    val v = deltasJson.optDouble(j, Double.NaN)
                    if (v.isFinite()) deltas += v
                }
                val variants = linkedMapOf<String, VariantTrialRecord>()
                val variantsJson = o.optJSONObject("variants")
                if (variantsJson != null) {
                    val keys = variantsJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = variantsJson.optJSONObject(key) ?: continue
                        parseVariant(key, value)?.let { variants[key] = it }
                    }
                }
                loaded[taskId] = LedgerEntry(
                    taskId = taskId,
                    taskTitle = o.optString("taskTitle", taskId),
                    decision = parseDecision(o.optString("decision", Decision.MORE_DATA.name)),
                    deltas = deltas,
                    meanDelta = nullableDouble(o, "meanDelta"),
                    ciLow = nullableDouble(o, "ciLow"),
                    ciHigh = nullableDouble(o, "ciHigh"),
                    pairs = o.optInt("pairs", deltas.size),
                    sessions = o.optInt("sessions", 1),
                    evaluatedAt = o.optLong("evaluatedAt"),
                    detail = nullableString(o, "detail"),
                    context = o.optJSONObject("context")?.let(::parseContext),
                    score = nullableDouble(o, "score"),
                    confidenceLevel = nullableDouble(o, "confidenceLevel"),
                    comparisonCount = o.optInt("comparisonCount", 1).coerceAtLeast(1),
                    reason = nullableString(o, "reason"),
                    variants = variants
                )
            } catch (_: Exception) {
                // Skip one malformed object; the rest of a valid ledger remains usable.
            }
        }
        return loaded
    }

    private fun serializeEntries(): String {
        val arr = JSONArray()
        entries.values.forEach { entry ->
            val o = JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("taskId", entry.taskId)
                .put("taskTitle", entry.taskTitle)
                .put("decision", entry.decision.name)
                .put("deltas", JSONArray(entry.deltas))
                .put("meanDelta", entry.meanDelta ?: JSONObject.NULL)
                .put("ciLow", entry.ciLow ?: JSONObject.NULL)
                .put("ciHigh", entry.ciHigh ?: JSONObject.NULL)
                .put("pairs", entry.pairs)
                .put("sessions", entry.sessions)
                .put("evaluatedAt", entry.evaluatedAt)
                .put("detail", entry.detail ?: JSONObject.NULL)
                .put("context", entry.context?.let(::contextJson) ?: JSONObject.NULL)
                .put("score", entry.score ?: JSONObject.NULL)
                .put("confidenceLevel", entry.confidenceLevel ?: JSONObject.NULL)
                .put("comparisonCount", entry.comparisonCount)
                .put("reason", entry.reason ?: JSONObject.NULL)
            val variants = JSONObject()
            entry.variants.forEach { (id, value) -> variants.put(id, variantJson(value)) }
            o.put("variants", variants)
            arr.put(o)
        }
        return arr.toString(2)
    }

    private fun parseVariant(id: String, o: JSONObject): VariantTrialRecord? {
        val observationsJson = o.optJSONArray("observations") ?: JSONArray()
        val observations = ArrayList<PairObservation>(observationsJson.length())
        for (i in 0 until observationsJson.length()) {
            parseObservation(observationsJson.optJSONObject(i) ?: continue)?.let(observations::add)
        }
        return VariantTrialRecord(
            variantId = o.optString("variantId", id),
            detail = o.optString("detail", id),
            observations = observations,
            decision = parseDecision(o.optString("decision", Decision.MORE_DATA.name)),
            meanScore = nullableDouble(o, "meanScore"),
            ciLow = nullableDouble(o, "ciLow"),
            ciHigh = nullableDouble(o, "ciHigh"),
            confidenceLevel = o.optDouble("confidenceLevel", AdaptivePolicy.FAMILY_WISE_CONFIDENCE),
            comparisonCount = o.optInt("comparisonCount", 1).coerceAtLeast(1),
            sessions = o.optInt("sessions", 0),
            evaluatedAt = o.optLong("evaluatedAt"),
            reason = o.optString("reason", ""),
            attemptCount = o.optInt("attemptCount", observations.size).coerceAtLeast(0),
            lastAttemptIndex = o.optInt("lastAttemptIndex", observations.size - 1),
            lastAttemptOrder = o.optString("lastAttemptOrder")
                .takeIf { it.isNotBlank() && it != "null" }?.let(::parsePairOrder),
            lastAttemptAtMs = o.optLong("lastAttemptAtMs", o.optLong("evaluatedAt")),
            lastInvalidReason = nullableString(o, "lastInvalidReason")
        )
    }

    private fun variantJson(value: VariantTrialRecord): JSONObject {
        val observations = JSONArray()
        value.observations.forEach { observations.put(observationJson(it)) }
        return JSONObject()
            .put("variantId", value.variantId)
            .put("detail", value.detail)
            .put("observations", observations)
            .put("decision", value.decision.name)
            .put("meanScore", value.meanScore ?: JSONObject.NULL)
            .put("ciLow", value.ciLow ?: JSONObject.NULL)
            .put("ciHigh", value.ciHigh ?: JSONObject.NULL)
            .put("confidenceLevel", value.confidenceLevel)
            .put("comparisonCount", value.comparisonCount)
            .put("sessions", value.sessions)
            .put("evaluatedAt", value.evaluatedAt)
            .put("reason", value.reason)
            .put("attemptCount", value.attemptCount)
            .put("lastAttemptIndex", value.lastAttemptIndex)
            .put("lastAttemptOrder", value.lastAttemptOrder?.name ?: JSONObject.NULL)
            .put("lastAttemptAtMs", value.lastAttemptAtMs)
            .put("lastInvalidReason", value.lastInvalidReason ?: JSONObject.NULL)
    }

    private fun parseObservation(o: JSONObject): PairObservation? = try {
        PairObservation(
            sampleId = o.getString("sampleId"),
            sessionId = o.optString("sessionId", "legacy"),
            observedAtMs = o.optLong("observedAtMs"),
            baseline = parseWindow(o.getJSONObject("baseline")),
            candidate = parseWindow(o.getJSONObject("candidate")),
            score = parseScore(o.getJSONObject("score")),
            blockIndex = o.optInt("blockIndex", 0),
            order = parsePairOrder(o.optString("order", PairOrder.LEGACY_SEQUENTIAL.name)),
            temporalDistanceMs = o.optLong("temporalDistanceMs", 0L),
            blockDurationMs = o.optLong("blockDurationMs", 0L),
            qualityValid = o.optBoolean("qualityValid", true),
            invalidReason = nullableString(o, "invalidReason")
        )
    } catch (_: Exception) {
        null
    }

    private fun observationJson(value: PairObservation): JSONObject = JSONObject()
        .put("sampleId", value.sampleId)
        .put("sessionId", value.sessionId)
        .put("observedAtMs", value.observedAtMs)
        .put("baseline", windowJson(value.baseline))
        .put("candidate", windowJson(value.candidate))
        .put("score", scoreJson(value.score))
        .put("blockIndex", value.blockIndex)
        .put("order", value.order.name)
        .put("temporalDistanceMs", value.temporalDistanceMs)
        .put("blockDurationMs", value.blockDurationMs)
        .put("qualityValid", value.qualityValid)
        .put("invalidReason", value.invalidReason ?: JSONObject.NULL)

    private fun windowJson(value: WindowMetrics): JSONObject = JSONObject()
        .put("fpsMean", value.fpsMean)
        .put("lowFps", value.lowFps)
        .put("fpsSampleCount", value.fpsSampleCount)
        .put("sampleCount", value.sampleCount)
        .put("thermalSampleCount", value.thermalSampleCount)
        .put("thermalTier", value.thermalTier)
        .put("minThermalTier", value.minThermalTier)
        .put("maxThermalTier", value.maxThermalTier)
        .put("tempMeanC", value.tempMeanC ?: JSONObject.NULL)
        .put("tempMaxC", value.tempMaxC ?: JSONObject.NULL)
        .put("thermalSlopeCPerMin", value.thermalSlopeCPerMin ?: JSONObject.NULL)
        .put("ramMeanPct", value.ramMeanPct ?: JSONObject.NULL)
        .put("energyMeanMah", value.energyMeanMah ?: JSONObject.NULL)
        .put("frameTime", value.frameTime?.let(::frameTimeJson) ?: JSONObject.NULL)
        .put("firstTimestampMs", value.firstTimestampMs)
        .put("lastTimestampMs", value.lastTimestampMs)
        .put("windowComplete", value.windowComplete)
        .put("requestedWindowMs", value.requestedWindowMs)
        .put("maxSampleGapMs", value.maxSampleGapMs)
        .put("maxMonitorAgeMs", value.maxMonitorAgeMs)
        .put("gamePackage", value.gamePackage ?: JSONObject.NULL)
        .put("processEpoch", value.processEpoch)
        .put("targetFps", value.targetFps)
        .put("fpsCoefficientOfVariation", value.fpsCoefficientOfVariation ?: JSONObject.NULL)

    private fun parseWindow(o: JSONObject): WindowMetrics = WindowMetrics(
        fpsMean = o.optDouble("fpsMean"),
        lowFps = o.optDouble("lowFps"),
        fpsSampleCount = o.optInt("fpsSampleCount"),
        sampleCount = o.optInt("sampleCount"),
        thermalSampleCount = o.optInt("thermalSampleCount"),
        thermalTier = o.optInt("thermalTier"),
        minThermalTier = o.optInt("minThermalTier", o.optInt("thermalTier")),
        maxThermalTier = o.optInt("maxThermalTier", o.optInt("thermalTier")),
        tempMeanC = nullableDouble(o, "tempMeanC"),
        tempMaxC = nullableDouble(o, "tempMaxC"),
        thermalSlopeCPerMin = nullableDouble(o, "thermalSlopeCPerMin"),
        ramMeanPct = nullableDouble(o, "ramMeanPct"),
        energyMeanMah = nullableDouble(o, "energyMeanMah"),
        frameTime = o.optJSONObject("frameTime")?.let(::parseFrameTime),
        firstTimestampMs = o.optLong("firstTimestampMs"),
        lastTimestampMs = o.optLong("lastTimestampMs"),
        windowComplete = o.optBoolean("windowComplete", true),
        requestedWindowMs = o.optLong("requestedWindowMs"),
        maxSampleGapMs = o.optLong("maxSampleGapMs"),
        maxMonitorAgeMs = o.optLong("maxMonitorAgeMs"),
        gamePackage = nullableString(o, "gamePackage"),
        processEpoch = o.optLong("processEpoch"),
        targetFps = o.optInt("targetFps", 60),
        fpsCoefficientOfVariation = nullableDouble(o, "fpsCoefficientOfVariation")
    )

    private fun scoreJson(value: ScoreComponents): JSONObject = JSONObject()
        .put("averageFpsGain", value.averageFpsGain)
        .put("lowFpsGain", value.lowFpsGain)
        .put("frameStabilityGain", value.frameStabilityGain ?: JSONObject.NULL)
        .put("hitchGain", value.hitchGain ?: JSONObject.NULL)
        .put("memoryGain", value.memoryGain ?: JSONObject.NULL)
        .put("energyGain", value.energyGain ?: JSONObject.NULL)
        .put("thermalTemperatureCost", value.thermalTemperatureCost ?: JSONObject.NULL)
        .put("thermalSlopeCost", value.thermalSlopeCost ?: JSONObject.NULL)
        .put("thermalTierCost", value.thermalTierCost)
        .put("thermalHeadroomFactor", value.thermalHeadroomFactor)
        .put("score", value.score)
        .put("objectiveWeights", objectiveWeightsJson(value.objectiveWeights))

    private fun parseScore(o: JSONObject): ScoreComponents = ScoreComponents(
        averageFpsGain = o.optDouble("averageFpsGain"),
        lowFpsGain = o.optDouble("lowFpsGain"),
        frameStabilityGain = nullableDouble(o, "frameStabilityGain"),
        hitchGain = nullableDouble(o, "hitchGain"),
        memoryGain = nullableDouble(o, "memoryGain"),
        energyGain = nullableDouble(o, "energyGain"),
        thermalTemperatureCost = nullableDouble(o, "thermalTemperatureCost"),
        thermalSlopeCost = nullableDouble(o, "thermalSlopeCost"),
        thermalTierCost = o.optDouble("thermalTierCost"),
        thermalHeadroomFactor = o.optDouble("thermalHeadroomFactor", 1.0),
        score = o.optDouble("score"),
        objectiveWeights = o.optJSONObject("objectiveWeights")?.let(::parseObjectiveWeights)
            ?: ObjectiveWeightResolver.DEFAULT_WEIGHTS
    )

    private fun objectiveWeightsJson(value: ObjectiveWeights): JSONObject = JSONObject()
        .put("performance", value.performance)
        .put("temperature", value.temperature)
        .put("thermalSlope", value.thermalSlope)
        .put("thermalTier", value.thermalTier)

    private fun parseObjectiveWeights(o: JSONObject): ObjectiveWeights =
        ObjectiveWeightResolver.normalizeOrDefault(
            ObjectiveWeights(
                performance = o.optDouble("performance", Double.NaN),
                temperature = o.optDouble("temperature", Double.NaN),
                thermalSlope = o.optDouble("thermalSlope", Double.NaN),
                thermalTier = o.optDouble("thermalTier", Double.NaN)
            )
        )

    private fun frameTimeJson(value: FrameTimeMetrics): JSONObject = JSONObject()
        .put("frameCount", value.frameCount)
        .put("medianMs", value.medianMs)
        .put("p95Ms", value.p95Ms)
        .put("p99Ms", value.p99Ms)
        .put("varianceMs2", value.varianceMs2)
        .put("hitchCount", value.hitchCount)
        .put("hitchRate", value.hitchRate)
        .put("hitchThresholdMs", value.hitchThresholdMs)

    private fun parseFrameTime(o: JSONObject): FrameTimeMetrics = FrameTimeMetrics(
        frameCount = o.optInt("frameCount"),
        medianMs = o.optDouble("medianMs"),
        p95Ms = o.optDouble("p95Ms"),
        p99Ms = o.optDouble("p99Ms"),
        varianceMs2 = o.optDouble("varianceMs2"),
        hitchCount = o.optInt("hitchCount"),
        hitchRate = o.optDouble("hitchRate"),
        hitchThresholdMs = o.optDouble("hitchThresholdMs")
    )

    private fun contextJson(value: TrialContext): JSONObject = JSONObject()
        .put("deviceKey", value.deviceKey)
        .put("androidVersion", value.androidVersion)
        .put("gamePackage", value.gamePackage)
        .put("boostLevel", value.boostLevel)
        .put("thermalTier", value.thermalTier)
        .put("capabilityKey", value.capabilityKey)
        .put("profileKey", value.profileKey)
        .put("thermalSignature", value.thermalSignature)
        .put("taskRevision", value.taskRevision)
        .put("objectivePolicyRevision", value.objectivePolicyRevision)
        .put("objectivePolicyKey", value.objectivePolicyKey)

    private fun parseContext(o: JSONObject): TrialContext = TrialContext(
        deviceKey = o.optString("deviceKey"),
        androidVersion = o.optString("androidVersion"),
        gamePackage = o.optString("gamePackage"),
        boostLevel = o.optInt("boostLevel"),
        thermalTier = o.optInt("thermalTier"),
        capabilityKey = o.optString("capabilityKey"),
        profileKey = o.optString("profileKey"),
        thermalSignature = o.optString("thermalSignature", ""),
        taskRevision = o.optInt("taskRevision", 1),
        objectivePolicyRevision = o.optInt("objectivePolicyRevision", 0),
        objectivePolicyKey = o.optString(
            "objectivePolicyKey", TrialContext.LEGACY_OBJECTIVE_POLICY_KEY
        )
    )

    private fun emptyEntry(taskId: String, taskTitle: String, context: TrialContext, nowMs: Long) =
        LedgerEntry(
            taskId = taskId,
            taskTitle = taskTitle.ifEmpty { taskId },
            decision = Decision.MORE_DATA,
            deltas = emptyList(),
            meanDelta = null,
            ciLow = null,
            ciHigh = null,
            pairs = 0,
            sessions = 0,
            evaluatedAt = nowMs,
            context = context
        )

    private fun isFresh(evaluatedAt: Long, nowMs: Long, ttlMs: Long): Boolean =
        evaluatedAt > 0L && nowMs >= evaluatedAt && nowMs - evaluatedAt <= ttlMs

    private fun applySafetyLimit(statistical: Decision, safetyLimit: Decision?): Decision {
        if (safetyLimit == null) return statistical
        return when (safetyLimit) {
            // A deterministic safety trip is allowed to stop re-applying a
            // harmful variant even before its performance CI is conclusive.
            Decision.DROP -> Decision.DROP
            Decision.NEUTRAL -> if (statistical == Decision.KEEP) Decision.NEUTRAL else statistical
            Decision.MORE_DATA, Decision.NEEDS_MORE -> Decision.MORE_DATA
            Decision.KEEP -> statistical // safety constraints are never allowed to grant KEEP
        }
    }

    private fun safetyReason(limit: Decision?): String = when (limit) {
        Decision.DROP -> "thermal safety forced DROP"
        Decision.NEUTRAL -> "thermal cost downgraded statistical KEEP"
        else -> "thermal/session quality requires more data"
    }

    private fun parseDecision(value: String): Decision = when (value) {
        "NEEDS_MORE" -> Decision.MORE_DATA
        else -> Decision.valueOf(value)
    }

    private fun parsePairOrder(value: String): PairOrder =
        runCatching { PairOrder.valueOf(value) }.getOrDefault(PairOrder.LEGACY_SEQUENTIAL)

    private fun nullableDouble(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optDouble(key).takeIf { it.isFinite() }
    }

    private fun nullableString(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }

    companion object {
        const val SCHEMA_VERSION = 2
    }
}
