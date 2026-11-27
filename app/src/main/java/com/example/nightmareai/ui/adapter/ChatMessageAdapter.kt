package com.example.nightmareai.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.nightmareai.R
import com.example.nightmareai.data.local.entity.ChatMessageEntity
import com.example.nightmareai.databinding.ItemClaimEvaluationBinding
import com.example.nightmareai.databinding.ItemMessageReportBinding
import com.example.nightmareai.databinding.ItemMessageUserBinding
import com.example.nightmareai.domain.model.ClaimVerdict
import com.example.nightmareai.domain.model.TwoSectionTruthReport

class ChatMessageAdapter : ListAdapter<ChatMessageEntity, RecyclerView.ViewHolder>(DiffCallback) {

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).sender == ChatMessageEntity.SENDER_USER) {
            VIEW_TYPE_USER
        } else {
            VIEW_TYPE_REPORT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            UserMessageViewHolder(ItemMessageUserBinding.inflate(inflater, parent, false))
        } else {
            ReportMessageViewHolder(ItemMessageReportBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is UserMessageViewHolder -> holder.bind(item)
            is ReportMessageViewHolder -> holder.bind(item)
        }
    }

    class UserMessageViewHolder(
        private val binding: ItemMessageUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessageEntity) {
            binding.tvUserMessage.text = message.content
        }
    }

    class ReportMessageViewHolder(
        private val binding: ItemMessageReportBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessageEntity) {
            val report = TwoSectionTruthReport.fromJson(message.twoSectionReportJson)
            if (report == null) {
                binding.tvTruthScore.text = "INFO"
                binding.tvOverallVerdict.text = "Assistant"
                binding.tvLatencyBadge.text = ""
                binding.tvSummaryHeadline.text = message.content
                binding.layoutClaimEvaluations.removeAllViews()
                binding.tvClaimedPros.visibility = View.GONE
                binding.tvClaimedCons.visibility = View.GONE
                binding.tvHiddenAdvantages.visibility = View.GONE
                binding.tvHiddenDisadvantages.visibility = View.GONE
                return
            }

            val ctx = binding.root.context
            binding.tvTruthScore.text = "${report.overallTruthScore}/100"
            binding.tvOverallVerdict.text = report.overallVerdict
            val cacheTag = if (report.usedSpeculativeCache) "⚡ Cached" else "Local ML"
            binding.tvLatencyBadge.text = "$cacheTag • ${report.analysisLatencyMs}ms"
            binding.tvSummaryHeadline.text = report.summaryHeadline

            // Section 1: What the User Talked About
            binding.layoutClaimEvaluations.removeAllViews()
            val inflater = LayoutInflater.from(ctx)
            report.section1TalkedAbout.claimEvaluations.forEach { eval ->
                val claimBinding = ItemClaimEvaluationBinding.inflate(
                    inflater,
                    binding.layoutClaimEvaluations,
                    false
                )
                claimBinding.tvClaimVerdictBadge.text = eval.verdict.label
                val (bgColor, fgColor) = when (eval.verdict) {
                    ClaimVerdict.TRUTH -> R.color.verdict_truth_bg to R.color.verdict_truth_fg
                    ClaimVerdict.EXAGGERATION -> R.color.verdict_exag_bg to R.color.verdict_exag_fg
                    ClaimVerdict.LIE -> R.color.verdict_lie_bg to R.color.verdict_lie_fg
                    ClaimVerdict.UNVERIFIED -> R.color.verdict_unverified_bg to R.color.verdict_unverified_fg
                }
                claimBinding.tvClaimVerdictBadge.setBackgroundColor(ContextCompat.getColor(ctx, bgColor))
                claimBinding.tvClaimVerdictBadge.setTextColor(ContextCompat.getColor(ctx, fgColor))
                claimBinding.tvClaimConfidence.text = "${(eval.mlConfidence * 100).toInt()}% ML"
                claimBinding.tvClaimQuote.text = "\"${eval.claimText}\""
                claimBinding.tvClaimEvidence.text = eval.evidence
                binding.layoutClaimEvaluations.addView(claimBinding.root)
            }

            val claimedPros = report.section1TalkedAbout.claimedAdvantages
            if (claimedPros.isEmpty()) {
                binding.tvClaimedPros.visibility = View.GONE
            } else {
                binding.tvClaimedPros.visibility = View.VISIBLE
                binding.tvClaimedPros.text = "Claimed Advantages:\n" + claimedPros.joinToString("\n") { "+ $it" }
            }

            val claimedCons = report.section1TalkedAbout.claimedDisadvantages
            if (claimedCons.isEmpty()) {
                binding.tvClaimedCons.visibility = View.GONE
            } else {
                binding.tvClaimedCons.visibility = View.VISIBLE
                binding.tvClaimedCons.text = "Claimed Issues / Contradictions:\n" + claimedCons.joinToString("\n") { "- $it" }
            }

            // Section 2: What the User Didn't Talk About
            val hiddenPros = report.section2DidntTalkAbout.hiddenAdvantages
            binding.tvHiddenAdvantages.visibility = View.VISIBLE
            binding.tvHiddenAdvantages.text = if (hiddenPros.isEmpty()) {
                "Hidden Advantages:\n+ None detected beyond spoken pitch."
            } else {
                "Hidden Advantages:\n" + hiddenPros.joinToString("\n") { "+ $it" }
            }

            val hiddenCons = report.section2DidntTalkAbout.hiddenDisadvantages
            binding.tvHiddenDisadvantages.visibility = View.VISIBLE
            binding.tvHiddenDisadvantages.text = if (hiddenCons.isEmpty()) {
                "Hidden Disadvantages / Red Flags:\n- No hidden red flags detected."
            } else {
                "Hidden Disadvantages / Red Flags:\n" + hiddenCons.joinToString("\n") { "- $it" }
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatMessageEntity>() {
        override fun areItemsTheSame(oldItem: ChatMessageEntity, newItem: ChatMessageEntity): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatMessageEntity, newItem: ChatMessageEntity): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_REPORT = 2
    }
}
