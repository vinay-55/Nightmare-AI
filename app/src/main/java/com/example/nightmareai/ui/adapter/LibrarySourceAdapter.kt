package com.example.nightmareai.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.nightmareai.data.local.entity.ChatSourceEntity
import com.example.nightmareai.databinding.ItemLibrarySourceBinding
import com.example.nightmareai.domain.model.ExtractedSourceFacts

class LibrarySourceAdapter :
    ListAdapter<ChatSourceEntity, LibrarySourceAdapter.LibraryViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LibraryViewHolder {
        val binding = ItemLibrarySourceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return LibraryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LibraryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LibraryViewHolder(
        private val binding: ItemLibrarySourceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entity: ChatSourceEntity) {
            binding.tvLibSourceType.text = entity.sourceType
            binding.tvLibSourceName.text = entity.displayName
            val facts = ExtractedSourceFacts.fromJson(entity.extractedFactsJson)
            if (facts != null) {
                binding.tvLibQualityScore.text = "Q: ${facts.codeQualityScore}/100"
                val lines = buildList {
                    add("Files: ${facts.fileCount} • Languages: ${facts.languages.ifEmpty { listOf("N/A") }.joinToString(", ")}")
                    if (facts.mlModels.isNotEmpty()) add("ML Models: ${facts.mlModels.joinToString(", ")}")
                    if (facts.databaseUsage.isNotEmpty()) add("DB: ${facts.databaseUsage.joinToString(", ")}")
                    if (facts.authUsage.isNotEmpty()) add("Auth: ${facts.authUsage.joinToString(", ")}")
                    add("Secrets: ${facts.hardcodedSecrets.size} • Mock shortcuts: ${facts.mockShortcuts.size} • Tests: ${facts.testFilesCount}")
                }
                binding.tvLibFactsSummary.text = lines.joinToString("\n")
            } else {
                binding.tvLibQualityScore.text = "Q: --"
                binding.tvLibFactsSummary.text = entity.localUriString
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatSourceEntity>() {
        override fun areItemsTheSame(oldItem: ChatSourceEntity, newItem: ChatSourceEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatSourceEntity, newItem: ChatSourceEntity): Boolean =
            oldItem == newItem
    }
}
