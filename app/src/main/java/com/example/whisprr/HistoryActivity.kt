package com.example.whisprr

import android.content.Intent
import android.content.SharedPreferences
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.whisprr.databinding.ActivityHistoryBinding
import com.example.whisprr.databinding.DialogPermanentBanBinding
import com.example.whisprr.databinding.DialogRejectionBinding
import com.example.whisprr.databinding.ItemHistoryBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

data class HistoryItem(
    val chatroomId: String,
    val inviteCode: String,
    val visitedAt: Long,
    var roomName: String = "Room",
    var createdAt: Long = 0L,
    var isActive: Boolean = false,
    var isBanned: Boolean = false,
    var wasKicked: Boolean = false,
    var allowHistory: Boolean = true,
    var dataDeleted: Boolean = false
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
        binding.historyToolbar.navigationIcon?.setTint(
            if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
                Color.WHITE else Color.BLACK
        )
        binding.historyToolbar.setNavigationOnClickListener { finish() }

        historyAdapter = HistoryAdapter(historyItems,
            onRejoin = { item -> rejoinRoom(item) },
            onDelete = { item -> showDeleteDialog(item) }
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
                visitedAt = obj.getLong("visitedAt"),
                createdAt = obj.optLong("createdAt", 0L)
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
                            item.roomName = snapshot.child("roomName").getValue(String::class.java) ?: "Room"
                            item.createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0L
                            item.isBanned = snapshot.child("permanentBans/$myId")
                                .getValue(Boolean::class.java) ?: false
                            item.wasKicked = snapshot.child("kickedParticipants/$myId").exists()
                            item.allowHistory = snapshot.child("settings/allowHistory").getValue(Boolean::class.java) ?: true
                            item.dataDeleted = snapshot.child("dataDeleted").getValue(Boolean::class.java) ?: false
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
                .setTitle("Permanently Banned")
                .setMessage("You have been permanently banned from this room.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        if (item.dataDeleted) {
            AlertDialog.Builder(this)
                .setTitle("Chat Data Deleted")
                .setMessage("The host deleted the chat data from the server for ${item.roomName}.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        if (item.wasKicked) {
            AlertDialog.Builder(this)
                .setTitle("Access Denied")
                .setMessage("You were kicked out from this room. Would you like to request to rejoin?")
                .setPositiveButton("Request to Rejoin") { _, _ ->
                    submitJoinRequest(item.chatroomId, item.inviteCode, item.roomName, wasKicked = true)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (!item.isActive) {
            if (item.allowHistory) {
                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra("chatroomId", item.chatroomId)
                    putExtra("inviteCode", item.inviteCode)
                    putExtra("isViewMode", true)
                    putExtra("roomName", item.roomName)
                }
                startActivity(intent)
            } else {
                AlertDialog.Builder(this)
                    .setTitle("History Not Available")
                    .setMessage("Chat history is not enabled for this room.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        } else {
            val intent = Intent(this, ChatActivity::class.java).apply {
                putExtra("chatroomId", item.chatroomId)
                putExtra("inviteCode", item.inviteCode)
                putExtra("isTemporary", true)
            }
            startActivity(intent)
        }
    }



    private fun saveHistory() {
        val historyArray = org.json.JSONArray()
        historyItems.forEach { item ->
            val obj = org.json.JSONObject().apply {
                put("chatroomId", item.chatroomId)
                put("inviteCode", item.inviteCode)
                put("visitedAt", item.visitedAt)
                put("createdAt", item.createdAt)
            }
            historyArray.put(obj)
        }
        prefs.edit().putString("room_history", historyArray.toString()).apply()
    }

    private fun submitJoinRequest(chatroomId: String, inviteCode: String, roomName: String, wasKicked: Boolean = false) {
        val myId = auth.currentUser?.uid ?: return
        database.reference.child("users").child(myId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(mySnapshot: DataSnapshot) {
                    val myName = mySnapshot.child("name").getValue(String::class.java) ?: "User"
                    val requestData = mapOf(
                        "name" to myName,
                        "timestamp" to System.currentTimeMillis(),
                        "wasKicked" to wasKicked
                    )
                    database.reference.child("chatrooms").child(chatroomId).child("meta")
                        .child("joinRequests").child(myId).setValue(requestData)
                        .addOnSuccessListener {
                            if (!wasKicked) {
                                Toast.makeText(this@HistoryActivity, "Join request sent. Waiting for approval...", Toast.LENGTH_LONG).show()
                            }
                            waitForJoinApproval(chatroomId, inviteCode, roomName)
                        }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun waitForJoinApproval(chatroomId: String, inviteCode: String, roomName: String) {
        val myId = auth.currentUser?.uid ?: return
        val joinRequestRef = database.reference.child("chatrooms").child(chatroomId)
            .child("meta").child("joinRequests").child(myId)

        val approvalRef = database.reference.child("chatrooms").child(chatroomId)
            .child("meta").child("participants").child(myId)

        var requestListener: ValueEventListener? = null
        var approvalListener: ValueEventListener? = null

        fun finishListeners() {
            requestListener?.let { joinRequestRef.removeEventListener(it) }
            approvalListener?.let { approvalRef.removeEventListener(it) }
        }

        approvalListener = object : ValueEventListener {
            override fun onDataChange(appSnap: DataSnapshot) {
                if (appSnap.exists() && appSnap.getValue(Boolean::class.java) == true) {
                    finishListeners()
                    joinRoomAndEnterChat(chatroomId, inviteCode) {}
                }
            }
            override fun onCancelled(error: DatabaseError) {
                approvalListener?.let { approvalRef.removeEventListener(it) }
            }
        }

        val checkApprovalState = object {
            fun run(finalCheck: Boolean = false) {
                approvalRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(appSnap: DataSnapshot) {
                        if (appSnap.exists() && appSnap.getValue(Boolean::class.java) == true) {
                            finishListeners()
                            joinRoomAndEnterChat(chatroomId, inviteCode) {}
                        } else if (finalCheck) {
                            database.reference.child("chatrooms").child(chatroomId).child("meta/permanentBans").child(myId)
                                .addListenerForSingleValueEvent(object : ValueEventListener {
                                    override fun onDataChange(banSnap: DataSnapshot) {
                                        finishListeners()
                                        if (banSnap.getValue(Boolean::class.java) == true) {
                                            showPermanentlyBannedDialog(roomName)
                                        } else {
                                            showRejectedDialog()
                                        }
                                    }
                                    override fun onCancelled(error: DatabaseError) {
                                        finishListeners()
                                        showRejectedDialog()
                                    }
                                })
                        } else {
                            Handler(Looper.getMainLooper()).postDelayed({ run(true) }, 400)
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {
                        finishListeners()
                        showRejectedDialog()
                    }
                })
            }
        }

        requestListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    checkApprovalState.run()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                requestListener?.let { joinRequestRef.removeEventListener(it) }
            }
        }

        joinRequestRef.addValueEventListener(requestListener)
        approvalRef.addValueEventListener(approvalListener)
    }

    private fun joinRoomAndEnterChat(chatroomId: String, inviteCode: String, onJoined: () -> Unit = {}) {
        val myId = auth.currentUser?.uid ?: return
        database.reference.child("users").child(myId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(mySnapshot: DataSnapshot) {
                    val myName = mySnapshot.child("name").getValue(String::class.java) ?: "User"
                    val myPublicKey = mySnapshot.child("publicKey").getValue(String::class.java) ?: return

                    val participantData = mapOf(
                        "name" to myName,
                        "publicKey" to myPublicKey,
                        "joinedAt" to System.currentTimeMillis()
                    )

                    database.reference.child("chatrooms").child(chatroomId).child("meta/participants").child(myId)
                        .setValue(participantData)
                        .addOnSuccessListener {
                            database.reference.child("chatrooms").child(chatroomId).child("meta/participantNames").child(myId)
                                .setValue(myName)
                            database.reference.child("chatrooms").child(chatroomId).child("meta/participantPublicKeys").child(myId)
                                .setValue(myPublicKey)
                            database.reference.child("chatrooms").child(chatroomId).child("meta/joinRequests").child(myId)
                                .removeValue()

                            val intent = Intent(this@HistoryActivity, ChatActivity::class.java).apply {
                                putExtra("chatroomId", chatroomId)
                                putExtra("inviteCode", inviteCode)
                                putExtra("isTemporary", true)
                            }
                            startActivity(intent)
                            onJoined()
                        }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun showRejectedDialog() {
        val dialogBinding = com.example.whisprr.databinding.DialogRejectionBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialogBinding.btnOkRejection.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showPermanentlyBannedDialog(roomName: String) {
        val dialogBinding = com.example.whisprr.databinding.DialogPermanentBanBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialogBinding.tvBanMessage.text = "You have been permanently banned from $roomName chat room by the host."
        dialogBinding.btnOkBan.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showCodeCopyDialog(code: String) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_copy_code, null)
        val etRoomCode = dialogView.findViewById<EditText>(R.id.etRoomCode)
        val btnCopy = dialogView.findViewById<ImageButton>(R.id.btnCopy)

        etRoomCode.setText(code)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCopy.setOnClickListener {
            copyToClipboard("Whisprr Room Code", code)
            Toast.makeText(this, "Room code copied", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
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
            holder.binding.tvRoomName.text = item.roomName
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
                item.dataDeleted -> {
                    holder.binding.tvRoomStatus.text = "DELETED"
                    holder.binding.tvRoomStatus.setTextColor(getColor(R.color.whisprr_error))
                    holder.binding.btnRejoin.text = "Deleted"
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
                    holder.binding.btnRejoin.text = "Rejoin"
                    holder.binding.btnRejoin.isEnabled = true
                    holder.binding.btnRejoin.alpha = 1.0f
                }
                else -> {
                    holder.binding.tvRoomStatus.text = "Ended"
                    holder.binding.tvRoomStatus.setTextColor(getColor(R.color.whisprr_text_secondary))
                    if (item.allowHistory) {
                        holder.binding.btnRejoin.text = "View"
                        holder.binding.btnRejoin.isEnabled = true
                        holder.binding.btnRejoin.alpha = 1.0f
                    } else {
                        holder.binding.btnRejoin.text = "Ended"
                        holder.binding.btnRejoin.isEnabled = false
                        holder.binding.btnRejoin.alpha = 0.5f
                    }
                }
            }

            holder.binding.btnRejoin.setOnClickListener { onRejoin(item) }
            holder.binding.tvRoomCode.setOnLongClickListener {
                showCodeCopyDialog(item.inviteCode)
                true
            }
            holder.itemView.setOnLongClickListener {
                if (!item.isActive) {
                    onDelete(item)
                }
                true
            }
        }

        override fun getItemCount() = items.size
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    private fun showDeleteDialog(item: HistoryItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Chat")
            .setMessage("Are you sure you want to delete this chat from history?")
            .setPositiveButton("Delete") { _, _ ->
                deleteFromHistory(item)
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

    private fun deleteFromHistory(item: HistoryItem) {
        // No local file to delete, just remove from history
    }
}

