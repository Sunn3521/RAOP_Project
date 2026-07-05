package com.example.whisprr

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.whisprr.databinding.ActivityChatBinding
import com.example.whisprr.databinding.DialogHostSettingsBinding
import com.example.whisprr.databinding.DialogJoinRequestBinding
import com.example.whisprr.databinding.DialogParticipantsBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import java.io.ByteArrayOutputStream

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private var chatroomId: String    = ""
    private var inviteCode: String    = ""
    private var isTemporary: Boolean  = false
    private var hostId: String        = ""
    private var isHost: Boolean       = false
    private val messages = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private var messagesListener: ValueEventListener? = null
    private var participantsListener: ValueEventListener? = null
    private var participantNamesListener: ValueEventListener? = null
    private var joinRequestsListener: ValueEventListener? = null
    private var kickListener: ValueEventListener? = null
    private var mutedParticipantsListener: ValueEventListener? = null
    private var hostIdListener: ValueEventListener? = null
    private var participantAdapter: ParticipantAdapter? = null

    private val participantPublicKeys = mutableMapOf<String, String>()
    private val participantNames = mutableMapOf<String, String>()
    private val participantsList = mutableListOf<Participant>()
    private val mutedParticipants = mutableSetOf<String>()
    private val kickedParticipants = mutableMapOf<String, Long>()
    private val bannedParticipants = mutableSetOf<String>()

    // File picker
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFileSelection(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth     = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance(WhisprrApplication.DATABASE_URL)

        chatroomId = intent.getStringExtra("chatroomId") ?: run {
            Toast.makeText(this, "Invalid room", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        inviteCode = intent.getStringExtra("inviteCode") ?: "Unknown"
        isTemporary = intent.getBooleanExtra("isTemporary", true)

        setSupportActionBar(binding.chatToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.chatToolbar.setNavigationOnClickListener { finish() }

        // Load room name
        database.reference.child("chatrooms").child(chatroomId).child("meta/roomName")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val roomName = snapshot.getValue(String::class.java) ?: "Room $inviteCode"
                    supportActionBar?.title = "\uD83D\uDD12 $roomName"
                }
                override fun onCancelled(error: DatabaseError) {}
            })

        messageAdapter = MessageAdapter(messages, auth.currentUser?.uid ?: "")
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = messageAdapter

        checkHostStatus()
        listenForParticipants()
        listenForMessages()

        binding.btnSend.setOnClickListener { sendEncryptedMessage() }
        binding.btnAttach.setOnClickListener { launchFilePicker() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.chat_menu, menu)
        val hostSettingsItem = menu.findItem(R.id.action_host_settings)
        hostSettingsItem?.isVisible = isHost
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_participants -> {
                showParticipantsDialog()
                true
            }
            R.id.action_host_settings -> {
                if (isHost) showHostSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ======================= FILE SHARING (BASE64 via RTDB) =======================

    private fun launchFilePicker() {
        filePickerLauncher.launch("*/*")
    }

    private fun handleFileSelection(uri: Uri) {
        val myId = auth.currentUser?.uid ?: return

        // Check muted
        database.reference.child("chatrooms").child(chatroomId)
            .child("meta").child("mutedParticipants").child(myId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists() && snapshot.getValue(Boolean::class.java) == true) {
                        Toast.makeText(this@ChatActivity, "You are muted by the host", Toast.LENGTH_SHORT).show()
                        return
                    }
                    validateAndSendFile(uri)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun validateAndSendFile(uri: Uri) {
        // Determine file category from MIME type
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val category = getFileCategory(mimeType)
        val fileName = getFileName(uri) ?: "unknown"

        // Check file size first
        val fileSize = getFileSize(uri)
        val maxSize = 10 * 1024 * 1024 // 10MB
        if (fileSize > maxSize) {
            AlertDialog.Builder(this@ChatActivity)
                .setTitle("File Too Large")
                .setMessage("This file is ${formatFileSize(fileSize)} which exceeds the 10MB limit for free sharing.\n\nPlease choose a smaller file.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        // Fetch host settings
        database.reference.child("chatrooms").child(chatroomId).child("meta/settings/allowedFileTypes")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val everything = snapshot.child("everything").getValue(Boolean::class.java) ?: false
                    if (everything) {
                        readAndSendFile(uri, fileName, mimeType, category)
                        return
                    }

                    val allowed = snapshot.child(category).getValue(Boolean::class.java) ?: false
                    if (allowed) {
                        readAndSendFile(uri, fileName, mimeType, category)
                    } else {
                        // Build allowed types list for error message
                        val allowedTypes = mutableListOf<String>()
                        listOf("pdf", "images", "videos", "audio", "archives").forEach {
                            if (snapshot.child(it).getValue(Boolean::class.java) == true) {
                                allowedTypes.add(it.replaceFirstChar { c -> c.uppercase() })
                            }
                        }
                        val title = "File Type Not Allowed"
                        val msg = if (allowedTypes.isEmpty()) {
                            "The host has disabled all file uploads."
                        } else {
                            "Files of this type are not allowed.\n\nAllowed file types:\n\u2022 ${allowedTypes.joinToString("\n\u2022 ")}"
                        }
                        AlertDialog.Builder(this@ChatActivity)
                            .setTitle(title)
                            .setMessage(msg)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun getFileCategory(mimeType: String): String {
        return when {
            mimeType.startsWith("image/") -> "images"
            mimeType.startsWith("video/") -> "videos"
            mimeType.startsWith("audio/") -> "audio"
            mimeType == "application/pdf" -> "pdf"
            mimeType == "application/zip" ||
            mimeType == "application/x-rar-compressed" ||
            mimeType == "application/x-7z-compressed" ||
            mimeType == "application/gzip" -> "archives"
            else -> "other"
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun getFileSize(uri: Uri): Long {
        var size: Long = 0
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0) size = cursor.getLong(idx)
            }
        }
        if (size == 0L) {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                size = afd.length
            }
        }
        return size
    }

    private fun formatFileSize(size: Long): String {
        val kb = size / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format("%.2f MB", mb)
            kb >= 1 -> String.format("%.2f KB", kb)
            else -> "$size B"
        }
    }

    private fun readAndSendFile(uri: Uri, fileName: String, mimeType: String, category: String) {
        val myId = auth.currentUser?.uid ?: return
        val myName = participantNames[myId] ?: "Me"

        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArrayOutputStream()
                val tmp = ByteArray(4096)
                var read: Int
                while (inputStream.read(tmp).also { read = it } != -1) {
                    buffer.write(tmp, 0, read)
                }
                val fileBytes = buffer.toByteArray()
                val base64Data = Base64.encodeToString(fileBytes, Base64.DEFAULT)
                sendFileMessage(myId, myName, fileName, mimeType, base64Data, category)
            } ?: run {
                Toast.makeText(this, "Failed to read file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ChatActivity", "File read error: ${e.message}", e)
            Toast.makeText(this, "File read failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendFileMessage(
        myId: String,
        myName: String,
        fileName: String,
        fileType: String,
        fileData: String,
        category: String
    ) {
        val messageData = mapOf(
            "senderId"      to myId,
            "senderName"    to myName,
            "encryptedText" to "",
            "iv"            to "",
            "encryptedKeys" to mapOf<String, String>(),
            "timestamp"     to System.currentTimeMillis(),
            "isFile"        to true,
            "fileName"      to fileName,
            "fileType"      to fileType,
            "fileData"      to fileData,
            "fileCategory"  to category
        )

        database.reference.child("chatrooms").child(chatroomId).child("messages")
            .push().setValue(messageData)
            .addOnSuccessListener {
                Toast.makeText(this, "File sent", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Send failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ======================= HOST DETECTION & TRANSFER =======================

    private fun checkHostStatus() {
        val myId = auth.currentUser?.uid ?: return
        val hostRef = database.reference.child("chatrooms").child(chatroomId).child("meta/hostId")

        // Initial check
        hostRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                hostId = snapshot.getValue(String::class.java) ?: ""
                isHost = (hostId == myId)
                invalidateOptionsMenu()

                if (isHost) {
                    listenForJoinRequests()
                } else {
                    listenForKick()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })

        // Real-time listener for host changes
        hostIdListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newHostId = snapshot.getValue(String::class.java) ?: ""
                if (newHostId != hostId) {
                    hostId = newHostId
                    isHost = (hostId == myId)
                    invalidateOptionsMenu()
                    rebuildParticipantsList()

                    // Switch between kick listener and join requests listener
                    if (isHost) {
                        kickListener?.let {
                            database.reference.child("chatrooms").child(chatroomId)
                                .child("meta").child("participants").child(myId)
                                .removeEventListener(it)
                        }
                        listenForJoinRequests()
                    } else {
                        joinRequestsListener?.let {
                            database.reference.child("chatrooms").child(chatroomId)
                                .child("meta").child("joinRequests").removeEventListener(it)
                        }
                        listenForKick()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        hostRef.addValueEventListener(hostIdListener!!)
    }

    // ======================= JOIN REQUESTS (HOST ONLY) =======================

    private fun listenForJoinRequests() {
        if (!isHost) return

        // Load kicked and banned participants first
        database.reference.child("chatrooms").child(chatroomId).child("meta/kickedParticipants")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(kickedSnap: DataSnapshot) {
                    kickedParticipants.clear()
                    for (child in kickedSnap.children) {
                        val uid = child.key ?: continue
                        val ts = child.getValue(Long::class.java) ?: 0L
                        kickedParticipants[uid] = ts
                    }

                    database.reference.child("chatrooms").child(chatroomId).child("meta/bannedParticipants")
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(bannedSnap: DataSnapshot) {
                                bannedParticipants.clear()
                                for (child in bannedSnap.children) {
                                    val uid = child.key ?: continue
                                    if (child.getValue(Boolean::class.java) == true) {
                                        bannedParticipants.add(uid)
                                    }
                                }
                                attachJoinRequestsListener()
                            }
                            override fun onCancelled(error: DatabaseError) {}
                        })
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun attachJoinRequestsListener() {
        val requestsRef = database.reference.child("chatrooms").child(chatroomId)
            .child("meta").child("joinRequests")

        joinRequestsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val uid = child.key ?: continue
                    // Skip permanently banned users
                    if (bannedParticipants.contains(uid)) {
                        // Auto-decline banned users silently
                        database.reference.child("chatrooms").child(chatroomId)
                            .child("meta").child("joinRequests").child(uid).removeValue()
                        continue
                    }
                    val name = child.child("name").getValue(String::class.java) ?: "Unknown"
                    val wasKicked = kickedParticipants.containsKey(uid)
                    showJoinRequestDialog(uid, name, wasKicked)
                    break // Show one at a time
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        requestsRef.addValueEventListener(joinRequestsListener!!)
    }

    private fun showJoinRequestDialog(uid: String, name: String, wasKicked: Boolean = false) {
        val dialogBinding = DialogJoinRequestBinding.inflate(LayoutInflater.from(this))
        dialogBinding.tvRequesterName.text = "$name wants to join"

        if (wasKicked) {
            dialogBinding.tvKickedWarning.isVisible = true
            dialogBinding.cbPermanentlyBan.isVisible = true
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialogBinding.btnAccept.setOnClickListener {
            // Approve: add to participants, remove from kicked list
            val updates = mutableMapOf<String, Any?>(
                "chatrooms/$chatroomId/meta/participants/$uid" to true,
                "chatrooms/$chatroomId/meta/joinRequests/$uid" to null
            )
            if (wasKicked) {
                updates["chatrooms/$chatroomId/meta/kickedParticipants/$uid"] = null
            }
            database.reference.updateChildren(updates)
            dialog.dismiss()
        }

        dialogBinding.btnDecline.setOnClickListener {
            if (wasKicked && dialogBinding.cbPermanentlyBan.isChecked) {
                // Permanently ban
                database.reference.child("chatrooms").child(chatroomId)
                    .child("meta").child("bannedParticipants").child(uid).setValue(true)
                    .addOnSuccessListener {
                        // Also remove from kicked list and requests
                        val updates = mapOf<String, Any?>(
                            "chatrooms/$chatroomId/meta/kickedParticipants/$uid" to null,
                            "chatrooms/$chatroomId/meta/joinRequests/$uid" to null
                        )
                        database.reference.updateChildren(updates)
                    }
            } else {
                // Just decline
                database.reference.child("chatrooms").child(chatroomId)
                    .child("meta").child("joinRequests").child(uid).removeValue()
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    // ======================= PARTICIPANTS DIALOG =======================

    private fun showParticipantsDialog() {
        val myId = auth.currentUser?.uid ?: return

        // Check if participant list is visible to all or only host
        database.reference.child("chatrooms").child(chatroomId).child("meta/settings/showParticipantsToAll")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val showToAll = snapshot.getValue(Boolean::class.java) ?: true
                    if (!showToAll && !isHost) {
                        Toast.makeText(this@ChatActivity, "Participant list is hidden by the host", Toast.LENGTH_SHORT).show()
                        return
                    }
                    openParticipantsDialog(myId)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun openParticipantsDialog(myId: String) {
        val dialogBinding = DialogParticipantsBinding.inflate(LayoutInflater.from(this))

        // Show host bulk actions only for host
        dialogBinding.layoutHostActions.isVisible = isHost

        dialogBinding.btnMuteAll.setOnClickListener {
            if (!isHost) return@setOnClickListener
            val targets = participantsList.filter { it.uid != myId && it.uid != CryptoManager.BOT_UID && !it.isHost }
            if (targets.isEmpty()) {
                Toast.makeText(this, "No participants to mute", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Mute All Participants?")
                .setMessage("${targets.size} participant(s) will be muted.")
                .setPositiveButton("Mute All") { _, _ ->
                    val updates = mutableMapOf<String, Any?>()
                    targets.forEach { p ->
                        updates["chatrooms/$chatroomId/meta/mutedParticipants/${p.uid}"] = true
                    }
                    database.reference.updateChildren(updates)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialogBinding.btnKickAll.setOnClickListener {
            if (!isHost) return@setOnClickListener
            val targets = participantsList.filter { it.uid != myId && it.uid != CryptoManager.BOT_UID && !it.isHost }
            if (targets.isEmpty()) {
                Toast.makeText(this, "No participants to kick", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle("Kick All Participants?")
                .setMessage("${targets.size} participant(s) will be removed from the room.")
                .setPositiveButton("Kick All") { _, _ ->
                    val updates = mutableMapOf<String, Any?>()
                    targets.forEach { p ->
                        updates["chatrooms/$chatroomId/meta/participants/${p.uid}"] = null
                        updates["chatrooms/$chatroomId/meta/participantPublicKeys/${p.uid}"] = null
                        updates["chatrooms/$chatroomId/meta/participantNames/${p.uid}"] = null
                        updates["chatrooms/$chatroomId/meta/mutedParticipants/${p.uid}"] = null
                    }
                    database.reference.updateChildren(updates)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        participantAdapter = ParticipantAdapter(
            participants = participantsList,
            currentUserId = myId,
            isHost = isHost,
            onKick = { participant ->
                if (isHost) {
                    AlertDialog.Builder(this)
                        .setTitle("Kick ${participant.name}?")
                        .setMessage("They will be removed from the chatroom.")
                        .setPositiveButton("Kick") { _, _ ->
                            kickParticipant(participant.uid)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            },
            onMute = { participant ->
                if (isHost) {
                    val mutedRef = database.reference.child("chatrooms").child(chatroomId)
                        .child("meta").child("mutedParticipants").child(participant.uid)
                    if (participant.isMuted) {
                        mutedRef.removeValue()
                    } else {
                        mutedRef.setValue(true)
                    }
                    // Refresh adapter immediately for responsive UI
                    participantAdapter?.notifyDataSetChanged()
                }
            }
        )

        dialogBinding.rvParticipants.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvParticipants.adapter = participantAdapter

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCloseParticipants.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun kickParticipant(uid: String) {
        val kickedAt = System.currentTimeMillis()
        val updates = mapOf<String, Any?>(
            "chatrooms/$chatroomId/meta/participants/$uid" to null,
            "chatrooms/$chatroomId/meta/participantPublicKeys/$uid" to null,
            "chatrooms/$chatroomId/meta/participantNames/$uid" to null,
            "chatrooms/$chatroomId/meta/mutedParticipants/$uid" to null,
            "chatrooms/$chatroomId/meta/kickedParticipants/$uid" to kickedAt
        )
        database.reference.updateChildren(updates)
            .addOnSuccessListener {
                Toast.makeText(this, "Participant removed", Toast.LENGTH_SHORT).show()
            }
    }

    // ======================= KICK LISTENER (NON-HOST) =======================

    private fun listenForKick() {
        val myId = auth.currentUser?.uid ?: return
        val myParticipantRef = database.reference.child("chatrooms").child(chatroomId)
            .child("meta").child("participants").child(myId)

        kickListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists() || snapshot.getValue(Boolean::class.java) != true) {
                    Toast.makeText(this@ChatActivity, "You have been removed from the room", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        myParticipantRef.addValueEventListener(kickListener!!)
    }

    // ======================= HOST SETTINGS =======================

    private fun showHostSettingsDialog() {
        val dialogBinding = DialogHostSettingsBinding.inflate(LayoutInflater.from(this))

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        // Load room name
        database.reference.child("chatrooms").child(chatroomId).child("meta/roomName")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(nameSnap: DataSnapshot) {
                    dialogBinding.etRoomName.setText(nameSnap.getValue(String::class.java) ?: "")
                }
                override fun onCancelled(error: DatabaseError) {}
            })

        // Load current settings
        database.reference.child("chatrooms").child(chatroomId).child("meta/settings")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val locked = snapshot.child("locked").getValue(Boolean::class.java) ?: false
                    val entryMode = snapshot.child("entryMode").getValue(String::class.java) ?: "open"
                    val showParticipants = snapshot.child("showParticipantsToAll").getValue(Boolean::class.java) ?: true

                    dialogBinding.switchLockRoom.isChecked = locked
                    dialogBinding.switchShowParticipants.isChecked = showParticipants

                    when (entryMode) {
                        "open" -> dialogBinding.radioOpen.isChecked = true
                        "request" -> dialogBinding.radioRequest.isChecked = true
                    }

                    // File types
                    val ft = snapshot.child("allowedFileTypes")
                    dialogBinding.cbPdf.isChecked = ft.child("pdf").getValue(Boolean::class.java) ?: true
                    dialogBinding.cbImages.isChecked = ft.child("images").getValue(Boolean::class.java) ?: true
                    dialogBinding.cbVideos.isChecked = ft.child("videos").getValue(Boolean::class.java) ?: true
                    dialogBinding.cbAudio.isChecked = ft.child("audio").getValue(Boolean::class.java) ?: true
                    dialogBinding.cbArchives.isChecked = ft.child("archives").getValue(Boolean::class.java) ?: true
                    dialogBinding.cbEverything.isChecked = ft.child("everything").getValue(Boolean::class.java) ?: false

                    // Handle "Everything" checkbox toggle
                    dialogBinding.cbEverything.setOnCheckedChangeListener { _, checked ->
                        val enabled = !checked
                        dialogBinding.cbPdf.isEnabled = enabled
                        dialogBinding.cbImages.isEnabled = enabled
                        dialogBinding.cbVideos.isEnabled = enabled
                        dialogBinding.cbAudio.isEnabled = enabled
                        dialogBinding.cbArchives.isEnabled = enabled
                    }
                    val everythingChecked = dialogBinding.cbEverything.isChecked
                    dialogBinding.cbPdf.isEnabled = !everythingChecked
                    dialogBinding.cbImages.isEnabled = !everythingChecked
                    dialogBinding.cbVideos.isEnabled = !everythingChecked
                    dialogBinding.cbAudio.isEnabled = !everythingChecked
                    dialogBinding.cbArchives.isEnabled = !everythingChecked
                }
                override fun onCancelled(error: DatabaseError) {}
            })

        // Save button
        dialogBinding.btnCloseSettings.setOnClickListener {
            saveHostSettings(dialogBinding)
            dialog.dismiss()
        }

        dialogBinding.btnClearChat.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear All Messages?")
                .setMessage("This will delete all messages permanently.")
                .setPositiveButton("Clear") { _, _ ->
                    database.reference.child("chatrooms").child(chatroomId).child("messages").removeValue()
                    Toast.makeText(this, "Chat cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialogBinding.btnTransferHost.setOnClickListener {
            showTransferHostDialog()
        }

        dialog.show()
    }

    private fun saveHostSettings(dialogBinding: DialogHostSettingsBinding) {
        val roomName = dialogBinding.etRoomName.text.toString().trim()
            .ifEmpty { "Room $inviteCode" }

        val entryMode = if (dialogBinding.radioRequest.isChecked) "request" else "open"
        val locked = dialogBinding.switchLockRoom.isChecked
        val showParticipants = dialogBinding.switchShowParticipants.isChecked
        val everything = dialogBinding.cbEverything.isChecked

        val settings = mapOf(
            "entryMode" to entryMode,
            "locked" to locked,
            "showParticipantsToAll" to showParticipants,
            "allowedFileTypes" to mapOf(
                "pdf" to (dialogBinding.cbPdf.isChecked || everything),
                "images" to (dialogBinding.cbImages.isChecked || everything),
                "videos" to (dialogBinding.cbVideos.isChecked || everything),
                "audio" to (dialogBinding.cbAudio.isChecked || everything),
                "archives" to (dialogBinding.cbArchives.isChecked || everything),
                "everything" to everything
            )
        )

        val metaRef = database.reference.child("chatrooms").child(chatroomId).child("meta")
        metaRef.child("roomName").setValue(roomName)
        metaRef.child("settings").setValue(settings)
            .addOnSuccessListener {
                supportActionBar?.title = "\uD83D\uDD12 $roomName"
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showTransferHostDialog() {
        val otherParticipants = participantsList.filter { !it.isHost }
        if (otherParticipants.isEmpty()) {
            Toast.makeText(this, "No other participants to transfer host to", Toast.LENGTH_SHORT).show()
            return
        }

        val names = otherParticipants.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Transfer Host To")
            .setItems(names) { _, which ->
                val newHostId = otherParticipants[which].uid
                database.reference.child("chatrooms").child(chatroomId)
                    .child("meta").child("hostId").setValue(newHostId)
                    .addOnSuccessListener {
                        isHost = false
                        invalidateOptionsMenu()
                        Toast.makeText(this, "Host role transferred", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ======================= PARTICIPANTS LISTENER =======================

    private fun listenForParticipants() {
        val pubKeysRef = database.reference.child("chatrooms").child(chatroomId)
            .child("meta").child("participantPublicKeys")

        participantsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                participantPublicKeys.clear()
                for (child in snapshot.children) {
                    val uid = child.key ?: continue
                    val key = child.getValue(String::class.java) ?: continue
                    participantPublicKeys[uid] = key
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        pubKeysRef.addValueEventListener(participantsListener!!)

        val namesRef = database.reference.child("chatrooms").child(chatroomId)
            .child("meta").child("participantNames")

        participantNamesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                participantNames.clear()
                for (child in snapshot.children) {
                    val uid = child.key ?: continue
                    val name = child.getValue(String::class.java) ?: continue
                    participantNames[uid] = name
                }
                rebuildParticipantsList()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        namesRef.addValueEventListener(participantNamesListener!!)

        // Listen for muted participants
        val mutedRef = database.reference.child("chatrooms").child(chatroomId)
            .child("meta").child("mutedParticipants")

        mutedParticipantsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                mutedParticipants.clear()
                for (child in snapshot.children) {
                    val uid = child.key ?: continue
                    if (child.getValue(Boolean::class.java) == true) {
                        mutedParticipants.add(uid)
                    }
                }
                rebuildParticipantsList()
                // Refresh participant list adapter in real-time
                participantAdapter?.notifyDataSetChanged()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        mutedRef.addValueEventListener(mutedParticipantsListener!!)
    }

    private fun rebuildParticipantsList() {
        participantsList.clear()

        for ((uid, name) in participantNames) {
            // Bot is invisible in participant list
            if (uid == CryptoManager.BOT_UID) continue

            val isHostUser = (uid == hostId)
            val isMuted = mutedParticipants.contains(uid)
            participantsList.add(Participant(
                uid = uid,
                name = name,
                isHost = isHostUser,
                isMuted = isMuted
            ))
        }
    }

    private fun reencryptMessagesForNewUser(newUid: String, newPubKey: String) {
        if (!isHost) return
        val myId = auth.currentUser?.uid ?: return

        database.reference.child("chatrooms").child(chatroomId).child("messages")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val updates = mutableMapOf<String, Any>()
                    for (msgSnap in snapshot.children) {
                        if (msgSnap.child("encryptedKeys").child(newUid).exists()) continue
                        val hostEncryptedKey = msgSnap.child("encryptedKeys").child(myId)
                            .getValue(String::class.java) ?: continue
                        try {
                            val aesKeyBytes = CryptoManager.rsaDecrypt(hostEncryptedKey)
                            val newEncryptedKey = CryptoManager.rsaEncrypt(aesKeyBytes, newPubKey)
                            updates["chatrooms/$chatroomId/messages/${msgSnap.key}/encryptedKeys/$newUid"] = newEncryptedKey
                        } catch (e: Exception) {
                            Log.e("ChatActivity", "Re-encryption failed: ${e.message}")
                        }
                    }
                    if (updates.isNotEmpty()) {
                        database.reference.updateChildren(updates)
                            .addOnSuccessListener {
                                Log.d("ChatActivity", "Re-encrypted ${updates.size} messages for $newUid")
                            }
                            .addOnFailureListener { e ->
                                Log.e("ChatActivity", "Re-encryption batch failed: ${e.message}")
                            }
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    // ======================= MESSAGES =======================

    private fun listenForMessages() {
        val messagesRef = database.reference.child("chatrooms").child(chatroomId).child("messages")

        messagesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                messages.clear()
                val myId = auth.currentUser?.uid ?: return

                for (msgSnap in snapshot.children) {
                    val senderId   = msgSnap.child("senderId").getValue(String::class.java) ?: ""
                    val senderName = msgSnap.child("senderName").getValue(String::class.java) ?: "Unknown"
                    val timestamp  = msgSnap.child("timestamp").getValue(Long::class.java) ?: 0L
                    val isFile     = msgSnap.child("isFile").getValue(Boolean::class.java) ?: false

                    if (isFile) {
                        val fileName     = msgSnap.child("fileName").getValue(String::class.java) ?: "File"
                        val fileType     = msgSnap.child("fileType").getValue(String::class.java) ?: ""
                        val fileData     = msgSnap.child("fileData").getValue(String::class.java) ?: ""
                        val fileCategory = msgSnap.child("fileCategory").getValue(String::class.java) ?: "other"
                        messages.add(Message(
                            senderId = senderId,
                            senderName = senderName,
                            text = fileName,
                            timestamp = timestamp,
                            isFile = true,
                            fileName = fileName,
                            fileType = fileType,
                            fileData = fileData,
                            fileCategory = fileCategory
                        ))
                        continue
                    }

                    val encryptedText = msgSnap.child("encryptedText").getValue(String::class.java) ?: continue
                    val iv            = msgSnap.child("iv").getValue(String::class.java) ?: continue

                    val myEncryptedKey = msgSnap.child("encryptedKeys").child(myId).getValue(String::class.java)
                    if (myEncryptedKey == null) {
                        val text = if (senderId == myId) "\uD83D\uDCE4 [Sent]" else "\uD83D\uDD12 [Encrypted]"
                        messages.add(Message(senderId, senderName, text, timestamp))
                        continue
                    }

                    val displayText = try {
                        val aesKeyBytes = CryptoManager.rsaDecrypt(myEncryptedKey)
                        CryptoManager.aesDecrypt(encryptedText, iv, aesKeyBytes)
                    } catch (e: Exception) {
                        Log.e("ChatActivity", "Decrypt failed: ${e.message}")
                        "[Decryption Failed]"
                    }

                    messages.add(Message(
                        senderId = senderId,
                        senderName = senderName,
                        text = displayText,
                        timestamp = timestamp
                    ))
                }

                messageAdapter.notifyDataSetChanged()
                if (messages.isNotEmpty()) {
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ChatActivity", "Messages listener cancelled: ${error.message}")
            }
        }
        messagesRef.orderByChild("timestamp").addValueEventListener(messagesListener!!)
    }

    private fun sendEncryptedMessage() {
        val plaintext = binding.etMessageInput.text.toString().trim()
        if (plaintext.isEmpty()) return

        val myId = auth.currentUser?.uid ?: return
        val myName = participantNames[myId] ?: "Me"

        database.reference.child("chatrooms").child(chatroomId)
            .child("meta").child("mutedParticipants").child(myId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists() && snapshot.getValue(Boolean::class.java) == true) {
                        Toast.makeText(this@ChatActivity, "You are muted by the host", Toast.LENGTH_SHORT).show()
                        return
                    }
                    doSendMessage(myId, myName, plaintext)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun doSendMessage(myId: String, myName: String, plaintext: String) {
        val myPubKey = participantPublicKeys[myId]
        if (myPubKey.isNullOrBlank()) {
            Toast.makeText(this, "Your public key is missing. Please rejoin the room.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val aesKey = CryptoManager.generateAESKey()
            val (encryptedText, iv) = CryptoManager.aesEncrypt(plaintext, aesKey)
            val encryptedKeys = mutableMapOf<String, String>()

            try {
                encryptedKeys[myId] = CryptoManager.rsaEncrypt(aesKey.encoded, myPubKey)
            } catch (e: Exception) {
                Log.e("ChatActivity", "Failed to encrypt for self: ${e.message}")
            }

            for ((uid, pubKey) in participantPublicKeys) {
                if (uid == myId) continue
                try {
                    encryptedKeys[uid] = CryptoManager.rsaEncrypt(aesKey.encoded, pubKey)
                } catch (e: Exception) {
                    Log.e("ChatActivity", "Failed to encrypt for $uid: ${e.message}")
                }
            }

            if (!encryptedKeys.containsKey(myId)) {
                Toast.makeText(this, "Could not encrypt message for yourself", Toast.LENGTH_SHORT).show()
                return
            }

            val messageData = mapOf(
                "senderId" to myId,
                "senderName" to myName,
                "encryptedText" to encryptedText,
                "iv" to iv,
                "encryptedKeys" to encryptedKeys,
                "timestamp" to System.currentTimeMillis()
            )

            database.reference.child("chatrooms").child(chatroomId).child("messages")
                .push().setValue(messageData)
                .addOnSuccessListener { binding.etMessageInput.text?.clear() }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Send failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }

        } catch (e: Exception) {
            Log.e("ChatActivity", "Encryption error: ${e.message}", e)
            Toast.makeText(this, "Encryption failed", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================= LIFECYCLE =======================

    override fun onDestroy() {
        super.onDestroy()

        val ref = database.reference.child("chatrooms").child(chatroomId)

        messagesListener?.let { ref.child("messages").removeEventListener(it) }
        participantsListener?.let { ref.child("meta").child("participantPublicKeys").removeEventListener(it) }
        participantNamesListener?.let { ref.child("meta").child("participantNames").removeEventListener(it) }
        mutedParticipantsListener?.let { ref.child("meta").child("mutedParticipants").removeEventListener(it) }
        hostIdListener?.let { ref.child("meta").child("hostId").removeEventListener(it) }
        joinRequestsListener?.let { ref.child("meta").child("joinRequests").removeEventListener(it) }
        kickListener?.let {
            ref.child("meta").child("participants").child(auth.currentUser?.uid ?: "").removeEventListener(it)
        }

        if (isTemporary) {
            val updates = mapOf(
                "chatrooms/$chatroomId/meta/ended" to true,
                "chatrooms/$chatroomId/meta/endedAt" to System.currentTimeMillis()
            )
            database.reference.updateChildren(updates)
        }
    }
}
