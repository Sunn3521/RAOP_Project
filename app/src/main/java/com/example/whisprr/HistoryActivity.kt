package com.example.whisprr

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.whisprr.databinding.ActivityHistoryBinding
import com.example.whisprr.databinding.ItemHistoryBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.*

data class HistoryItem(
    val chatroomId: String,
    val inviteCode: String,
    val visitedAt: Long,
    var isActive: Boolean = false,
    var isBanned: Boolean = false,
    var wasKicked: Boolean = false
)

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private val historyItems = mutableListOf<HistoryItem>()
    private lateinit var historyAdapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance(WhisprrApplication.DATABASE_URL)
        prefs = getSharedPreferences("whisprr_prefs", MODE_PRIVATE)

        setSupportActionBar(binding.historyToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.historyToolbar.setNavigationOnClickListener { finish() }

        historyAdapter = HistoryAdapter(historyItems,
            onRejoin = { item -> rejoinRoom(item) },
            onDelete = { item -> deleteHistoryItem(item) }
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter

        loadHistory()
    }

    private fun loadHistory() {
        val historyJson = prefs.getString("room_history", "[]") ?: "[]"
        val historyArray = try {
            org.json.JSONArray(historyJson)
        } catch (e: Exception) {
            org.json.JSONArray()
        }

        historyItems.clear()
        for (i in 0 until historyArray.length()) {
            val obj = historyArray.getJSONObject(i)
            historyItems.add(HistoryItem(
                chatroomId = obj.getString("chatroomId"),
                inviteCode = obj.getString("inviteCode"),
                visitedAt = obj.getLong("visitedAt")
            ))
        }

        // Sort by most recent first
        historyItems.sortByDescending { it.visitedAt }

        if (historyItems.isEmpty()) {
            binding.tvEmptyHistory.isVisible = true
            binding.rvHistory.isVisible = false
        } else {
            binding.tvEmptyHistory.isVisible = false
            binding.rvHistory.isVisible = true
            checkRoomStatuses()
        }
    }

    private fun checkRoomStatuses() {
        val myId = auth.currentUser?.uid ?: return
        var pendingChecks = historyItems.size

        if (pendingChecks == 0) {
            historyAdapter.notifyDataSetChanged()
            return
        }

        historyItems.forEach { item ->
            database.reference.child("chatrooms").child(item.chatroomId).child("meta")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.exists()) {
                            val ended = snapshot.child("ended").getValue(Boolean::class.java) ?: false
                            item.isActive = !ended
                            item.isBanned = snapshot.child("bannedParticipants/$myId")
                                .getValue(Boolean::class.java) ?: false
                            item.wasKicked = snapshot.child("kickedParticipants/$myId").exists()
                        } else {
                            item.isActive = false
                        }
                        pendingChecks--
                        if (pendingChecks <= 0) {
                            historyAdapter.notifyDataSetChanged()
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {
                        pendingChecks--
                        if (pendingChecks <= 0) {
                            historyAdapter.notifyDataSetChanged()
                        }
                    }
                })
        }
    }

    private fun rejoinRoom(item: HistoryItem) {
        if (item.isBanned) {
            AlertDialog.Builder(this)
                .setTitle("Access Denied")
                .setMessage("You have been permanently banned from this room.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        if (!item.isActive) {
            AlertDialog.Builder(this)
                .setTitle("Session Ended")
                .setMessage("This chatroom session has already ended.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("chatroomId", item.chatroomId)
            putExtra("inviteCode", item.inviteCode)
            putExtra("isTemporary", true)
        }
        startActivity(intent)
    }

    private fun deleteHistoryItem(item: HistoryItem) {
        AlertDialog.Builder(this)
            .setTitle("Remove from History?")
            .setMessage("This will remove the room from your history. You can still rejoin using the invite code.")
            .setPositiveButton("Remove") { _, _ ->
                historyItems.remove(item)
                saveHistory()
                historyAdapter.notifyDataSetChanged()
                if (historyItems.isEmpty()) {
                    binding.tvEmptyHistory.isVisible = true
                    binding.rvHistory.isVisible = false
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveHistory() {
        val historyArray = org.json.JSONArray()
        historyItems.forEach { item ->
            val obj = org.json.JSONObject().apply {
                put("chatroomId", item.chatroomId)
                put("inviteCode", item.inviteCode)
                put("visitedAt", item.visitedAt)
            }
            historyArray.put(obj)
        }
        prefs.edit().putString("room_history", historyArray.toString()).apply()
    }

    // ======================= ADAPTER =======================

    inner class HistoryAdapter(
        private val items: List<HistoryItem>,
        private val onRejoin: (HistoryItem) -> Unit,
        private val onDelete: (HistoryItem) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

        inner class HistoryViewHolder(val binding: ItemHistoryBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
            val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return HistoryViewHolder(binding)
        }

        override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
            val item = items[position]
            holder.binding.tvRoomCode.text = item.inviteCode

            val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault())
            holder.binding.tvVisitedAt.text = "Visited: ${sdf.format(Date(item.visitedAt))}"

            when {
                item.isBanned -> {
                    holder.binding.tvRoomStatus.text = "BANNED"
                    holder.binding.tvRoomStatus.setTextColor(getColor(R.color.whisprr_error))
                    holder.binding.btnRejoin.isEnabled = false
                    holder.binding.btnRejoin.alpha = 0.5f
                }
                item.wasKicked -> {
                    holder.binding.tvRoomStatus.text = "KICKED • Request needed"
                    holder.binding.tvRoomStatus.setTextColor(getColor(R.color.whisprr_warning))
                    holder.binding.btnRejoin.isEnabled = true
                    holder.binding.btnRejoin.alpha = 1.0f
                }
                item.isActive -> {
                    holder.binding.tvRoomStatus.text = "Active"
                    holder.binding.tvRoomStatus.setTextColor(getColor(R.color.whisprr_success))
                    holder.binding.btnRejoin.isEnabled = true
                    holder.binding.btnRejoin.alpha = 1.0f
                }
                else -> {
                    holder.binding.tvRoomStatus.text = "Ended"
                    holder.binding.tvRoomStatus.setTextColor(getColor(R.color.whisprr_outline))
                    holder.binding.btnRejoin.isEnabled = false
                    holder.binding.btnRejoin.alpha = 0.5f
                }
            }

            holder.binding.btnRejoin.setOnClickListener { onRejoin(item) }
            holder.itemView.setOnLongClickListener {
                onDelete(item)
                true
            }
        }

        override fun getItemCount() = items.size
    }
}

