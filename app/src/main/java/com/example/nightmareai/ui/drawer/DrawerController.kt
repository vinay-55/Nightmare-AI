package com.example.nightmareai.ui.drawer

import android.view.View
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nightmareai.R
import com.example.nightmareai.data.local.entity.ChatThreadEntity
import com.example.nightmareai.data.repository.NightmareRepository
import com.example.nightmareai.databinding.NavDrawerContentBinding
import com.example.nightmareai.ui.adapter.DrawerChatAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Controls the Navigation Drawer with exact top-to-bottom hierarchy:
 * 1. + New Chat
 * 2. About You (GLOBAL_ABOUT_YOU)
 * 3. Library
 * 4. Master Chat collapsible section (starts View.GONE, toggles via header/arrow)
 *    - + New Master Chat
 *    - 1 Large Primary Master Chat Card
 *    - RecyclerView (DiffUtil) of additional smaller Master Chats
 * 5. Chats section (ListAdapter + DiffUtil)
 */
class DrawerController(
    private val drawerLayout: DrawerLayout,
    private val binding: NavDrawerContentBinding,
    private val repository: NightmareRepository,
    private val scope: CoroutineScope,
    private val onThreadSelected: (ChatThreadEntity) -> Unit,
    private val onNewNormalChatClicked: () -> Unit,
    private val onAboutYouClicked: () -> Unit,
    private val onLibraryClicked: () -> Unit,
    private val onNewMasterChatClicked: () -> Unit,
    private val onLogoutClicked: () -> Unit
) {

    private val secondaryMasterAdapter = DrawerChatAdapter { thread ->
        onThreadSelected(thread)
        drawerLayout.closeDrawers()
    }

    private val normalChatsAdapter = DrawerChatAdapter { thread ->
        onThreadSelected(thread)
        drawerLayout.closeDrawers()
    }

    private var primaryMasterThread: ChatThreadEntity? = null
    private var selectedThreadId: Long = -1L
    private var observationJob: Job? = null
    private var isMasterExpanded: Boolean = false

    init {
        setupViews()
    }

    private fun setupViews() {
        binding.rvSecondaryMasterChats.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = secondaryMasterAdapter
        }
        binding.rvNormalChats.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = normalChatsAdapter
        }

        // Master Chat section MUST start hidden/collapsed (View.GONE) when app opens
        setMasterSectionExpanded(false)

        binding.layoutMasterHeader.setOnClickListener {
            setMasterSectionExpanded(!isMasterExpanded)
        }
        binding.tvMasterArrow.setOnClickListener {
            setMasterSectionExpanded(!isMasterExpanded)
        }

        binding.btnNewChat.setOnClickListener {
            onNewNormalChatClicked()
            drawerLayout.closeDrawers()
        }
        binding.btnAboutYou.setOnClickListener {
            onAboutYouClicked()
            drawerLayout.closeDrawers()
        }
        binding.btnLibrary.setOnClickListener {
            onLibraryClicked()
            drawerLayout.closeDrawers()
        }
        binding.btnNewMasterChat.setOnClickListener {
            onNewMasterChatClicked()
            drawerLayout.closeDrawers()
        }
        binding.cardPrimaryMasterChat.setOnClickListener {
            primaryMasterThread?.let {
                onThreadSelected(it)
                drawerLayout.closeDrawers()
            }
        }
        binding.btnLogout.setOnClickListener {
            drawerLayout.closeDrawers()
            onLogoutClicked()
        }
    }

    fun setMasterSectionExpanded(expanded: Boolean) {
        isMasterExpanded = expanded
        binding.layoutMasterCollapsible.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.tvMasterArrow.text = binding.root.context.getString(
            if (expanded) R.string.arrow_up else R.string.arrow_down
        )
    }

    fun isMasterSectionExpanded(): Boolean = isMasterExpanded

    fun bindAccount(googleId: String, displayName: String) {
        binding.tvAccountLabel.text = "$displayName ($googleId)"
        observationJob?.cancel()
        observationJob = scope.launch {
            launch {
                repository.observePrimaryMasterThread(googleId).collectLatest { primary ->
                    primaryMasterThread = primary
                    if (primary != null) {
                        binding.tvPrimaryMasterTitle.text = primary.title
                        updatePrimaryCardStroke()
                    }
                }
            }
            launch {
                repository.observeSecondaryMasterThreads(googleId).collectLatest { secondaryList ->
                    secondaryMasterAdapter.submitList(secondaryList)
                }
            }
            launch {
                repository.observeNormalThreads(googleId).collectLatest { normalList ->
                    normalChatsAdapter.submitList(normalList)
                }
            }
        }
    }

    fun setSelectedThreadId(threadId: Long) {
        selectedThreadId = threadId
        secondaryMasterAdapter.setSelectedThreadId(threadId)
        normalChatsAdapter.setSelectedThreadId(threadId)
        updatePrimaryCardStroke()
    }

    private fun updatePrimaryCardStroke() {
        val ctx = binding.root.context
        val isPrimarySelected = primaryMasterThread?.id == selectedThreadId
        val colorRes = if (isPrimarySelected) R.color.nightmare_secondary else R.color.nightmare_primary
        binding.cardPrimaryMasterChat.strokeColor = ContextCompat.getColor(ctx, colorRes)
    }
}
