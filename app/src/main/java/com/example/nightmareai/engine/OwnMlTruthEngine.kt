package com.example.nightmareai.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.example.nightmareai.data.local.entity.ChatThreadEntity
import com.example.nightmareai.data.repository.GlobalAuditSnapshot
import com.example.nightmareai.domain.model.ClaimEvaluation
import com.example.nightmareai.domain.model.ClaimVerdict
import com.example.nightmareai.domain.model.ExtractedSourceFacts
import com.example.nightmareai.domain.model.Section1TalkedAbout
import com.example.nightmareai.domain.model.Section2DidntTalkAbout
import com.example.nightmareai.domain.model.TwoSectionTruthReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 100% offline, zero-rate-limit on-device ML truth-verification engine.
 * Supports optional CPU ONNX Runtime session inference (`assets/nightmare_truth_classifier.onnx`)
 * backed by a deterministic multi-feature logistic verifier & semantic code cross-referencer
 * running purely on [Dispatchers.Default].
 */
class OwnMlTruthEngine(context: Context? = null) {

    private val appContext: Context? = context?.applicationContext
    private val onnxSession: OrtSession? by lazy { loadOptionalOnnxSession() }

    /**
     * Cross-references a student's spoken/written pitch against extracted local artifact facts
     * and splits findings into Section 1 (What User Talked About) vs Section 2 (What User Didn't Talk About).
     */
    suspend fun evaluatePitchAgainstSources(
        pitchText: String,
        sources: List<ExtractedSourceFacts>,
        usedSpeculativeCache: Boolean = false
    ): TwoSectionTruthReport = withContext(Dispatchers.Default) {
        val startNs = System.nanoTime()
        val normalizedPitch = pitchText.trim()
        val lowerPitch = normalizedPitch.lowercase(Locale.US)

        if (sources.isEmpty()) {
            val claims = splitIntoClaims(normalizedPitch).map { claim ->
                ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.UNVERIFIED,
                    mlConfidence = 0.45f,
                    evidence = "No local .zip, .apk, or code folder attached to this chat thread yet."
                )
            }
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            return@withContext TwoSectionTruthReport(
                overallTruthScore = 50,
                overallVerdict = "NO SOURCE ATTACHED",
                summaryHeadline = "Attach a local .zip, .apk, or code folder to cross-verify claims offline.",
                section1TalkedAbout = Section1TalkedAbout(
                    claimEvaluations = claims,
                    claimedAdvantages = claims.map { "Unverified claim: ${it.claimText}" },
                    claimedDisadvantages = listOf("No source artifact available to verify pitch claims.")
                ),
                section2DidntTalkAbout = Section2DidntTalkAbout(
                    hiddenAdvantages = emptyList(),
                    hiddenDisadvantages = listOf("Source code / APK was not provided for inspection.")
                ),
                analysisLatencyMs = elapsedMs,
                usedSpeculativeCache = usedSpeculativeCache
            )
        }

        val merged = mergeSourceFacts(sources)
        val rawClaims = splitIntoClaims(normalizedPitch)
        val evaluations = rawClaims.map { claim -> evaluateSingleClaim(claim, merged) }

        val claimedAdvantages = mutableListOf<String>()
        val claimedDisadvantages = mutableListOf<String>()

        evaluations.forEach { eval ->
            when (eval.verdict) {
                ClaimVerdict.TRUTH -> claimedAdvantages.add("Verified: ${eval.claimText} (${eval.evidence})")
                ClaimVerdict.EXAGGERATION -> claimedDisadvantages.add("Exaggerated claim: ${eval.claimText} — ${eval.evidence}")
                ClaimVerdict.LIE -> claimedDisadvantages.add("Contradicted / Lie: ${eval.claimText} — ${eval.evidence}")
                ClaimVerdict.UNVERIFIED -> {
                    if (ADMITTED_LIMITATION_REGEX.containsMatchIn(eval.claimText.lowercase(Locale.US))) {
                        claimedDisadvantages.add("Admitted limitation: ${eval.claimText}")
                    } else {
                        claimedAdvantages.add("Claimed (unconfirmed in static scan): ${eval.claimText}")
                    }
                }
            }
        }

        // Section 2: What the User Didn't Talk About (Hidden Advantages & Hidden Disadvantages)
        val hiddenAdvantages = computeHiddenAdvantages(lowerPitch, merged)
        val hiddenDisadvantages = computeHiddenDisadvantages(lowerPitch, merged)

        val truthScore = computeOverallTruthScore(evaluations, merged)
        val overallVerdict = when {
            evaluations.isEmpty() -> "NO CLAIMS EVALUATED"
            evaluations.any { it.verdict == ClaimVerdict.LIE } && truthScore < 55 -> "HIGH EXAGGERATION / MISLEADING"
            truthScore >= 80 -> "VERIFIED"
            truthScore >= 55 -> "PARTIALLY VERIFIED"
            else -> "MISLEADING PITCH"
        }

        val lieCount = evaluations.count { it.verdict == ClaimVerdict.LIE }
        val exagCount = evaluations.count { it.verdict == ClaimVerdict.EXAGGERATION }
        val truthCount = evaluations.count { it.verdict == ClaimVerdict.TRUTH }
        val summaryHeadline =
            "Verified $truthCount, Exaggerated $exagCount, Lies $lieCount across ${sources.size} local artifact(s) • Code Quality ${merged.avgCodeQuality}/100."

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        TwoSectionTruthReport(
            overallTruthScore = truthScore,
            overallVerdict = overallVerdict,
            summaryHeadline = summaryHeadline,
            section1TalkedAbout = Section1TalkedAbout(
                claimEvaluations = evaluations,
                claimedAdvantages = claimedAdvantages.distinct(),
                claimedDisadvantages = claimedDisadvantages.distinct()
            ),
            section2DidntTalkAbout = Section2DidntTalkAbout(
                hiddenAdvantages = hiddenAdvantages,
                hiddenDisadvantages = hiddenDisadvantages
            ),
            analysisLatencyMs = elapsedMs,
            usedSpeculativeCache = usedSpeculativeCache
        )
    }

    /**
     * Synthesizes a cross-project ranking and comparison strictly for the GLOBAL_ABOUT_YOU workspace.
     */
    suspend fun evaluateGlobalAboutYou(
        judgeQuery: String,
        snapshot: GlobalAuditSnapshot
    ): TwoSectionTruthReport = withContext(Dispatchers.Default) {
        val startNs = System.nanoTime()
        if (snapshot.threads.isEmpty()) {
            return@withContext TwoSectionTruthReport(
                overallTruthScore = 0,
                overallVerdict = "NO AUDITS YET",
                summaryHeadline = "No Master or Normal audit chats exist yet for this account.",
                section1TalkedAbout = Section1TalkedAbout(
                    claimEvaluations = listOf(
                        ClaimEvaluation(
                            claimText = judgeQuery,
                            verdict = ClaimVerdict.UNVERIFIED,
                            mlConfidence = 0.5f,
                            evidence = "Create a Master Chat or Normal Chat and audit a team's code/APK first."
                        )
                    ),
                    claimedAdvantages = emptyList(),
                    claimedDisadvantages = listOf("Workspace has 0 audited teams.")
                ),
                section2DidntTalkAbout = Section2DidntTalkAbout(
                    hiddenAdvantages = emptyList(),
                    hiddenDisadvantages = emptyList()
                ),
                analysisLatencyMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L),
                usedSpeculativeCache = false
            )
        }

        data class RankedTeam(
            val thread: ChatThreadEntity,
            val compositeScore: Int,
            val truthScore: Int,
            val codeScore: Int,
            val secretsCount: Int,
            val mocksCount: Int,
            val sourceCount: Int
        )

        val ranked = snapshot.threads.map { thread ->
            val reports = snapshot.messagesByThread[thread.id].orEmpty()
                .mapNotNull { TwoSectionTruthReport.fromJson(it.twoSectionReportJson) }
            val sources = snapshot.sourcesByThread[thread.id].orEmpty()
                .mapNotNull { ExtractedSourceFacts.fromJson(it.extractedFactsJson) }

            val avgTruth = if (reports.isNotEmpty()) reports.map { it.overallTruthScore }.average().roundToInt() else 50
            val avgCode = if (sources.isNotEmpty()) sources.map { it.codeQualityScore }.average().roundToInt() else 50
            val secrets = sources.sumOf { it.hardcodedSecrets.size }
            val mocks = sources.sumOf { it.mockShortcuts.size }
            val composite = ((avgTruth * 0.55) + (avgCode * 0.45) - (secrets * 8) - (mocks * 4))
                .roundToInt()
                .coerceIn(0, 100)

            RankedTeam(
                thread = thread,
                compositeScore = composite,
                truthScore = avgTruth,
                codeScore = avgCode,
                secretsCount = secrets,
                mocksCount = mocks,
                sourceCount = sources.size
            )
        }.sortedByDescending { it.compositeScore }

        val top = ranked.first()
        val leaderboardClaims = ranked.mapIndexed { idx, item ->
            val verdict = when {
                item.compositeScore >= 75 && item.secretsCount == 0 -> ClaimVerdict.TRUTH
                item.compositeScore >= 50 -> ClaimVerdict.EXAGGERATION
                else -> ClaimVerdict.LIE
            }
            ClaimEvaluation(
                claimText = "#${idx + 1} ${item.thread.title} (${item.thread.chatType.name})",
                verdict = verdict,
                mlConfidence = (item.compositeScore / 100f).coerceIn(0.1f, 0.99f),
                evidence = "Composite ${item.compositeScore}/100 (Truth ${item.truthScore}, Code ${item.codeScore}, Sources ${item.sourceCount}, Secrets ${item.secretsCount}, Mocks ${item.mocksCount})"
            )
        }

        val topStrengths = ranked.take(3).map {
            "${it.thread.title}: Composite ${it.compositeScore}/100 with ${it.sourceCount} verified artifact(s)"
        }
        val riskyTeams = ranked.filter { it.secretsCount > 0 || it.mocksCount > 0 || it.truthScore < 60 }
            .map {
                "${it.thread.title}: Truth ${it.truthScore}/100, ${it.secretsCount} secret(s), ${it.mocksCount} mock shortcut(s)"
            }

        val hiddenGlobalPros = buildList {
            val offlineTeams = snapshot.sourcesByThread.values.flatten()
                .mapNotNull { ExtractedSourceFacts.fromJson(it.extractedFactsJson) }
                .count { it.mlModels.isNotEmpty() }
            if (offlineTeams > 0) {
                add("$offlineTeams uploaded project(s) actually bundle real .tflite/.onnx ML models.")
            }
            val testedTeams = snapshot.sourcesByThread.values.flatten()
                .mapNotNull { ExtractedSourceFacts.fromJson(it.extractedFactsJson) }
                .count { it.testFilesCount > 0 }
            if (testedTeams > 0) {
                add("$testedTeams project(s) include automated unit/instrumentation tests.")
            }
        }

        val hiddenGlobalCons = buildList {
            val totalSecrets = ranked.sumOf { it.secretsCount }
            val totalMocks = ranked.sumOf { it.mocksCount }
            if (totalSecrets > 0) add("Across all teams, $totalSecrets hardcoded API key/secret finding(s) were detected.")
            if (totalMocks > 0) add("Across all teams, $totalMocks mock/delay/TODO shortcut(s) were detected.")
        }

        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        TwoSectionTruthReport(
            overallTruthScore = top.compositeScore,
            overallVerdict = "LEADER: ${top.thread.title}",
            summaryHeadline = "Cross-audit ranking across ${ranked.size} thread(s) for query: \"$judgeQuery\"",
            section1TalkedAbout = Section1TalkedAbout(
                claimEvaluations = leaderboardClaims,
                claimedAdvantages = topStrengths,
                claimedDisadvantages = riskyTeams
            ),
            section2DidntTalkAbout = Section2DidntTalkAbout(
                hiddenAdvantages = hiddenGlobalPros,
                hiddenDisadvantages = hiddenGlobalCons
            ),
            analysisLatencyMs = elapsedMs,
            usedSpeculativeCache = false
        )
    }

    private fun evaluateSingleClaim(claim: String, merged: MergedFacts): ClaimEvaluation {
        val lower = claim.lowercase(Locale.US)
        val claimTokens = TOKEN_REGEX.findAll(lower).map { it.value }.filter { it.length >= 3 }.toSet()

        // 1. Offline / No external API / On-device ML claim check
        if (OFFLINE_AI_CLAIM_REGEX.containsMatchIn(lower)) {
            val hasExternalAi = merged.networkUsage.any { it.contains("External AI API") }
            val hasOnDeviceMl = merged.mlModels.isNotEmpty() ||
                merged.dependencies.any { it.contains("ONNX") || it.contains("TensorFlow Lite") || it.contains("PyTorch") || it.contains("MediaPipe") }

            return when {
                hasExternalAi && !hasOnDeviceMl -> ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.LIE,
                    mlConfidence = scoreWithOptionalOnnx(floatArrayOf(0.05f, 1.0f, 0.0f, 0.9f), 0.94f),
                    evidence = "Contradiction: External cloud AI endpoint found (${merged.networkUsage.filter { it.contains("External AI API") }.joinToString()}) with 0 local ML models."
                )
                hasOnDeviceMl && !hasExternalAi -> ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.TRUTH,
                    mlConfidence = scoreWithOptionalOnnx(floatArrayOf(0.95f, 0.0f, 0.9f, 0.9f), 0.92f),
                    evidence = "Confirmed on-device ML (${(merged.mlModels + merged.dependencies.filter { it.contains("ONNX") || it.contains("TensorFlow") }).distinct().joinToString()}) with no cloud AI endpoints."
                )
                hasOnDeviceMl && hasExternalAi -> ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.EXAGGERATION,
                    mlConfidence = 0.78f,
                    evidence = "Hybrid setup: Local ML exists, but external cloud AI calls were also detected."
                )
                else -> ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.EXAGGERATION,
                    mlConfidence = 0.74f,
                    evidence = "No .tflite/.onnx model files or local ML runtime dependency found in the inspected artifact."
                )
            }
        }

        // 2. Zero-mock / Real-time / Production-ready claim check
        if (NO_MOCK_CLAIM_REGEX.containsMatchIn(lower)) {
            return if (merged.mockShortcuts.isNotEmpty()) {
                ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.LIE,
                    mlConfidence = 0.91f,
                    evidence = "Contradiction: Found ${merged.mockShortcuts.size} mock/delay shortcut(s): ${merged.mockShortcuts.take(2).joinToString("; ")}"
                )
            } else if (merged.databaseUsage.isNotEmpty() || merged.networkUsage.isNotEmpty()) {
                ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.TRUTH,
                    mlConfidence = 0.84f,
                    evidence = "No mock/dummy shortcuts detected; real persistence/networking present."
                )
            } else {
                ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.UNVERIFIED,
                    mlConfidence = 0.56f,
                    evidence = "No obvious mocks found, but no live database or network layer detected either."
                )
            }
        }

        // 3. Security / Encryption / Auth / Zero-secrets claim check
        if (SECURITY_CLAIM_REGEX.containsMatchIn(lower)) {
            return when {
                merged.hardcodedSecrets.isNotEmpty() -> ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.LIE,
                    mlConfidence = 0.95f,
                    evidence = "Security lie: Found ${merged.hardcodedSecrets.joinToString("; ")}"
                )
                merged.authUsage.isNotEmpty() -> ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.TRUTH,
                    mlConfidence = 0.86f,
                    evidence = "Verified auth stack: ${merged.authUsage.joinToString(", ")} and 0 hardcoded secrets."
                )
                else -> ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.EXAGGERATION,
                    mlConfidence = 0.72f,
                    evidence = "0 hardcoded keys found, but no authentication or security library detected."
                )
            }
        }

        // 4. Database / Persistence claim check
        if (DB_CLAIM_REGEX.containsMatchIn(lower)) {
            return if (merged.databaseUsage.isNotEmpty()) {
                ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.TRUTH,
                    mlConfidence = 0.89f,
                    evidence = "Confirmed database layer: ${merged.databaseUsage.joinToString(", ")}"
                )
            } else {
                ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.LIE,
                    mlConfidence = 0.85f,
                    evidence = "Contradiction: No Room, SQLite, Firestore, Realm, or DataStore persistence detected."
                )
            }
        }

        // 5. Testing claim check
        if (TEST_CLAIM_REGEX.containsMatchIn(lower)) {
            return if (merged.testFilesCount > 0) {
                ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.TRUTH,
                    mlConfidence = 0.90f,
                    evidence = "Confirmed ${merged.testFilesCount} test file(s) in repository."
                )
            } else {
                ClaimEvaluation(
                    claimText = claim,
                    verdict = ClaimVerdict.LIE,
                    mlConfidence = 0.88f,
                    evidence = "Contradiction: 0 unit/instrumentation test files found in artifact."
                )
            }
        }

        // 6. General feature-vector + lexical overlap ML classifier
        val techCorpus = (merged.languages + merged.dependencies + merged.databaseUsage +
            merged.authUsage + merged.permissions + merged.rawKeywords)
            .map { it.lowercase(Locale.US) }
            .toSet()

        val matchedTokens = claimTokens.filter { token ->
            techCorpus.any { it == token || it.contains(token) }
        }
        val overlapRatio = if (claimTokens.isEmpty()) 0f else matchedTokens.size.toFloat() / claimTokens.size.toFloat()
        val hasHypeWords = HYPE_WORDS_REGEX.containsMatchIn(lower)
        val logisticProb = scoreWithOptionalOnnx(
            features = floatArrayOf(
                overlapRatio,
                if (hasHypeWords) 1.0f else 0.0f,
                (merged.avgCodeQuality / 100f),
                if (merged.mockShortcuts.isNotEmpty()) 1.0f else 0.0f
            ),
            fallbackProbability = sigmoid(2.8f * overlapRatio - (if (hasHypeWords) 0.9f else 0.0f) + 0.35f * (merged.avgCodeQuality / 100f) - 0.55f)
        )

        return when {
            matchedTokens.isNotEmpty() && logisticProb >= 0.58f -> ClaimEvaluation(
                claimText = claim,
                verdict = ClaimVerdict.TRUTH,
                mlConfidence = logisticProb,
                evidence = "Matched code symbols: ${matchedTokens.take(4).joinToString(", ")}"
            )
            hasHypeWords && matchedTokens.isEmpty() -> ClaimEvaluation(
                claimText = claim,
                verdict = ClaimVerdict.EXAGGERATION,
                mlConfidence = (1f - logisticProb).coerceIn(0.60f, 0.88f),
                evidence = "Marketing/superlative claim has no matching implementation tokens in inspected code."
            )
            else -> ClaimEvaluation(
                claimText = claim,
                verdict = ClaimVerdict.UNVERIFIED,
                mlConfidence = 0.52f,
                evidence = "Insufficient static evidence in artifact to confirm or refute this statement."
            )
        }
    }

    private fun computeHiddenAdvantages(lowerPitch: String, merged: MergedFacts): List<String> {
        val hidden = mutableListOf<String>()
        if (merged.mlModels.isNotEmpty() && !lowerPitch.contains("tflite") && !lowerPitch.contains("onnx") && !lowerPitch.contains("model")) {
            hidden.add("Undisclosed on-device ML asset(s): ${merged.mlModels.joinToString(", ")}")
        }
        if (merged.testFilesCount > 0 && !lowerPitch.contains("test")) {
            hidden.add("Includes ${merged.testFilesCount} automated test file(s) not highlighted in the pitch.")
        }
        if (merged.databaseUsage.isNotEmpty() && !lowerPitch.contains("room") && !lowerPitch.contains("sqlite") && !lowerPitch.contains("database") && !lowerPitch.contains("db")) {
            hidden.add("Implements persistent storage (${merged.databaseUsage.joinToString(", ")}) without mentioning it.")
        }
        if (merged.authUsage.isNotEmpty() && !lowerPitch.contains("auth") && !lowerPitch.contains("login") && !lowerPitch.contains("sign")) {
            hidden.add("Includes authentication layer (${merged.authUsage.joinToString(", ")}) omitted from pitch.")
        }
        merged.strengths.forEach { s ->
            val key = s.substringBefore(':').lowercase(Locale.US)
            if (hidden.none { it.lowercase(Locale.US).contains(key) } &&
                !lowerPitch.contains(key.take(10))
            ) {
                hidden.add(s)
            }
        }
        return hidden.distinct().take(6)
    }

    private fun computeHiddenDisadvantages(lowerPitch: String, merged: MergedFacts): List<String> {
        val hidden = mutableListOf<String>()
        if (merged.hardcodedSecrets.isNotEmpty() && !lowerPitch.contains("secret") && !lowerPitch.contains("hardcoded") && !lowerPitch.contains("api key")) {
            hidden.add("Hidden hardcoded secret(s): ${merged.hardcodedSecrets.joinToString("; ")}")
        }
        if (merged.mockShortcuts.isNotEmpty() && !lowerPitch.contains("mock") && !lowerPitch.contains("dummy") && !lowerPitch.contains("todo")) {
            hidden.add("Hidden mock/shortcut code: ${merged.mockShortcuts.take(3).joinToString("; ")}")
        }
        if (merged.networkUsage.any { it.contains("External AI API") } && !lowerPitch.contains("openai") && !lowerPitch.contains("gemini") && !lowerPitch.contains("api")) {
            hidden.add("Unmentioned dependency on external AI APIs: ${merged.networkUsage.filter { it.contains("External AI API") }.joinToString(", ")}")
        }
        merged.weaknesses.forEach { w ->
            if (hidden.none { it.contains(w.take(18)) }) {
                hidden.add(w)
            }
        }
        return hidden.distinct().take(6)
    }

    private fun computeOverallTruthScore(
        evaluations: List<ClaimEvaluation>,
        merged: MergedFacts
    ): Int {
        if (evaluations.isEmpty()) return merged.avgCodeQuality
        var score = 72
        evaluations.forEach { eval ->
            when (eval.verdict) {
                ClaimVerdict.TRUTH -> score += 8
                ClaimVerdict.UNVERIFIED -> score -= 3
                ClaimVerdict.EXAGGERATION -> score -= 14
                ClaimVerdict.LIE -> score -= 26
            }
        }
        score -= (merged.hardcodedSecrets.size * 6).coerceAtMost(18)
        score -= (merged.mockShortcuts.size * 4).coerceAtMost(16)
        return score.coerceIn(5, 99)
    }

    private fun splitIntoClaims(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.split(CLAIM_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.length >= 4 }
            .ifEmpty { listOf(text) }
    }

    private fun scoreWithOptionalOnnx(features: FloatArray, fallbackProbability: Float): Float {
        val session = onnxSession ?: return fallbackProbability.coerceIn(0.05f, 0.99f)
        return runCatching {
            val env = OrtEnvironment.getEnvironment()
            val inputName = session.inputNames.firstOrNull() ?: return fallbackProbability
            OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1L, features.size.toLong())).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val raw = result[0].value
                    when (raw) {
                        is Array<*> -> {
                            val row = raw.firstOrNull() as? FloatArray
                            row?.firstOrNull()?.coerceIn(0.05f, 0.99f) ?: fallbackProbability
                        }
                        is FloatArray -> raw.firstOrNull()?.coerceIn(0.05f, 0.99f) ?: fallbackProbability
                        else -> fallbackProbability
                    }
                }
            }
        }.getOrDefault(fallbackProbability.coerceIn(0.05f, 0.99f))
    }

    private fun loadOptionalOnnxSession(): OrtSession? {
        val ctx = appContext ?: return null
        return runCatching {
            val assetList = ctx.assets.list("")?.toSet().orEmpty()
            if (OPTIONAL_ONNX_MODEL !in assetList) return null
            val modelBytes = ctx.assets.open(OPTIONAL_ONNX_MODEL).use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            env.createSession(modelBytes, OrtSession.SessionOptions())
        }.getOrNull()
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    private fun mergeSourceFacts(sources: List<ExtractedSourceFacts>): MergedFacts {
        return MergedFacts(
            languages = sources.flatMap { it.languages }.distinct(),
            permissions = sources.flatMap { it.permissions }.distinct(),
            mlModels = sources.flatMap { it.mlModels }.distinct(),
            dependencies = sources.flatMap { it.dependencies }.distinct(),
            databaseUsage = sources.flatMap { it.databaseUsage }.distinct(),
            authUsage = sources.flatMap { it.authUsage }.distinct(),
            networkUsage = sources.flatMap { it.networkUsage }.distinct(),
            hardcodedSecrets = sources.flatMap { it.hardcodedSecrets }.distinct(),
            mockShortcuts = sources.flatMap { it.mockShortcuts }.distinct(),
            testFilesCount = sources.sumOf { it.testFilesCount },
            strengths = sources.flatMap { it.strengths }.distinct(),
            weaknesses = sources.flatMap { it.weaknesses }.distinct(),
            rawKeywords = sources.flatMap { it.rawKeywordSet }.toSet(),
            avgCodeQuality = sources.map { it.codeQualityScore }.average().roundToInt()
        )
    }

    private data class MergedFacts(
        val languages: List<String>,
        val permissions: List<String>,
        val mlModels: List<String>,
        val dependencies: List<String>,
        val databaseUsage: List<String>,
        val authUsage: List<String>,
        val networkUsage: List<String>,
        val hardcodedSecrets: List<String>,
        val mockShortcuts: List<String>,
        val testFilesCount: Int,
        val strengths: List<String>,
        val weaknesses: List<String>,
        val rawKeywords: Set<String>,
        val avgCodeQuality: Int
    )

    companion object {
        private const val OPTIONAL_ONNX_MODEL = "nightmare_truth_classifier.onnx"
        private val CLAIM_SPLIT_REGEX = Regex("""[.!?;]+|\n+|\b(?:and also|but|however)\b""", RegexOption.IGNORE_CASE)
        private val TOKEN_REGEX = Regex("""[a-z][a-z0-9_]+""")

        private val OFFLINE_AI_CLAIM_REGEX = Regex(
            """\b(offline|no api|zero api|no external|on-device|on device|local ml|local ai|onnx|tflite|tensorflow|custom model|neural network)\b"""
        )
        private val NO_MOCK_CLAIM_REGEX = Regex(
            """\b(no mock|no dummy|no fake|not mocked|real-time|realtime|production ready|production-ready|no hardcoded|live data)\b"""
        )
        private val SECURITY_CLAIM_REGEX = Regex(
            """\b(secure|encrypted|encryption|oauth|google sign|biometric|jwt|no secret|safe keys|authentication|auth)\b"""
        )
        private val DB_CLAIM_REGEX = Regex(
            """\b(room|sqlite|database|local storage|persists|wal|firestore|realm|datastore|supabase|postgres|mongo)\b"""
        )
        private val TEST_CLAIM_REGEX = Regex(
            """\b(unit test|tested|test coverage|automated test|integration test|100% tested)\b"""
        )
        private val HYPE_WORDS_REGEX = Regex(
            """\b(100%|zero latency|revolutionary|autonomous|proprietary ai|military grade|infinite|instantaneous|blockchain ai)\b"""
        )
        private val ADMITTED_LIMITATION_REGEX = Regex(
            """\b(todo|not yet|haven't|didn't|missing|prototype|limited|only works)\b"""
        )
    }
}
