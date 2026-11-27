package com.example.nightmareai.engine

import android.net.Uri
import com.example.nightmareai.data.local.entity.ChatMessageEntity
import com.example.nightmareai.data.local.entity.ChatSourceEntity
import com.example.nightmareai.data.local.entity.ChatType
import com.example.nightmareai.data.repository.NightmareRepository
import com.example.nightmareai.domain.model.ExtractedSourceFacts
import com.example.nightmareai.domain.model.TwoSectionTruthReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class PipelineState {
    IDLE,
    ANALYZING_SOURCE,
    SOURCE_READY
}

/**
 * Coordinates the speculative multi-thread audit pipeline:
 * 1) Immediate [Dispatchers.IO] source extraction the millisecond a .zip/.apk/folder is attached.
 * 2) Silent 180ms-debounced speculative ML pre-computation on [Dispatchers.Default] for every
 *    text/voice change, without displaying premature results in the UI.
 * 3) Sub-30ms finalization & Room persistence when the judge presses Send.
 */
class LiveAuditOrchestrator(
    private val repository: NightmareRepository,
    private val sourceAnalyzer: OwnLocalSourceAnalyzer,
    private val mlTruthEngine: OwnMlTruthEngine,
    private val scope: CoroutineScope
) {

    private data class SpeculativeCacheKey(
        val googleId: String,
        val threadId: Long,
        val sourceSignature: String,
        val normalizedText: String
    )

    private data class SpeculativeCacheEntry(
        val key: SpeculativeCacheKey,
        val report: TwoSectionTruthReport
    )

    private val _pipelineState = MutableStateFlow(PipelineState.IDLE)
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    @Volatile
    private var activeSourceJob: Job? = null

    @Volatile
    private var activeSpeculativeJob: Job? = null

    @Volatile
    private var cachedSpeculative: SpeculativeCacheEntry? = null

    /**
     * Launches immediate background source parsing on [Dispatchers.IO] the instant the user attaches
     * a .zip archive, .apk file, or code folder Uri—without waiting for any pitch input.
     */
    fun attachSourceUriAsync(
        googleId: String,
        threadId: Long,
        uri: Uri,
        displayName: String? = null,
        isFolderTree: Boolean = false,
        onComplete: ((Result<ChatSourceEntity>) -> Unit)? = null
    ): Job {
        activeSourceJob?.cancel()
        _pipelineState.value = PipelineState.ANALYZING_SOURCE

        val job = scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val facts = sourceAnalyzer.analyzeUri(
                    uri = uri,
                    explicitDisplayName = displayName,
                    isFolderTree = isFolderTree
                )
                repository.attachSourceToThread(
                    googleId = googleId,
                    threadId = threadId,
                    sourceType = facts.sourceType,
                    displayName = facts.displayName,
                    localUriString = uri.toString(),
                    extractedFactsJson = facts.toJson()
                )
            }
            _pipelineState.value = if (result.isSuccess) PipelineState.SOURCE_READY else PipelineState.IDLE
            // Invalidate old speculative cache because source facts changed
            cachedSpeculative = null
            onComplete?.let { callback ->
                withContext(Dispatchers.Main.immediate) {
                    callback(result)
                }
            }
        }
        activeSourceJob = job
        return job
    }

    /**
     * Convenience entry point for testing or direct local File / directory ingestion on [Dispatchers.IO].
     */
    fun attachLocalFileAsync(
        googleId: String,
        threadId: Long,
        file: File,
        onComplete: ((Result<ChatSourceEntity>) -> Unit)? = null
    ): Job {
        return attachSourceUriAsync(
            googleId = googleId,
            threadId = threadId,
            uri = Uri.fromFile(file),
            displayName = file.name,
            isFolderTree = file.isDirectory,
            onComplete = onComplete
        )
    }

    /**
     * Triggered on every TextWatcher change or SpeechRecognizer partial transcript.
     * Debounces 180ms and silently pre-computes the TwoSectionTruthReport on [Dispatchers.Default]
     * without surfacing premature results on screen.
     */
    fun onPitchDraftChanged(
        googleId: String,
        threadId: Long,
        draftText: String,
        debounceMs: Long = DEFAULT_DEBOUNCE_MS
    ) {
        val normalized = draftText.trim()
        activeSpeculativeJob?.cancel()
        if (normalized.length < 3) {
            cachedSpeculative = null
            return
        }

        activeSpeculativeJob = scope.launch(Dispatchers.Default) {
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            val thread = repository.getThreadById(googleId, threadId) ?: return@launch
            if (thread.chatType == ChatType.GLOBAL_ABOUT_YOU) {
                // Global synthesis is computed on send
                return@launch
            }
            val sourceEntities = repository.getSourcesForThreadSync(googleId, threadId)
            val factsList = sourceEntities.mapNotNull { ExtractedSourceFacts.fromJson(it.extractedFactsJson) }
            val sig = buildSourceSignature(sourceEntities)
            val key = SpeculativeCacheKey(googleId, threadId, sig, normalized)
            if (cachedSpeculative?.key == key) return@launch

            val report = mlTruthEngine.evaluatePitchAgainstSources(
                pitchText = normalized,
                sources = factsList,
                usedSpeculativeCache = false
            )
            cachedSpeculative = SpeculativeCacheEntry(key = key, report = report)
        }
    }

    /**
     * Finalizes the audit when the judge presses Send.
     * Uses the warm speculative cache in <30ms when available, persists user + report messages
     * under ([googleId], [threadId]), and returns the generated [TwoSectionTruthReport].
     */
    suspend fun submitPitchAndVerify(
        googleId: String,
        threadId: Long,
        pitchText: String
    ): TwoSectionTruthReport {
        val trimmed = pitchText.trim()
        require(trimmed.isNotEmpty()) { "Pitch text cannot be empty." }

        // If a source attachment was just triggered milliseconds ago, await it first
        activeSourceJob?.let { job ->
            if (job.isActive) job.join()
        }

        val thread = repository.getThreadById(googleId, threadId)
            ?: throw SecurityException("Active thread $threadId does not belong to googleId=$googleId")

        // Persist the user's message
        repository.appendMessage(
            googleId = googleId,
            threadId = threadId,
            sender = ChatMessageEntity.SENDER_USER,
            content = trimmed
        )

        val finalReport = if (thread.chatType == ChatType.GLOBAL_ABOUT_YOU) {
            val snapshot = repository.getGlobalAuditSnapshotForAboutYou(googleId, threadId)
            mlTruthEngine.evaluateGlobalAboutYou(trimmed, snapshot)
        } else {
            val sendStartNs = System.nanoTime()
            val sourceEntities = repository.getSourcesForThreadSync(googleId, threadId)
            val sig = buildSourceSignature(sourceEntities)
            val key = SpeculativeCacheKey(googleId, threadId, sig, trimmed)
            val warm = cachedSpeculative?.takeIf { it.key == key }

            if (warm != null) {
                val mergeMs = ((System.nanoTime() - sendStartNs) / 1_000_000L).coerceAtLeast(1L)
                warm.report.copy(
                    analysisLatencyMs = mergeMs,
                    usedSpeculativeCache = true
                )
            } else {
                val factsList = sourceEntities.mapNotNull { ExtractedSourceFacts.fromJson(it.extractedFactsJson) }
                mlTruthEngine.evaluatePitchAgainstSources(
                    pitchText = trimmed,
                    sources = factsList,
                    usedSpeculativeCache = false
                )
            }
        }

        cachedSpeculative = null
        activeSpeculativeJob?.cancel()

        repository.appendMessage(
            googleId = googleId,
            threadId = threadId,
            sender = ChatMessageEntity.SENDER_ASSISTANT,
            content = finalReport.toPlainSummaryText(),
            twoSectionReportJson = finalReport.toJson()
        )
        return finalReport
    }

    fun hasWarmSpeculativeReport(googleId: String, threadId: Long, draftText: String): Boolean {
        val entry = cachedSpeculative ?: return false
        return entry.key.googleId == googleId &&
            entry.key.threadId == threadId &&
            entry.key.normalizedText == draftText.trim()
    }

    private fun buildSourceSignature(sources: List<ChatSourceEntity>): String =
        sources.joinToString("|") { "${it.id}:${it.uuid}:${it.createdAt}" }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 180L
    }
}
