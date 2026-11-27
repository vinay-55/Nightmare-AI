package com.example.nightmareai.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.nightmareai.R
import com.example.nightmareai.data.local.entity.ChatThreadEntity
import com.example.nightmareai.databinding.ItemDrawerChatBinding

class DrawerChatAdapter(
    private val onThreadClicked: (ChatThreadEntity) -> Unit
) : ListAdapter<ChatThreadEntity, DrawerChatAdapter.ChatViewHolder>(DiffCallback) {

    private var selectedThreadId: Long = -1L

    fun setSelectedThreadId(threadId: Long) {
        if (selectedThreadId == threadId) return
        selectedThreadId = threadId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val binding = ItemDrawerChatBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id == selectedThreadId)
    }

    inner class ChatViewHolder(
        private val binding: ItemDrawerChatBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(thread: ChatThreadEntity, isSelected: Boolean) {
            binding.tvDrawerChatTitle.text = thread.title
            binding.tvDrawerChatType.text = thread.chatType.name
            val strokeColor = if (isSelected) {
                ContextCompat.getColor(binding.root.context, R.color.nightmare_primary)
            } else {
                ContextCompat.getColor(binding.root.context, R.color.nightmare_stroke)
            }
            binding.cardDrawerChat.strokeColor = strokeColor
            binding.root.setOnClickListener { onThreadClicked(thread) }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatThreadEntity>() {
        override fun areItemsTheSame(oldItem: ChatThreadEntity, newItem: ChatThreadEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatThreadEntity, newItem: ChatThreadEntity): Boolean =
            oldItem == newItem
    }
}
