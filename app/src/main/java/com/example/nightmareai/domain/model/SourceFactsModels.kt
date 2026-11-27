package com.example.nightmareai.domain.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Structured facts extracted offline by [com.example.nightmareai.engine.OwnLocalSourceAnalyzer]
 * from a .zip archive, .apk package, or local code folder.
 */
data class ExtractedSourceFacts(
    val sourceType: String,
    val displayName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val languages: List<String>,
    val packageName: String? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val permissions: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val services: List<String> = emptyList(),
    val mlModels: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val databaseUsage: List<String> = emptyList(),
    val authUsage: List<String> = emptyList(),
    val networkUsage: List<String> = emptyList(),
    val hardcodedSecrets: List<String> = emptyList(),
    val mockShortcuts: List<String> = emptyList(),
    val testFilesCount: Int = 0,
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val codeQualityScore: Int = 50,
    val rawKeywordSet: List<String> = emptyList(),
    val analysisDurationMs: Long = 0L
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("sourceType", sourceType)
        obj.put("displayName", displayName)
        obj.put("fileCount", fileCount)
        obj.put("totalBytes", totalBytes)
        obj.put("languages", JSONArray(languages))
        if (packageName != null) obj.put("packageName", packageName)
        if (minSdk != null) obj.put("minSdk", minSdk)
        if (targetSdk != null) obj.put("targetSdk", targetSdk)
        obj.put("permissions", JSONArray(permissions))
        obj.put("activities", JSONArray(activities))
        obj.put("services", JSONArray(services))
        obj.put("mlModels", JSONArray(mlModels))
        obj.put("dependencies", JSONArray(dependencies))
        obj.put("databaseUsage", JSONArray(databaseUsage))
        obj.put("authUsage", JSONArray(authUsage))
        obj.put("networkUsage", JSONArray(networkUsage))
        obj.put("hardcodedSecrets", JSONArray(hardcodedSecrets))
        obj.put("mockShortcuts", JSONArray(mockShortcuts))
        obj.put("testFilesCount", testFilesCount)
        obj.put("strengths", JSONArray(strengths))
        obj.put("weaknesses", JSONArray(weaknesses))
        obj.put("codeQualityScore", codeQualityScore)
        obj.put("rawKeywordSet", JSONArray(rawKeywordSet))
        obj.put("analysisDurationMs", analysisDurationMs)
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): ExtractedSourceFacts? {
            if (json.isBlank()) return null
            return runCatching {
                val obj = JSONObject(json)
                ExtractedSourceFacts(
                    sourceType = obj.optString("sourceType", "UNKNOWN"),
                    displayName = obj.optString("displayName", "Unknown Source"),
                    fileCount = obj.optInt("fileCount", 0),
                    totalBytes = obj.optLong("totalBytes", 0L),
                    languages = obj.optJSONArray("languages").toStringList(),
                    packageName = obj.optString("packageName").takeIf { it.isNotBlank() },
                    minSdk = if (obj.has("minSdk")) obj.optInt("minSdk") else null,
                    targetSdk = if (obj.has("targetSdk")) obj.optInt("targetSdk") else null,
                    permissions = obj.optJSONArray("permissions").toStringList(),
                    activities = obj.optJSONArray("activities").toStringList(),
                    services = obj.optJSONArray("services").toStringList(),
                    mlModels = obj.optJSONArray("mlModels").toStringList(),
                    dependencies = obj.optJSONArray("dependencies").toStringList(),
                    databaseUsage = obj.optJSONArray("databaseUsage").toStringList(),
                    authUsage = obj.optJSONArray("authUsage").toStringList(),
                    networkUsage = obj.optJSONArray("networkUsage").toStringList(),
                    hardcodedSecrets = obj.optJSONArray("hardcodedSecrets").toStringList(),
                    mockShortcuts = obj.optJSONArray("mockShortcuts").toStringList(),
                    testFilesCount = obj.optInt("testFilesCount", 0),
                    strengths = obj.optJSONArray("strengths").toStringList(),
                    weaknesses = obj.optJSONArray("weaknesses").toStringList(),
                    codeQualityScore = obj.optInt("codeQualityScore", 50),
                    rawKeywordSet = obj.optJSONArray("rawKeywordSet").toStringList(),
                    analysisDurationMs = obj.optLong("analysisDurationMs", 0L)
                )
            }.getOrNull()
        }
    }
}

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val result = ArrayList<String>(length())
    for (i in 0 until length()) {
        val item = optString(i)
        if (item.isNotBlank()) result.add(item)
    }
    return result
}
