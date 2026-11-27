package com.example.nightmareai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.nightmareai.data.local.NightmareDatabase
import com.example.nightmareai.data.repository.NightmareRepository
import com.example.nightmareai.domain.model.ClaimVerdict
import com.example.nightmareai.engine.LiveAuditOrchestrator
import com.example.nightmareai.engine.OwnLocalSourceAnalyzer
import com.example.nightmareai.engine.OwnMlTruthEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeculativePipelineAndMlEngineTest {

    private lateinit var context: Context
    private lateinit var db: NightmareDatabase
    private lateinit var repository: NightmareRepository
    private lateinit var analyzer: OwnLocalSourceAnalyzer
    private lateinit var mlEngine: OwnMlTruthEngine
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NightmareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NightmareRepository(db)
        analyzer = OwnLocalSourceAnalyzer(context)
        mlEngine = OwnMlTruthEngine(context)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        db.close()
    }

    @Test
    fun immediateSourceParseSilentSpeculativePrecomputeAndTwoSectionReport() = runBlocking {
        val googleId = "open_access_user"
        val bootstrap = repository.ensureWorkspaceInitialized(googleId)
        val chat = repository.createNormalChat(googleId, "Team Nebula Audit")

        val zipFile = createHackathonSampleZip()
        try {
            val orchestrator = LiveAuditOrchestrator(repository, analyzer, mlEngine, testScope)

            // 1. Immediate Dispatchers.IO source attachment & parsing
            val parseJob = orchestrator.attachLocalFileAsync(googleId, chat.id, zipFile)
            parseJob.join()

            val attachedSources = repository.getSourcesForThreadSync(googleId, chat.id)
            assertEquals(1, attachedSources.size)
            assertEquals("sample_project.zip", attachedSources.first().displayName)

            // 2. Silent speculative pre-computation after 180ms debounce (must NOT write to Room before Send)
            val pitch = "Our app is 100% offline with zero external APIs. We have no mocks and full unit tests."
            orchestrator.onPitchDraftChanged(googleId, chat.id, pitch, debounceMs = 180L)

            // Wait until speculative cache warms up
            var attempts = 0
            while (!orchestrator.hasWarmSpeculativeReport(googleId, chat.id, pitch) && attempts < 40) {
                delay(25L)
                attempts++
            }
            assertTrue("Speculative ML report should warm up in background", orchestrator.hasWarmSpeculativeReport(googleId, chat.id, pitch))
            assertTrue("Speculative phase must NOT persist premature messages", repository.getMessagesForThreadSync(googleId, chat.id).isEmpty())

            // 3. Pressing Send finalizes in <30ms from speculative cache and persists Two-Section Report
            val report = orchestrator.submitPitchAndVerify(googleId, chat.id, pitch)
            assertTrue(report.usedSpeculativeCache)
            assertTrue("Expected fast cached finalization, got ${report.analysisLatencyMs}ms", report.analysisLatencyMs < 150L)

            // Section 1 must detect lies/contradictions in spoken pitch
            val verdicts = report.section1TalkedAbout.claimEvaluations.map { it.verdict }
            assertTrue("Expected LIE verdict for contradictory offline/mock/test claims", ClaimVerdict.LIE in verdicts)

            // Section 2 must surface unmentioned Room DB & hidden secrets/mocks
            val hiddenProsJoined = report.section2DidntTalkAbout.hiddenAdvantages.joinToString("\n")
            val hiddenConsJoined = report.section2DidntTalkAbout.hiddenDisadvantages.joinToString("\n")
            assertTrue("Expected Room persistence in hidden advantages: $hiddenProsJoined", hiddenProsJoined.contains("Room", ignoreCase = true))
            assertTrue("Expected hardcoded secret or mock in hidden disadvantages: $hiddenConsJoined", hiddenConsJoined.contains("Hardcoded", ignoreCase = true) || hiddenConsJoined.contains("delay", ignoreCase = true))

            // 4. Verify About You global ranking synthesizes across threads
            val globalReport = orchestrator.submitPitchAndVerify(
                googleId = googleId,
                threadId = bootstrap.aboutYouThread.id,
                pitchText = "Rank all teams by truth score"
            )
            assertTrue(globalReport.section1TalkedAbout.claimEvaluations.isNotEmpty())
            assertTrue(globalReport.overallVerdict.startsWith("LEADER:"))
        } finally {
            zipFile.delete()
        }
    }

    private fun createHackathonSampleZip(): File {
        val file = File.createTempFile("nightmare_test_", ".zip", context.cacheDir)
        val dest = File(file.parentFile, "sample_project.zip")
        if (dest.exists()) dest.delete()
        file.renameTo(dest)

        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            fun addEntry(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            addEntry(
                "app/src/main/AndroidManifest.xml",
                """
                <manifest package="com.hackathon.nebula">
                    <uses-permission android:name="android.permission.INTERNET" />
                    <application>
                        <activity android:name=".MainActivity" />
                    </application>
                </manifest>
                """.trimIndent()
            )
            addEntry(
                "app/build.gradle.kts",
                """
                dependencies {
                    implementation("androidx.room:room-runtime:2.6.1")
                    implementation("com.squareup.retrofit2:retrofit:2.11.0")
                }
                """.trimIndent()
            )
            addEntry(
                "app/src/main/java/com/hackathon/nebula/AiRepository.kt",
                """
                package com.hackathon.nebula
                import androidx.room.Entity
                import kotlinx.coroutines.delay

                @Entity
                data class UserNote(val id: Long)

                class AiRepository {
                    private val apiKey = "AIzaSyDummyHardcodedKey1234567890"
                    private val endpoint = "https://api.openai.com/v1/chat/completions"

                    suspend fun generateReply(): String {
                        // TODO: replace dummyData before finals
                        val dummyData = "fakeResponse"
                        delay(2000)
                        return dummyData
                    }
                }
                """.trimIndent()
            )
        }
        return dest
    }
}
