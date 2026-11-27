package com.example.nightmareai.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.nightmareai.data.local.entity.ChatSourceEntity
import com.example.nightmareai.databinding.ItemSourceChipBinding

class ThreadSourceChipAdapter :
    ListAdapter<ChatSourceEntity, ThreadSourceChipAdapter.SourceChipHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceChipHolder {
        val binding = ItemSourceChipBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SourceChipHolder(binding)
    }

    override fun onBindViewHolder(holder: SourceChipHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class SourceChipHolder(
        private val binding: ItemSourceChipBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(source: ChatSourceEntity) {
            binding.tvSourceChipLabel.text = "[${source.sourceType}] ${source.displayName}"
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatSourceEntity>() {
        override fun areItemsTheSame(oldItem: ChatSourceEntity, newItem: ChatSourceEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatSourceEntity, newItem: ChatSourceEntity): Boolean =
            oldItem == newItem
    }
}
