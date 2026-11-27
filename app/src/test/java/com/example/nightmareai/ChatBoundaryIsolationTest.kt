package com.example.nightmareai

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.drawerlayout.widget.DrawerLayout
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.nightmareai.data.local.NightmareDatabase
import com.example.nightmareai.data.local.entity.ChatMessageEntity
import com.example.nightmareai.data.local.entity.ChatSourceEntity
import com.example.nightmareai.data.repository.NightmareRepository
import com.example.nightmareai.databinding.NavDrawerContentBinding
import com.example.nightmareai.ui.drawer.DrawerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatBoundaryIsolationTest {

    private lateinit var context: Context
    private lateinit var db: NightmareDatabase
    private lateinit var repository: NightmareRepository
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.setTheme(R.style.Theme_NightmareAI)
        db = Room.inMemoryDatabaseBuilder(context, NightmareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NightmareRepository(db)
    }

    @After
    fun tearDown() {
        testScope.cancel()
        db.close()
    }

    @Test
    fun masterAndNormalChatsAreStrictlyThreadIsolatedWhileAboutYouIsGlobal() = runBlocking {
        val googleId = "judge_alpha"
        val bootstrap = repository.ensureWorkspaceInitialized(googleId)
        val primaryMaster = bootstrap.primaryMasterThread
        val secondaryMaster1 = repository.createSecondaryMasterChat(googleId, "Master Track A")
        val secondaryMaster2 = repository.createSecondaryMasterChat(googleId, "Master Track B")
        val normal1 = repository.createNormalChat(googleId, "Team One")
        val normal2 = repository.createNormalChat(googleId, "Team Two")

        // Seed distinct data in each isolated thread
        repository.appendMessage(googleId, primaryMaster.id, ChatMessageEntity.SENDER_USER, "Primary Master Msg")
        repository.appendMessage(googleId, secondaryMaster1.id, ChatMessageEntity.SENDER_USER, "Master 1 Msg")
        repository.appendMessage(googleId, secondaryMaster2.id, ChatMessageEntity.SENDER_USER, "Master 2 Msg")
        repository.appendMessage(googleId, normal1.id, ChatMessageEntity.SENDER_USER, "Normal 1 Msg")
        repository.appendMessage(googleId, normal2.id, ChatMessageEntity.SENDER_USER, "Normal 2 Msg")

        repository.attachSourceToThread(googleId, secondaryMaster1.id, ChatSourceEntity.TYPE_ZIP, "m1.zip", "file:///m1", "{}")
        repository.attachSourceToThread(googleId, normal1.id, ChatSourceEntity.TYPE_APK, "n1.apk", "file:///n1", "{}")

        // Verify strict per-thread isolation
        assertEquals(listOf("Primary Master Msg"), repository.getMessagesForThreadSync(googleId, primaryMaster.id).map { it.content })
        assertEquals(listOf("Master 1 Msg"), repository.getMessagesForThreadSync(googleId, secondaryMaster1.id).map { it.content })
        assertEquals(listOf("Master 2 Msg"), repository.getMessagesForThreadSync(googleId, secondaryMaster2.id).map { it.content })
        assertEquals(listOf("Normal 1 Msg"), repository.getMessagesForThreadSync(googleId, normal1.id).map { it.content })
        assertEquals(listOf("Normal 2 Msg"), repository.getMessagesForThreadSync(googleId, normal2.id).map { it.content })

        assertEquals(listOf("m1.zip"), repository.getSourcesForThreadSync(googleId, secondaryMaster1.id).map { it.displayName })
        assertTrue(repository.getSourcesForThreadSync(googleId, secondaryMaster2.id).isEmpty())
        assertEquals(listOf("n1.apk"), repository.getSourcesForThreadSync(googleId, normal1.id).map { it.displayName })
        assertTrue(repository.getSourcesForThreadSync(googleId, normal2.id).isEmpty())

        // Cross-chat global snapshot MUST be denied for MASTER and NORMAL threads
        for (nonGlobalId in listOf(primaryMaster.id, secondaryMaster1.id, normal1.id)) {
            try {
                repository.getGlobalAuditSnapshotForAboutYou(googleId, nonGlobalId)
                fail("Expected SecurityException for non-GLOBAL_ABOUT_YOU threadId=$nonGlobalId")
            } catch (_: SecurityException) {
                // expected
            }
        }

        // Cross-chat global snapshot succeeds ONLY for About You
        val globalSnapshot = repository.getGlobalAuditSnapshotForAboutYou(googleId, bootstrap.aboutYouThread.id)
        assertEquals(5, globalSnapshot.threads.size)
        assertEquals(5, globalSnapshot.messagesByThread.values.flatten().size)
        assertEquals(2, globalSnapshot.sourcesByThread.values.flatten().size)
    }

    @Test
    fun masterChatDrawerSectionStartsCollapsedGoneAndTogglesVisible() {
        val drawerLayout = DrawerLayout(context)
        val drawerBinding = NavDrawerContentBinding.inflate(LayoutInflater.from(context))
        val controller = DrawerController(
            drawerLayout = drawerLayout,
            binding = drawerBinding,
            repository = repository,
            scope = testScope,
            onThreadSelected = {},
            onNewNormalChatClicked = {},
            onAboutYouClicked = {},
            onLibraryClicked = {},
            onNewMasterChatClicked = {},
            onLogoutClicked = {}
        )

        // Must start hidden/collapsed (View.GONE)
        assertFalse(controller.isMasterSectionExpanded())
        assertEquals(View.GONE, drawerBinding.layoutMasterCollapsible.visibility)
        assertEquals("▼", drawerBinding.tvMasterArrow.text.toString())

        // Clicking header expands section revealing + New Master Chat, Primary Master Card, and N Master RecyclerView
        drawerBinding.layoutMasterHeader.performClick()
        assertTrue(controller.isMasterSectionExpanded())
        assertEquals(View.VISIBLE, drawerBinding.layoutMasterCollapsible.visibility)
        assertEquals("▲", drawerBinding.tvMasterArrow.text.toString())

        // Clicking down-arrow collapses back to GONE
        drawerBinding.tvMasterArrow.performClick()
        assertFalse(controller.isMasterSectionExpanded())
        assertEquals(View.GONE, drawerBinding.layoutMasterCollapsible.visibility)
    }
}
