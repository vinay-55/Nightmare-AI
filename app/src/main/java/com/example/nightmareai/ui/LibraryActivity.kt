package com.example.nightmareai.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nightmareai.auth.AuthSessionManager
import com.example.nightmareai.data.local.NightmareDatabase
import com.example.nightmareai.data.repository.NightmareRepository
import com.example.nightmareai.databinding.ActivityLibraryBinding
import com.example.nightmareai.ui.adapter.LibrarySourceAdapter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Displays every locally analyzed .zip, .apk, and code folder strictly scoped
 * to the active [AuthSessionManager] googleId.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var sessionManager: AuthSessionManager
    private lateinit var repository: NightmareRepository
    private val adapter = LibrarySourceAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionManager = AuthSessionManager(this)
        val activeGoogleId = sessionManager.currentSession()?.googleId
        if (activeGoogleId == null) {
            finish()
            return
        }

        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = NightmareRepository(NightmareDatabase.getInstance(this))
        binding.btnBackLibrary.setOnClickListener { finish() }

        binding.rvLibrarySources.layoutManager = LinearLayoutManager(this)
        binding.rvLibrarySources.adapter = adapter

        lifecycleScope.launch {
            repository.observeAllSourcesForAccount(activeGoogleId).collectLatest { sources ->
                adapter.submitList(sources)
                binding.tvEmptyLibrary.visibility = if (sources.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
