package com.example.nightmareai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.nightmareai.auth.AuthSessionManager
import com.example.nightmareai.data.local.NightmareDatabase
import com.example.nightmareai.data.local.entity.ChatMessageEntity
import com.example.nightmareai.data.local.entity.ChatSourceEntity
import com.example.nightmareai.data.repository.NightmareRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountIsolationTest {

    private lateinit var context: Context
    private lateinit var db: NightmareDatabase
    private lateinit var repository: NightmareRepository
    private lateinit var sessionManager: AuthSessionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NightmareDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NightmareRepository(db)
        sessionManager = AuthSessionManager(context)
        sessionManager.logout()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun logoutPreservesRowsAndAccountSwitchNeverMergesData() = runBlocking {
        // 1. Login via Open Access
        val sessionA = sessionManager.loginOpenAccess()
        assertEquals(AuthSessionManager.OPEN_ACCESS_GOOGLE_ID, sessionA.googleId)
        assertTrue(sessionA.isOpenAccess)

        val bootstrapA = repository.ensureWorkspaceInitialized(sessionA.googleId)
        val chatA = repository.createNormalChat(sessionA.googleId, "Account A Team Alpha")
        repository.attachSourceToThread(
            googleId = sessionA.googleId,
            threadId = chatA.id,
            sourceType = ChatSourceEntity.TYPE_ZIP,
            displayName = "alpha.zip",
            localUriString = "file:///alpha.zip",
            extractedFactsJson = "{}"
        )
        repository.appendMessage(
            googleId = sessionA.googleId,
            threadId = chatA.id,
            sender = ChatMessageEntity.SENDER_USER,
            content = "Alpha uses local Room"
        )

        // 2. Logout clears ONLY AuthSessionManager
        sessionManager.logout()
        assertFalse(sessionManager.isLoggedIn())
        assertNull(sessionManager.currentSession())

        // 3. Login with a completely different googleId
        val sessionB = sessionManager.onGoogleSignInSuccess(
            googleId = "google_judge_999",
            displayName = "Judge Beta",
            email = "beta@hackathon.org"
        )
        assertEquals("google_judge_999", sessionB.googleId)
        assertFalse(sessionB.isOpenAccess)

        val bootstrapB = repository.ensureWorkspaceInitialized(sessionB.googleId)
        assertNotNull(bootstrapB.aboutYouThread)
        assertNotNull(bootstrapB.primaryMasterThread)

        // Account B MUST see zero chats/sources from Account A
        val normalChatsB = repository.observeNormalThreads(sessionB.googleId).first()
        val allSourcesB = repository.observeAllSourcesForAccount(sessionB.googleId).first()
        assertTrue(normalChatsB.isEmpty())
        assertTrue(allSourcesB.isEmpty())

        val chatB = repository.createNormalChat(sessionB.googleId, "Account B Team Omega")
        repository.attachSourceToThread(
            googleId = sessionB.googleId,
            threadId = chatB.id,
            sourceType = ChatSourceEntity.TYPE_APK,
            displayName = "omega.apk",
            localUriString = "file:///omega.apk",
            extractedFactsJson = "{}"
        )

        // Account B sees only omega.apk
        assertEquals(listOf("omega.apk"), repository.observeAllSourcesForAccount(sessionB.googleId).first().map { it.displayName })

        // 4. Switch back to Open Access: Account A's data is untouched and never merged with B
        sessionManager.logout()
        val reloginA = sessionManager.loginOpenAccess()
        val normalChatsA = repository.observeNormalThreads(reloginA.googleId).first()
        val allSourcesA = repository.observeAllSourcesForAccount(reloginA.googleId).first()
        val messagesA = repository.getMessagesForThreadSync(reloginA.googleId, chatA.id)

        assertEquals(listOf("Account A Team Alpha"), normalChatsA.map { it.title })
        assertEquals(listOf("alpha.zip"), allSourcesA.map { it.displayName })
        assertEquals(listOf("Alpha uses local Room"), messagesA.map { it.content })
        assertEquals(bootstrapA.aboutYouThread.id, repository.ensureWorkspaceInitialized(reloginA.googleId).aboutYouThread.id)
    }
}
