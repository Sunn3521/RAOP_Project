package com.example.whisprr

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.whisprr.databinding.ItemParticipantBinding

class ParticipantAdapter(
    private val participants: List<Participant>,
    private val currentUserId: String,
    private val isHost: Boolean,
    private val onKick: ((Participant) -> Unit)? = null,
    private val onMute: ((Participant) -> Unit)? = null
) : RecyclerView.Adapter<ParticipantAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemParticipantBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemParticipantBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val p = participants[position]
        holder.binding.tvParticipantName.text = p.name
        holder.binding.tvHostBadge.isVisible = p.isHost

        // Hide controls for bot
        val isBot = p.uid == CryptoManager.BOT_UID

        // Host controls: only host can see kick/mute for non-hosts, and not for self or bot
        val showControls = isHost && !p.isHost && !isBot && p.uid != currentUserId

        // Mute badge — always visible for eligible participants as a toggle button
        // Fully opaque when muted (to click and unmute), semi-transparent when not muted (to click and mute)
        holder.binding.tvMuteBadge.isVisible = showControls
        if (showControls) {
            holder.binding.tvMuteBadge.alpha = if (p.isMuted) 1.0f else 0.35f
            holder.binding.tvMuteBadge.setOnClickListener { onMute?.invoke(p) }
        }

        // Kick badge — always visible for eligible participants
        holder.binding.tvKickBadge.isVisible = showControls
        if (showControls) {
            holder.binding.tvKickBadge.setOnClickListener { onKick?.invoke(p) }
        }

        // Hide old buttons
        holder.binding.btnKick.isVisible = false
        holder.binding.btnMute.isVisible = false
    }

    override fun getItemCount() = participants.size
}

