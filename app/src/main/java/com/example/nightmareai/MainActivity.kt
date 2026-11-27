package com.example.nightmareai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nightmareai.auth.AuthSessionManager
import com.example.nightmareai.data.local.NightmareDatabase
import com.example.nightmareai.data.local.entity.ChatThreadEntity
import com.example.nightmareai.data.local.entity.ChatType
import com.example.nightmareai.data.repository.NightmareRepository
import com.example.nightmareai.databinding.ActivityMainBinding
import com.example.nightmareai.engine.LiveAuditOrchestrator
import com.example.nightmareai.engine.OwnLocalSourceAnalyzer
import com.example.nightmareai.engine.OwnMlTruthEngine
import com.example.nightmareai.engine.PipelineState
import com.example.nightmareai.ui.LibraryActivity
import com.example.nightmareai.ui.LoginActivity
import com.example.nightmareai.ui.VoicePitchCaptureHelper
import com.example.nightmareai.ui.adapter.ChatMessageAdapter
import com.example.nightmareai.ui.adapter.ThreadSourceChipAdapter
import com.example.nightmareai.ui.drawer.DrawerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionManager: AuthSessionManager
    private lateinit var repository: NightmareRepository
    private lateinit var orchestrator: LiveAuditOrchestrator
    private lateinit var drawerController: DrawerController
    private lateinit var voiceHelper: VoicePitchCaptureHelper

    private val messageAdapter = ChatMessageAdapter()
    private val sourceChipAdapter = ThreadSourceChipAdapter()

    private var activeGoogleId: String = ""
    private var activeThread: ChatThreadEntity? = null
    private var aboutYouThread: ChatThreadEntity? = null
    private var threadObserveJob: Job? = null

    private val archivePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            takePersistableReadGrant(uri)
            attachSelectedSource(uri, isFolderTree = false)
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            takePersistableReadGrant(uri)
            attachSelectedSource(uri, isFolderTree = true)
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceHelper.startListening()
        } else {
            Toast.makeText(this, "Microphone permission denied.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = AuthSessionManager(this)
        val session = sessionManager.currentSession()
        if (session == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        activeGoogleId = session.googleId

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = NightmareDatabase.getInstance(this)
        repository = NightmareRepository(db)
        orchestrator = LiveAuditOrchestrator(
            repository = repository,
            sourceAnalyzer = OwnLocalSourceAnalyzer(this),
            mlTruthEngine = OwnMlTruthEngine(this),
            scope = lifecycleScope
        )

        setupRecyclerViews()
        setupDrawer(session.displayName)
        setupComposerAndVoice()
        observePipelineState()
        bootstrapWorkspace()
    }

    private fun setupRecyclerViews() {
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
        }
        binding.rvThreadSources.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = sourceChipAdapter
        }
    }

    private fun setupDrawer(displayName: String) {
        drawerController = DrawerController(
            drawerLayout = binding.drawerLayout,
            binding = binding.navDrawerContent,
            repository = repository,
            scope = lifecycleScope,
            onThreadSelected = { thread -> selectThread(thread) },
            onNewNormalChatClicked = {
                lifecycleScope.launch {
                    val created = repository.createNormalChat(
                        googleId = activeGoogleId,
                        title = "Team Audit #${(System.currentTimeMillis() % 1000)}"
                    )
                    selectThread(created)
                }
            },
            onAboutYouClicked = {
                aboutYouThread?.let { selectThread(it) }
            },
            onLibraryClicked = {
                startActivity(Intent(this, LibraryActivity::class.java))
            },
            onNewMasterChatClicked = {
                lifecycleScope.launch {
                    val created = repository.createSecondaryMasterChat(activeGoogleId)
                    selectThread(created)
                }
            },
            onLogoutClicked = {
                sessionManager.logout()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        )
        drawerController.bindAccount(activeGoogleId, displayName)
        binding.btnMenuDrawer.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun setupComposerAndVoice() {
        voiceHelper = VoicePitchCaptureHelper(
            context = this,
            onPartialTranscript = { partial ->
                binding.etPitchInput.setText(partial)
                binding.etPitchInput.setSelection(partial.length)
            },
            onFinalTranscript = { finalText ->
                binding.etPitchInput.setText(finalText)
                binding.etPitchInput.setSelection(finalText.length)
            },
            onListeningStateChanged = { listening ->
                binding.btnVoiceMic.text = if (listening) "⏹" else "🎙"
            },
            onErrorMessage = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        )

        binding.etPitchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val thread = activeThread ?: return
                // Silent speculative pre-computation in background (no premature UI display)
                orchestrator.onPitchDraftChanged(
                    googleId = activeGoogleId,
                    threadId = thread.id,
                    draftText = s?.toString().orEmpty()
                )
            }
        })

        binding.btnAttachSource.setOnClickListener { anchor -> showAttachSourceMenu(anchor) }

        binding.btnVoiceMic.setOnClickListener {
            if (voiceHelper.isListening()) {
                voiceHelper.stopListening()
            } else {
                val permGranted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (permGranted) {
                    voiceHelper.startListening()
                } else {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        binding.btnSend.setOnClickListener {
            val thread = activeThread ?: return@setOnClickListener
            val text = binding.etPitchInput.text?.toString().orEmpty().trim()
            if (text.isEmpty()) return@setOnClickListener
            binding.btnSend.isEnabled = false
            binding.etPitchInput.setText("")

            lifecycleScope.launch {
                runCatching {
                    orchestrator.submitPitchAndVerify(activeGoogleId, thread.id, text)
                }.onFailure { err ->
                    Toast.makeText(
                        this@MainActivity,
                        err.message ?: "Verification error",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                binding.btnSend.isEnabled = true
            }
        }
    }

    private fun observePipelineState() {
        lifecycleScope.launch {
            orchestrator.pipelineState.collectLatest { state ->
                binding.tvPipelineIndicator.text = when (state) {
                    PipelineState.IDLE -> "OFFLINE ML"
                    PipelineState.ANALYZING_SOURCE -> "PARSING IO..."
                    PipelineState.SOURCE_READY -> "SOURCE READY"
                }
            }
        }
    }

    private fun bootstrapWorkspace() {
        lifecycleScope.launch {
            val bootstrap = repository.ensureWorkspaceInitialized(activeGoogleId)
            aboutYouThread = bootstrap.aboutYouThread
            selectThread(bootstrap.primaryMasterThread)
        }
    }

    private fun selectThread(thread: ChatThreadEntity) {
        activeThread = thread
        drawerController.setSelectedThreadId(thread.id)
        binding.tvToolbarTitle.text = thread.title
        binding.tvChatScopeBadge.text = when (thread.chatType) {
            ChatType.GLOBAL_ABOUT_YOU -> "GLOBAL CROSS-AUDIT"
            ChatType.MASTER -> "ISOLATED MASTER AUDIT"
            ChatType.NORMAL -> "ISOLATED TEAM CHAT"
        }
        binding.btnAttachSource.visibility =
            if (thread.chatType == ChatType.GLOBAL_ABOUT_YOU) View.GONE else View.VISIBLE

        threadObserveJob?.cancel()
        threadObserveJob = lifecycleScope.launch {
            launch {
                repository.observeMessagesForThread(activeGoogleId, thread.id).collectLatest { msgs ->
                    messageAdapter.submitList(msgs) {
                        if (msgs.isNotEmpty()) {
                            binding.rvMessages.scrollToPosition(msgs.size - 1)
                        }
                    }
                    binding.layoutEmptyChat.visibility = if (msgs.isEmpty()) View.VISIBLE else View.GONE
                }
            }
            launch {
                repository.observeSourcesForThread(activeGoogleId, thread.id).collectLatest { sources ->
                    sourceChipAdapter.submitList(sources)
                    binding.rvThreadSources.visibility =
                        if (sources.isEmpty() || thread.chatType == ChatType.GLOBAL_ABOUT_YOU) {
                            View.GONE
                        } else {
                            View.VISIBLE
                        }
                }
            }
        }
    }

    private fun showAttachSourceMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Attach .zip Archive")
        menu.menu.add(0, 2, 1, "Attach .apk Package")
        menu.menu.add(0, 3, 2, "Attach Code Folder")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> archivePickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                2 -> archivePickerLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
                3 -> folderPickerLauncher.launch(null)
            }
            true
        }
        menu.show()
    }

    private fun attachSelectedSource(uri: Uri, isFolderTree: Boolean) {
        val thread = activeThread ?: return
        orchestrator.attachSourceUriAsync(
            googleId = activeGoogleId,
            threadId = thread.id,
            uri = uri,
            isFolderTree = isFolderTree
        ) { result ->
            result.onSuccess { source ->
                Toast.makeText(
                    this,
                    "Parsed ${source.displayName} offline.",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this,
                    "Failed to inspect source: ${err.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun takePersistableReadGrant(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    override fun onDestroy() {
        voiceHelper.stopListening()
        super.onDestroy()
    }
}
