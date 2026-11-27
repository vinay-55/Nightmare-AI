package com.example.nightmareai.domain.model

import org.json.JSONArray
import org.json.JSONObject

enum class ClaimVerdict(val label: String) {
    TRUTH("VERIFIED TRUTH"),
    EXAGGERATION("EXAGGERATED"),
    LIE("LIE / CONTRADICTED"),
    UNVERIFIED("UNVERIFIED")
}

data class ClaimEvaluation(
    val claimText: String,
    val verdict: ClaimVerdict,
    val mlConfidence: Float,
    val evidence: String
)

data class Section1TalkedAbout(
    val claimEvaluations: List<ClaimEvaluation>,
    val claimedAdvantages: List<String>,
    val claimedDisadvantages: List<String>
)

data class Section2DidntTalkAbout(
    val hiddenAdvantages: List<String>,
    val hiddenDisadvantages: List<String>
)

/**
 * Two-section truth verification report produced by [com.example.nightmareai.engine.OwnMlTruthEngine]
 * and persisted in [com.example.nightmareai.data.local.entity.ChatMessageEntity.twoSectionReportJson].
 */
data class TwoSectionTruthReport(
    val overallTruthScore: Int,
    val overallVerdict: String,
    val summaryHeadline: String,
    val section1TalkedAbout: Section1TalkedAbout,
    val section2DidntTalkAbout: Section2DidntTalkAbout,
    val analysisLatencyMs: Long = 0L,
    val usedSpeculativeCache: Boolean = false
) {
    fun toJson(): String {
        val claimsArr = JSONArray()
        section1TalkedAbout.claimEvaluations.forEach { eval ->
            claimsArr.put(
                JSONObject().apply {
                    put("claimText", eval.claimText)
                    put("verdict", eval.verdict.name)
                    put("mlConfidence", eval.mlConfidence.toDouble())
                    put("evidence", eval.evidence)
                }
            )
        }
        val sec1 = JSONObject().apply {
            put("claimEvaluations", claimsArr)
            put("claimedAdvantages", JSONArray(section1TalkedAbout.claimedAdvantages))
            put("claimedDisadvantages", JSONArray(section1TalkedAbout.claimedDisadvantages))
        }
        val sec2 = JSONObject().apply {
            put("hiddenAdvantages", JSONArray(section2DidntTalkAbout.hiddenAdvantages))
            put("hiddenDisadvantages", JSONArray(section2DidntTalkAbout.hiddenDisadvantages))
        }
        return JSONObject().apply {
            put("overallTruthScore", overallTruthScore)
            put("overallVerdict", overallVerdict)
            put("summaryHeadline", summaryHeadline)
            put("section1TalkedAbout", sec1)
            put("section2DidntTalkAbout", sec2)
            put("analysisLatencyMs", analysisLatencyMs)
            put("usedSpeculativeCache", usedSpeculativeCache)
        }.toString()
    }

    fun toPlainSummaryText(): String {
        val sb = StringBuilder()
        sb.appendLine("Truth Score: $overallTruthScore/100 ($overallVerdict)")
        sb.appendLine(summaryHeadline)
        sb.appendLine()
        sb.appendLine("SECTION 1 — WHAT THE USER TALKED ABOUT")
        if (section1TalkedAbout.claimEvaluations.isEmpty()) {
            sb.appendLine("• No direct technical claims detected in prompt.")
        } else {
            section1TalkedAbout.claimEvaluations.forEach { eval ->
                val pct = (eval.mlConfidence * 100).toInt()
                sb.appendLine("• [${eval.verdict.label} • $pct%] \"${eval.claimText}\"")
                sb.appendLine("  Evidence: ${eval.evidence}")
            }
        }
        if (section1TalkedAbout.claimedAdvantages.isNotEmpty()) {
            sb.appendLine("Claimed Advantages:")
            section1TalkedAbout.claimedAdvantages.forEach { sb.appendLine("  + $it") }
        }
        if (section1TalkedAbout.claimedDisadvantages.isNotEmpty()) {
            sb.appendLine("Claimed Disadvantages / Risks:")
            section1TalkedAbout.claimedDisadvantages.forEach { sb.appendLine("  - $it") }
        }
        sb.appendLine()
        sb.appendLine("SECTION 2 — WHAT THE USER DIDN'T TALK ABOUT")
        sb.appendLine("Hidden Advantages:")
        if (section2DidntTalkAbout.hiddenAdvantages.isEmpty()) {
            sb.appendLine("  + None detected beyond spoken claims.")
        } else {
            section2DidntTalkAbout.hiddenAdvantages.forEach { sb.appendLine("  + $it") }
        }
        sb.appendLine("Hidden Disadvantages / Red Flags:")
        if (section2DidntTalkAbout.hiddenDisadvantages.isEmpty()) {
            sb.appendLine("  - No hidden red flags detected.")
        } else {
            section2DidntTalkAbout.hiddenDisadvantages.forEach { sb.appendLine("  - $it") }
        }
        return sb.toString().trim()
    }

    companion object {
        fun fromJson(json: String?): TwoSectionTruthReport? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val obj = JSONObject(json)
                val sec1Obj = obj.getJSONObject("section1TalkedAbout")
                val claimsArr = sec1Obj.optJSONArray("claimEvaluations") ?: JSONArray()
                val claims = buildList {
                    for (i in 0 until claimsArr.length()) {
                        val c = claimsArr.getJSONObject(i)
                        val verdictStr = c.optString("verdict", ClaimVerdict.UNVERIFIED.name)
                        val verdict = runCatching { ClaimVerdict.valueOf(verdictStr) }
                            .getOrDefault(ClaimVerdict.UNVERIFIED)
                        add(
                            ClaimEvaluation(
                                claimText = c.optString("claimText"),
                                verdict = verdict,
                                mlConfidence = c.optDouble("mlConfidence", 0.5).toFloat(),
                                evidence = c.optString("evidence")
                            )
                        )
                    }
                }
                val sec2Obj = obj.getJSONObject("section2DidntTalkAbout")
                TwoSectionTruthReport(
                    overallTruthScore = obj.optInt("overallTruthScore", 0),
                    overallVerdict = obj.optString("overallVerdict", "UNVERIFIED"),
                    summaryHeadline = obj.optString("summaryHeadline", ""),
                    section1TalkedAbout = Section1TalkedAbout(
                        claimEvaluations = claims,
                        claimedAdvantages = sec1Obj.optJSONArray("claimedAdvantages").toStringList(),
                        claimedDisadvantages = sec1Obj.optJSONArray("claimedDisadvantages").toStringList()
                    ),
                    section2DidntTalkAbout = Section2DidntTalkAbout(
                        hiddenAdvantages = sec2Obj.optJSONArray("hiddenAdvantages").toStringList(),
                        hiddenDisadvantages = sec2Obj.optJSONArray("hiddenDisadvantages").toStringList()
                    ),
                    analysisLatencyMs = obj.optLong("analysisLatencyMs", 0L),
                    usedSpeculativeCache = obj.optBoolean("usedSpeculativeCache", false)
                )
            }.getOrNull()
        }
    }
}
