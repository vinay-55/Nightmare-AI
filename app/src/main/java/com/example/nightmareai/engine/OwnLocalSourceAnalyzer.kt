package com.example.nightmareai.engine

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.example.nightmareai.data.local.entity.ChatSourceEntity
import com.example.nightmareai.domain.model.ExtractedSourceFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 100% offline, zero-API local artifact inspector running on [Dispatchers.IO].
 * Extracts factual signals from .zip source archives, .apk binaries (via PackageManager + DEX/ZIP scan),
 * and local code folders (SAF DocumentFile tree or local java.io.File directories).
 */
class OwnLocalSourceAnalyzer(private val context: Context) {

    suspend fun analyzeUri(
        uri: Uri,
        explicitDisplayName: String? = null,
        isFolderTree: Boolean = false
    ): ExtractedSourceFacts = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path) { "Invalid file URI: $uri" })
            return@withContext analyzeLocalFile(file, explicitDisplayName ?: file.name, startMs)
        }

        if (isFolderTree) {
            val treeDoc = DocumentFile.fromTreeUri(context, uri)
                ?: throw IllegalArgumentException("Unable to open folder URI: $uri")
            val name = explicitDisplayName ?: treeDoc.name ?: "Code Folder"
            return@withContext analyzeDocumentFolder(treeDoc, name, startMs)
        }

        val displayName = explicitDisplayName ?: resolveDisplayName(uri)
        val lowerName = displayName.lowercase(Locale.US)
        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.US).orEmpty()

        return@withContext when {
            lowerName.endsWith(".apk") || mime == "application/vnd.android.package-archive" -> {
                analyzeApkFromStream(displayName, startMs) {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Cannot open APK input stream for $uri")
                }
            }
            else -> {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    analyzeZipStream(stream, displayName, startMs)
                } ?: throw IllegalStateException("Cannot open archive stream for $uri")
            }
        }
    }

    suspend fun analyzeLocalFile(
        file: File,
        explicitDisplayName: String = file.name,
        startMs: Long = System.currentTimeMillis()
    ): ExtractedSourceFacts = withContext(Dispatchers.IO) {
        require(file.exists()) { "Source file does not exist: ${file.absolutePath}" }
        if (file.isDirectory) {
            return@withContext analyzeFileDirectory(file, explicitDisplayName, startMs)
        }
        if (file.name.lowercase(Locale.US).endsWith(".apk")) {
            return@withContext analyzeApkFileDirect(file, explicitDisplayName, startMs)
        }
        FileInputStream(file).use { fis ->
            analyzeZipStream(fis, explicitDisplayName, startMs)
        }
    }

    private fun analyzeApkFromStream(
        displayName: String,
        startMs: Long,
        streamOpener: () -> InputStream
    ): ExtractedSourceFacts {
        val tempApk = File.createTempFile("nightmare_apk_", ".apk", context.cacheDir)
        return try {
            streamOpener().use { input ->
                tempApk.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                }
            }
            analyzeApkFileDirect(tempApk, displayName, startMs)
        } finally {
            tempApk.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun analyzeApkFileDirect(
        apkFile: File,
        displayName: String,
        startMs: Long
    ): ExtractedSourceFacts {
        val acc = ScanAccumulator()
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_META_DATA

        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            }
        }.getOrNull()

        pkgInfo?.let { info ->
            acc.packageName = info.packageName
            info.applicationInfo?.let { appInfo ->
                acc.minSdk = appInfo.minSdkVersion
                acc.targetSdk = appInfo.targetSdkVersion
            }
            info.requestedPermissions?.forEach { acc.permissions.add(it.substringAfterLast('.')) }
            info.activities?.forEach { acc.activities.add(it.name.substringAfterLast('.')) }
            info.services?.forEach { acc.services.add(it.name.substringAfterLast('.')) }
        }

        FileInputStream(apkFile).use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null && acc.fileCount < MAX_FILES) {
                    if (!entry.isDirectory) {
                        val entryName = entry.name
                        acc.fileCount++
                        val entrySize = entry.size.coerceAtLeast(0L)
                        acc.totalBytes += entrySize
                        classifyBinaryOrResourcePath(entryName, acc)

                        if (entryName.endsWith(".dex", ignoreCase = true) ||
                            entryName == "AndroidManifest.xml" ||
                            entryName.endsWith(".json", ignoreCase = true)
                        ) {
                            val bytes = readBoundedBytes(zis, MAX_DEX_READ_BYTES)
                            acc.totalBytes += if (entrySize == 0L) bytes.size.toLong() else 0L
                            val printable = extractPrintableAscii(bytes)
                            scanTextSignals(entryName, printable, acc)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        if (acc.languages.isEmpty()) {
            acc.languages.add("Compiled Android DEX")
        }
        return acc.buildFacts(
            sourceType = ChatSourceEntity.TYPE_APK,
            displayName = displayName,
            durationMs = System.currentTimeMillis() - startMs
        )
    }

    private fun analyzeZipStream(
        inputStream: InputStream,
        displayName: String,
        startMs: Long
    ): ExtractedSourceFacts {
        val acc = ScanAccumulator()
        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null && acc.fileCount < MAX_FILES) {
                if (!entry.isDirectory) {
                    val path = entry.name
                    if (!shouldSkipPath(path)) {
                        acc.fileCount++
                        classifySourcePath(path, acc)
                        if (isInspectableTextFile(path)) {
                            val bytes = readBoundedBytes(zis, MAX_TEXT_FILE_BYTES)
                            acc.totalBytes += bytes.size
                            val content = bytes.toString(Charsets.UTF_8)
                            scanTextSignals(path, content, acc)
                        } else {
                            acc.totalBytes += entry.size.coerceAtLeast(0L)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return acc.buildFacts(
            sourceType = ChatSourceEntity.TYPE_ZIP,
            displayName = displayName,
            durationMs = System.currentTimeMillis() - startMs
        )
    }

    private fun analyzeFileDirectory(
        rootDir: File,
        displayName: String,
        startMs: Long
    ): ExtractedSourceFacts {
        val acc = ScanAccumulator()
        val stack = ArrayDeque<File>()
        stack.addLast(rootDir)
        while (stack.isNotEmpty() && acc.fileCount < MAX_FILES) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (acc.fileCount >= MAX_FILES) break
                val relativePath = child.relativeTo(rootDir).path.replace('\\', '/')
                if (shouldSkipPath(relativePath)) continue
                if (child.isDirectory) {
                    stack.addLast(child)
                } else {
                    acc.fileCount++
                    acc.totalBytes += child.length()
                    classifySourcePath(relativePath, acc)
                    if (isInspectableTextFile(relativePath) && child.length() <= MAX_TEXT_FILE_BYTES) {
                        val content = runCatching { child.readText(Charsets.UTF_8) }.getOrNull()
                        if (content != null) {
                            scanTextSignals(relativePath, content, acc)
                        }
                    }
                }
            }
        }
        return acc.buildFacts(
            sourceType = ChatSourceEntity.TYPE_FOLDER,
            displayName = displayName,
            durationMs = System.currentTimeMillis() - startMs
        )
    }

    private fun analyzeDocumentFolder(
        rootDoc: DocumentFile,
        displayName: String,
        startMs: Long
    ): ExtractedSourceFacts {
        val acc = ScanAccumulator()
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.addLast(rootDoc to "")
        while (stack.isNotEmpty() && acc.fileCount < MAX_FILES) {
            val (dir, prefix) = stack.removeLast()
            for (child in dir.listFiles()) {
                if (acc.fileCount >= MAX_FILES) break
                val name = child.name ?: continue
                val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
                if (shouldSkipPath(relativePath)) continue
                if (child.isDirectory) {
                    stack.addLast(child to relativePath)
                } else {
                    acc.fileCount++
                    acc.totalBytes += child.length().coerceAtLeast(0L)
                    classifySourcePath(relativePath, acc)
                    if (isInspectableTextFile(relativePath) && child.length() <= MAX_TEXT_FILE_BYTES) {
                        val content = runCatching {
                            context.contentResolver.openInputStream(child.uri)?.use { stream ->
                                readBoundedBytes(stream, MAX_TEXT_FILE_BYTES).toString(Charsets.UTF_8)
                            }
                        }.getOrNull()
                        if (content != null) {
                            scanTextSignals(relativePath, content, acc)
                        }
                    }
                }
            }
        }
        return acc.buildFacts(
            sourceType = ChatSourceEntity.TYPE_FOLDER,
            displayName = displayName,
            durationMs = System.currentTimeMillis() - startMs
        )
    }

    private fun classifyBinaryOrResourcePath(path: String, acc: ScanAccumulator) {
        val lower = path.lowercase(Locale.US)
        if (lower.endsWith(".tflite") || lower.endsWith(".onnx") ||
            lower.endsWith(".ptl") || lower.endsWith(".pt") || lower.endsWith(".mlmodel")
        ) {
            acc.mlModels.add(path.substringAfterLast('/'))
        }
        if (lower.contains("libonnxruntime")) acc.dependencies.add("ONNX Runtime (Native)")
        if (lower.contains("libtensorflowlite")) acc.dependencies.add("TensorFlow Lite (Native)")
        if (lower.contains("libmediapipe")) acc.dependencies.add("MediaPipe (Native)")
    }

    private fun classifySourcePath(path: String, acc: ScanAccumulator) {
        val lower = path.lowercase(Locale.US)
        when {
            lower.endsWith(".kt") || lower.endsWith(".kts") -> acc.languages.add("Kotlin")
            lower.endsWith(".java") -> acc.languages.add("Java")
            lower.endsWith(".py") -> acc.languages.add("Python")
            lower.endsWith(".ts") || lower.endsWith(".tsx") -> acc.languages.add("TypeScript")
            lower.endsWith(".js") || lower.endsWith(".jsx") -> acc.languages.add("JavaScript")
            lower.endsWith(".dart") -> acc.languages.add("Dart")
            lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".c") -> acc.languages.add("C/C++")
            lower.endsWith(".rs") -> acc.languages.add("Rust")
            lower.endsWith(".go") -> acc.languages.add("Go")
        }
        if (lower.endsWith(".tflite") || lower.endsWith(".onnx") ||
            lower.endsWith(".ptl") || lower.endsWith(".pt") || lower.endsWith(".mlmodel")
        ) {
            acc.mlModels.add(path.substringAfterLast('/'))
        }
        val fileName = lower.substringAfterLast('/')
        if (fileName.contains("test.") || fileName.endsWith("test.kt") ||
            fileName.endsWith("test.java") || fileName.startsWith("test_") ||
            lower.contains("/test/") || lower.contains("/androidtest/")
        ) {
            acc.testFilesCount++
        }
    }

    private fun scanTextSignals(path: String, text: String, acc: ScanAccumulator) {
        val lowerPath = path.lowercase(Locale.US)
        val lowerText = text.lowercase(Locale.US)
        val fileName = path.substringAfterLast('/')

        // Manifest metadata
        if (lowerPath.endsWith("androidmanifest.xml")) {
            PKG_REGEX.find(text)?.groupValues?.getOrNull(1)?.let { acc.packageName = it }
            PERMISSION_REGEX.findAll(text).forEach { match ->
                acc.permissions.add(match.groupValues[1].substringAfterLast('.'))
            }
            ACTIVITY_REGEX.findAll(text).forEach { match ->
                acc.activities.add(match.groupValues[1].substringAfterLast('.'))
            }
            SERVICE_REGEX.findAll(text).forEach { match ->
                acc.services.add(match.groupValues[1].substringAfterLast('.'))
            }
        }

        // Dependencies & Frameworks
        DEPENDENCY_PATTERNS.forEach { (needle, label) ->
            if (lowerText.contains(needle)) acc.dependencies.add(label)
        }
        DATABASE_PATTERNS.forEach { (needle, label) ->
            if (lowerText.contains(needle)) acc.databaseUsage.add(label)
        }
        AUTH_PATTERNS.forEach { (needle, label) ->
            if (lowerText.contains(needle)) acc.authUsage.add(label)
        }
        NETWORK_PATTERNS.forEach { (needle, label) ->
            if (lowerText.contains(needle)) acc.networkUsage.add(label)
        }

        // Hardcoded Secrets
        SECRET_PATTERNS.forEach { (regex, label) ->
            if (regex.containsMatchIn(text)) {
                acc.hardcodedSecrets.add("$label in $fileName")
            }
        }

        // Mock / Shortcut / Fake-demo indicators
        if (!lowerPath.contains("/test/") && !lowerPath.contains("/androidtest/")) {
            MOCK_PATTERNS.forEach { (regex, label) ->
                if (regex.containsMatchIn(text)) {
                    acc.mockShortcuts.add("$label in $fileName")
                }
            }
        }

        // Keyword extraction for ML claim matching
        TOKEN_REGEX.findAll(lowerText).take(300).forEach { match ->
            val token = match.value
            if (token.length in 3..32) {
                acc.keywords.add(token)
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "artifact"
    }

    private fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val toRead = minOf(buf.size, maxBytes - total)
            val read = input.read(buf, 0, toRead)
            if (read <= 0) break
            out.write(buf, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    private fun extractPrintableAscii(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size / 3)
        var run = 0
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) {
                sb.append(c.toChar())
                run++
            } else if (run > 0) {
                sb.append(' ')
                run = 0
            }
        }
        return sb.toString()
    }

    private fun shouldSkipPath(path: String): Boolean {
        val lower = path.lowercase(Locale.US)
        return lower.contains("/.git/") ||
            lower.startsWith(".git/") ||
            lower.contains("/build/") ||
            lower.contains("/node_modules/") ||
            lower.contains("/.gradle/") ||
            lower.contains("/__pycache__/")
    }

    private fun isInspectableTextFile(path: String): Boolean {
        val lower = path.lowercase(Locale.US)
        return TEXT_EXTENSIONS.any { lower.endsWith(it) }
    }

    private class ScanAccumulator {
        var fileCount: Int = 0
        var totalBytes: Long = 0L
        var packageName: String? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var testFilesCount: Int = 0
        val languages = linkedSetOf<String>()
        val permissions = linkedSetOf<String>()
        val activities = linkedSetOf<String>()
        val services = linkedSetOf<String>()
        val mlModels = linkedSetOf<String>()
        val dependencies = linkedSetOf<String>()
        val databaseUsage = linkedSetOf<String>()
        val authUsage = linkedSetOf<String>()
        val networkUsage = linkedSetOf<String>()
        val hardcodedSecrets = linkedSetOf<String>()
        val mockShortcuts = linkedSetOf<String>()
        val keywords = linkedSetOf<String>()

        fun buildFacts(sourceType: String, displayName: String, durationMs: Long): ExtractedSourceFacts {
            val strengths = mutableListOf<String>()
            val weaknesses = mutableListOf<String>()

            if (mlModels.isNotEmpty()) {
                strengths.add("Real on-device ML model files found: ${mlModels.take(3).joinToString(", ")}")
            }
            if (databaseUsage.any { it.contains("Room") || it.contains("SQLite") || it.contains("DataStore") }) {
                strengths.add("Local persistence implemented via ${databaseUsage.joinToString(", ")}")
            }
            if (authUsage.isNotEmpty()) {
                strengths.add("Authentication layer detected: ${authUsage.joinToString(", ")}")
            }
            if (testFilesCount > 0) {
                strengths.add("Includes $testFilesCount automated test file(s)")
            }
            if (dependencies.any { it.contains("Coroutines") || it.contains("ViewModel") }) {
                strengths.add("Modern asynchronous/lifecycle architecture (${dependencies.filter { it.contains("Coroutines") || it.contains("ViewModel") || it.contains("Hilt") }.joinToString(", ")})")
            }

            if (hardcodedSecrets.isNotEmpty()) {
                weaknesses.add("Security risk: ${hardcodedSecrets.size} hardcoded secret/key pattern(s) (${hardcodedSecrets.take(2).joinToString("; ")})")
            }
            if (mockShortcuts.isNotEmpty()) {
                weaknesses.add("Mock/shortcut indicators found: ${mockShortcuts.take(3).joinToString("; ")}")
            }
            if (testFilesCount == 0 && sourceType != ChatSourceEntity.TYPE_APK) {
                weaknesses.add("Zero automated unit or UI test files detected")
            }
            if (authUsage.isEmpty() && networkUsage.isNotEmpty()) {
                weaknesses.add("Network calls present without an identifiable auth mechanism")
            }
            if (networkUsage.any { it.contains("External AI API") } && mlModels.isEmpty()) {
                weaknesses.add("Depends on external cloud LLM APIs with no bundled offline ML model")
            }
            if (permissions.any { it in DANGEROUS_PERMISSIONS }) {
                val dangerous = permissions.filter { it in DANGEROUS_PERMISSIONS }
                weaknesses.add("Requests sensitive permissions: ${dangerous.joinToString(", ")}")
            }

            var score = 58
            score += (strengths.size * 8).coerceAtMost(28)
            if (fileCount >= 10) score += 6
            score -= (hardcodedSecrets.size * 15).coerceAtMost(35)
            score -= (mockShortcuts.size * 7).coerceAtMost(28)
            if (testFilesCount == 0 && sourceType != ChatSourceEntity.TYPE_APK) score -= 5
            score = score.coerceIn(5, 98)

            return ExtractedSourceFacts(
                sourceType = sourceType,
                displayName = displayName,
                fileCount = fileCount,
                totalBytes = totalBytes,
                languages = languages.toList(),
                packageName = packageName,
                minSdk = minSdk,
                targetSdk = targetSdk,
                permissions = permissions.toList(),
                activities = activities.toList(),
                services = services.toList(),
                mlModels = mlModels.toList(),
                dependencies = dependencies.toList(),
                databaseUsage = databaseUsage.toList(),
                authUsage = authUsage.toList(),
                networkUsage = networkUsage.toList(),
                hardcodedSecrets = hardcodedSecrets.toList(),
                mockShortcuts = mockShortcuts.toList(),
                testFilesCount = testFilesCount,
                strengths = strengths,
                weaknesses = weaknesses,
                codeQualityScore = score,
                rawKeywordSet = keywords.take(800),
                analysisDurationMs = durationMs
            )
        }
    }

    companion object {
        private const val MAX_FILES = 1500
        private const val MAX_TEXT_FILE_BYTES = 256 * 1024
        private const val MAX_DEX_READ_BYTES = 1024 * 1024

        private val TEXT_EXTENSIONS = setOf(
            ".kt", ".kts", ".java", ".xml", ".gradle", ".toml", ".json", ".yaml", ".yml",
            ".py", ".js", ".jsx", ".ts", ".tsx", ".dart", ".cpp", ".c", ".h", ".rs", ".go",
            ".md", ".txt", ".properties"
        )

        private val PKG_REGEX = Regex("""package\s*=\s*"([^"]+)"""")
        private val PERMISSION_REGEX = Regex("""uses-permission[^>]*android:name\s*=\s*"([^"]+)"""")
        private val ACTIVITY_REGEX = Regex("""<activity[^>]*android:name\s*=\s*"([^"]+)"""")
        private val SERVICE_REGEX = Regex("""<service[^>]*android:name\s*=\s*"([^"]+)"""")
        private val TOKEN_REGEX = Regex("""[a-z][a-z0-9_]{2,}""")

        private val DANGEROUS_PERMISSIONS = setOf(
            "READ_CONTACTS", "ACCESS_FINE_LOCATION", "READ_SMS", "SEND_SMS",
            "RECORD_AUDIO", "CAMERA", "READ_CALL_LOG", "WRITE_EXTERNAL_STORAGE"
        )

        private val DEPENDENCY_PATTERNS = listOf(
            "androidx.room" to "Room DB",
            "onnxruntime" to "ONNX Runtime",
            "tensorflow.lite" to "TensorFlow Lite",
            "tflite" to "TensorFlow Lite",
            "pytorch" to "PyTorch",
            "mediapipe" to "MediaPipe",
            "androidx.compose" to "Jetpack Compose",
            "viewbinding" to "ViewBinding",
            "kotlinx.coroutines" to "Kotlin Coroutines",
            "lifecycle-viewmodel" to "AndroidX ViewModel",
            "dagger.hilt" to "Hilt DI",
            "retrofit2" to "Retrofit",
            "okhttp3" to "OkHttp",
            "com.google.firebase" to "Firebase",
            "scikit-learn" to "scikit-learn",
            "fastapi" to "FastAPI",
            "flask" to "Flask",
            "react" to "React"
        )

        private val DATABASE_PATTERNS = listOf(
            "androidx.room" to "Room SQLite",
            "@entity" to "Room Entities",
            "sqliteopenhelper" to "SQLite",
            "sqldelight" to "SQLDelight",
            "firestore" to "Cloud Firestore",
            "realm" to "Realm DB",
            "sharedpreferences" to "SharedPreferences",
            "datastore" to "Jetpack DataStore",
            "supabase" to "Supabase",
            "mongodb" to "MongoDB",
            "postgresql" to "PostgreSQL"
        )

        private val AUTH_PATTERNS = listOf(
            "googlesignin" to "Google Sign-In",
            "firebaseauth" to "Firebase Auth",
            "biometricprompt" to "Biometric Auth",
            "oauth" to "OAuth2",
            "jsonwebtoken" to "JWT Auth",
            "jwt" to "JWT",
            "auth0" to "Auth0",
            "play-services-auth" to "Google Play Auth"
        )

        private val NETWORK_PATTERNS = listOf(
            "api.openai.com" to "External AI API (OpenAI)",
            "generativelanguage.googleapis.com" to "External AI API (Gemini)",
            "api.anthropic.com" to "External AI API (Anthropic)",
            "retrofit" to "Retrofit HTTP",
            "okhttp" to "OkHttp Client",
            "ktor" to "Ktor Client",
            "httpurlconnection" to "HttpURLConnection",
            "websocket" to "WebSocket",
            "axios" to "Axios HTTP",
            "fetch(" to "Fetch HTTP"
        )

        private val SECRET_PATTERNS = listOf(
            Regex("""AIza[0-9A-Za-z\-_]{20,}""") to "Hardcoded Google API Key",
            Regex("""sk-[A-Za-z0-9]{16,}""") to "Hardcoded OpenAI-style Secret Key",
            Regex("""ghp_[A-Za-z0-9]{20,}""") to "Hardcoded GitHub PAT",
            Regex("""AKIA[0-9A-Z]{16}""") to "Hardcoded AWS Access Key",
            Regex("""-----BEGIN (RSA |EC )?PRIVATE KEY-----""") to "Embedded Private Key",
            Regex("""(?i)(api_key|apikey|secret_key|client_secret|db_password)\s*[:=]\s*["'][^"'\s]{8,}["']""") to "Hardcoded Credential Literal"
        )

        private val MOCK_PATTERNS = listOf(
            Regex("""\bdelay\s*\(\s*\d{3,}""") to "Artificial coroutine delay() timer",
            Regex("""Thread\.sleep\s*\(""") to "Blocking Thread.sleep() simulation",
            Regex("""\b(TODO|FIXME|HACK)\b""") to "Unfinished TODO/FIXME marker",
            Regex("""\b(dummyData|mockData|fakeResponse|sampleUsers|hardcoded|stubResponse)\b""", RegexOption.IGNORE_CASE) to "Mock/dummy dataset identifier",
            Regex("""catch\s*\([^)]*\)\s*\{\s*\}""") to "Swallowed empty catch block"
        )
    }
}
