package com.example.whisprr

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
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
    private var messagesListener: ChildEventListener? = null
    private var participantsListener: ValueEventListener? = null
    private var participantNamesListener: ValueEventListener? = null
    private var joinRequestsListener: ValueEventListener? = null
    private var kickListener: ValueEventListener? = null
    private var mutedParticipantsListener: ValueEventListener? = null
    private var hostIdListener: ValueEventListener? = null
    private var approvalListener: ValueEventListener? = null
    private var participantAdapter: ParticipantAdapter? = null
    private var activeJoinRequestUid: String? = null
    private var joinRequestDialog: AlertDialog? = null

    private val participantPublicKeys = mutableMapOf<String, String>()
    private val participantNames = mutableMapOf<String, String>()
    private val participantsList = mutableListOf<Participant>()
    private val mutedParticipants = mutableSetOf<String>()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleFileSelection(it) }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            Log.d("ChatActivity", "Permissions granted")
        }
        // Silently grant or deny - no popup needed
    }

    private var isViewMode: Boolean   = false
    private var localChatFile: String? = null

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
        isViewMode = intent.getBooleanExtra("isViewMode", false)
        localChatFile = intent.getStringExtra("localChatFile")
        val passedRoomName = intent.getStringExtra("roomName")

        // Check if this participant is kicked (only for active rooms, not view mode)
        if (!isViewMode) {
            checkAndHandleKickedParticipant(chatroomId)
            return
        }

        setSupportActionBar(binding.chatToolbar)
        binding.chatToolbar.setTitleTextColor(android.graphics.Color.WHITE)
        binding.chatToolbar.setSubtitleTextColor(android.graphics.Color.WHITE)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.chatToolbar.setNavigationOnClickListener { finish() }
        binding.chatToolbar.setOnLongClickListener {
            showCopyCodeDialog(inviteCode)
            true
        }

        if (isViewMode) {
            binding.layoutInput.visibility = View.GONE
            supportActionBar?.subtitle = "View Mode"
        }

        database.reference.child("chatrooms").child(chatroomId).child("meta/roomName")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val roomName = passedRoomName ?: (snapshot.getValue(String::class.java) ?: "Room $inviteCode")
                    supportActionBar?.title = roomName
                }
                override fun onCancelled(error: DatabaseError) {}
            })

        messageAdapter = MessageAdapter(messages, auth.currentUser?.uid ?: "")
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = messageAdapter

        if (!isViewMode) {
            checkHostStatus()
            listenForParticipants()
            listenForMessages()
            checkPermissions()
            binding.btnSend.setOnClickListener { sendEncryptedMessage() }
            binding.btnAttach.setOnClickListener { launchFilePicker() }
        } else {
            listenForMessages()
            if (localChatFile != null) {
                loadMessagesFromFile(localChatFile!!)
            } else {
                listenForMessages()
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(android.Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missing = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun setupDialog(dialog: AlertDialog) {
        // Remove transparent background - use default dialog background
        // dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setCancelable(true)
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

    // ======================= FILE SHARING =======================

    private fun launchFilePicker() {
        filePickerLauncher.launch("*/*")
    }

    private fun handleFileSelection(uri: Uri) {
        val myId = auth.currentUser?.uid ?: return
        database.reference.child("chatrooms").child(chatroomId)
            .child("meta").child("mutedParticipants").child(myId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists() && snapshot.getValue(Boolean::class.java) == true) {
                        AlertDialog.Builder(this@ChatActivity)
                            .setTitle("Muted")
                            .setMessage("You have been muted by the host.")
                            .setPositiveButton("OK", null)
                            .show()
                        return
                    }
                    validateAndSendFile(uri)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun validateAndSendFile(uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val category = getFileCategory(mimeType, uri)
        val fileName = getFileName(uri) ?: "unknown"
        val fileSize = getFileSize(uri)
        if (fileSize > 10 * 1024 * 1024) {
            Toast.makeText(this, "File too large (max 10MB)", Toast.LENGTH_SHORT).show()
            return
        }

        database.reference.child("chatrooms").child(chatroomId).child("meta/settings/allowedFileTypes")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val everything = snapshot.child("everything").getValue(Boolean::class.java) ?: false
                    val allowed = snapshot.child(category).getValue(Boolean::class.java) ?: false
                    if (everything || allowed) {
                        readAndSendFile(uri, fileName, mimeType, category)
                    } else {
                        Toast.makeText(this@ChatActivity, "File type not allowed by host", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun getFileCategory(mimeType: String, uri: Uri) = when {
        mimeType.startsWith("image/") -> if (mimeType == "image/gif") "gifs" else "images"
        mimeType.startsWith("video/") -> "videos"
        mimeType.startsWith("audio/") -> "audio"
        mimeType == "application/pdf" -> "pdf"
        else -> {
            val ext = getFileExtension(uri).lowercase(Locale.getDefault())
            Log.d("ChatActivity", "MIME: $mimeType, Ext: $ext")
            when (ext) {
                "gif" -> "gifs"
                "jpg", "jpeg", "png", "webp", "bmp" -> "images"
                "mp4", "mkv", "webm", "avi", "mov" -> "videos"
                "mp3", "wav", "ogg" -> "audio"
                "pdf" -> "pdf"
                else -> "other"
            }
        }
    }

    private fun getFileExtension(uri: Uri): String {
        val path = uri.path ?: return ""
        val ext = path.substringAfterLast('.', "")
        if (ext.isNotBlank()) return ext
        val fileName = getFileName(uri) ?: return ""
        return fileName.substringAfterLast('.', "")
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
        return size
    }

    private fun readAndSendFile(uri: Uri, fileName: String, mimeType: String, category: String) {
        val myId = auth.currentUser?.uid ?: return
        val myName = participantNames[myId] ?: "Me"
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArrayOutputStream()
                val tmp = ByteArray(4096)
                var read: Int
                while (inputStream.read(tmp).also { read = it } != -1) buffer.write(tmp, 0, read)
                val base64Data = Base64.encodeToString(buffer.toByteArray(), Base64.DEFAULT)
                sendFileMessage(myId, myName, fileName, mimeType, base64Data, category)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "File read failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendFileMessage(myId: String, myName: String, fileName: String, fileType: String, fileData: String, category: String) {
        val messageData = mapOf("senderId" to myId, "senderName" to myName, "timestamp" to System.currentTimeMillis(), "isFile" to true, "fileName" to fileName, "fileType" to fileType, "fileData" to fileData, "fileCategory" to category)
        database.reference.child("chatrooms").child(chatroomId).child("messages").push().setValue(messageData)
    }

    // ======================= HOST / PARTICIPANTS =======================

    private fun checkHostStatus() {
        val myId = auth.currentUser?.uid ?: return
        hostIdListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newHostId = snapshot.getValue(String::class.java) ?: ""
                if (newHostId != hostId) {
                    hostId = newHostId
                    isHost = (hostId == myId)
                    invalidateOptionsMenu()
                    rebuildParticipantsList()
                    if (isHost) {
                        listenForJoinRequests()
                        participantPublicKeys.forEach { (uid, pubKey) ->
                            if (uid != myId) reencryptMessagesForNewUser(uid, pubKey)
                        }
                    } else {
                        listenForKick()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.reference.child("chatrooms").child(chatroomId).child("meta/hostId").addValueEventListener(hostIdListener!!)
    }

    private fun checkAndHandleKickedParticipant(chatroomId: String) {
        val myId = auth.currentUser?.uid ?: return
        database.reference.child("chatrooms").child(chatroomId).child("meta/kickedParticipants").child(myId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        // User is kicked - show popup and send them back
                        AlertDialog.Builder(this@ChatActivity)
                            .setTitle("Kicked from Room")
                            .setMessage("You have been kicked out by the host.")
                            .setCancelable(false)
                            .setPositiveButton("OK") { _, _ ->
                                // Remove from participants and go back to main
                                removeFromRoomAndShowRejoinPrompt()
                            }
                            .show()
                    } else {
                        // Not kicked, continue normal flow
                        continueNormalChatFlow()
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    // Continue with normal flow on error
                    continueNormalChatFlow()
                }
            })
    }

    private fun removeFromRoomAndShowRejoinPrompt() {
        val myId = auth.currentUser?.uid ?: return
        database.reference.child("chatrooms").child(chatroomId).child("meta/roomName")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(metaSnapshot: DataSnapshot) {
                    val roomName = metaSnapshot.getValue(String::class.java) ?: "Room"
                    // Remove from participants
                    database.reference.child("chatrooms").child(chatroomId).child("meta/participants").child(myId)
                        .removeValue()
                        .addOnCompleteListener {
                            // Show rejoin prompt
                            AlertDialog.Builder(this@ChatActivity)
                                .setTitle("Access Denied")
                                .setMessage("You were kicked out from this room. Would you like to request to rejoin?")
                                .setCancelable(false)
                                .setPositiveButton("Request to Rejoin") { _, _ ->
                                    submitJoinRequest(chatroomId, inviteCode, roomName, wasKicked = true)
                                    finish()
                                }
                                .setNegativeButton("Cancel") { _, _ ->
                                    finish()
                                }
                                .show()
                        }
                }
                override fun onCancelled(error: DatabaseError) {
                    finish()
                }
            })
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
                                Toast.makeText(this@ChatActivity, "Join request sent. Waiting for approval...", Toast.LENGTH_LONG).show()
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
            .child("meta/participants").child(myId)

        val checkApprovalState = Runnable {
            joinRequestRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        // Approved!
                        finish()
                    } else {
                        // Still waiting or rejected
                        Handler(Looper.getMainLooper()).postDelayed({
                            joinRequestRef.addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(recheck: DataSnapshot) {
                                    if (!recheck.exists()) {
                                        if (!isFinishing) {
                                            AlertDialog.Builder(this@ChatActivity)
                                                .setTitle("Request Rejected")
                                                .setMessage("Your join request was rejected.")
                                                .setCancelable(false)
                                                .setPositiveButton("OK") { _, _ -> finish() }
                                                .show()
                                        }
                                    }
                                }
                                override fun onCancelled(error: DatabaseError) {}
                            })
                        }, 3000)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }

        approvalListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    checkApprovalState.run()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                approvalListener?.let { joinRequestRef.removeEventListener(it) }
            }
        }
        joinRequestRef.addValueEventListener(approvalListener!!)
    }

    private fun continueNormalChatFlow() {
        // Re-inflate UI and continue with normal flow
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.chatToolbar)
        binding.chatToolbar.setTitleTextColor(android.graphics.Color.WHITE)
        binding.chatToolbar.setSubtitleTextColor(android.graphics.Color.WHITE)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.chatToolbar.setNavigationOnClickListener { finish() }
        binding.chatToolbar.setOnLongClickListener {
            showCopyCodeDialog(inviteCode)
            true
        }

        if (isViewMode) {
            binding.layoutInput.visibility = View.GONE
            supportActionBar?.subtitle = "View Mode"
        }

        database.reference.child("chatrooms").child(chatroomId).child("meta/roomName")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val roomName = (snapshot.getValue(String::class.java) ?: "Room $inviteCode")
                    supportActionBar?.title = roomName
                }
                override fun onCancelled(error: DatabaseError) {}
            })

        messageAdapter = MessageAdapter(messages, auth.currentUser?.uid ?: "")
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = messageAdapter

        if (!isViewMode) {
            checkHostStatus()
            listenForParticipants()
            listenForMessages()
            checkPermissions()
            binding.btnSend.setOnClickListener { sendEncryptedMessage() }
            binding.btnAttach.setOnClickListener { launchFilePicker() }
        } else {
            listenForMessages()
            if (localChatFile != null) {
                loadMessagesFromFile(localChatFile!!)
            } else {
                listenForMessages()
            }
        }
    }

    private fun listenForJoinRequests() {
        if (!isHost) return
        joinRequestsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val uid = child.key ?: continue
                    if (joinRequestDialog?.isShowing == true || activeJoinRequestUid == uid) return
                    val name = child.child("name").getValue(String::class.java) ?: "Unknown"
                    showJoinRequestDialog(uid, name)
                    break
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.reference.child("chatrooms").child(chatroomId).child("meta/joinRequests").addValueEventListener(joinRequestsListener!!)
    }

    private fun showJoinRequestDialog(uid: String, name: String) {
        if (joinRequestDialog?.isShowing == true) return
        activeJoinRequestUid = uid

        val dialogBinding = DialogJoinRequestBinding.inflate(LayoutInflater.from(this))
        dialogBinding.tvRequesterName.text = "$name wants to join"
        dialogBinding.tvKickedWarning.visibility = View.GONE
        dialogBinding.cbPermanentlyBan.visibility = View.GONE

        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()
        setupDialog(dialog)
        dialog.setOnDismissListener {
            joinRequestDialog = null
            activeJoinRequestUid = null
        }

        dialogBinding.btnAccept.setOnClickListener {
            database.reference.updateChildren(mapOf(
                "chatrooms/$chatroomId/meta/participants/$uid" to true,
                "chatrooms/$chatroomId/meta/joinRequests/$uid" to null,
                "chatrooms/$chatroomId/meta/kickedParticipants/$uid" to null
            ))
            dialog.dismiss()
        }
        dialogBinding.btnDecline.setOnClickListener {
            val permanentlyBan = dialogBinding.cbPermanentlyBan.isChecked
            val updates = mutableMapOf<String, Any?>()
            updates["chatrooms/$chatroomId/meta/joinRequests/$uid"] = null
            if (permanentlyBan) {
                updates["chatrooms/$chatroomId/meta/permanentBans/$uid"] = true
            }
            database.reference.updateChildren(updates)
            dialog.dismiss()
        }

        database.reference.child("chatrooms").child(chatroomId).child("meta/kickedParticipants").child(uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        dialogBinding.tvKickedWarning.text = "$name is requesting to join again after being kicked out"
                        dialogBinding.tvKickedWarning.visibility = View.VISIBLE
                        dialogBinding.cbPermanentlyBan.visibility = View.VISIBLE
                    }
                    dialog.show()
                    joinRequestDialog = dialog
                }
                override fun onCancelled(error: DatabaseError) {
                    dialog.show()
                    joinRequestDialog = dialog
                }
            })
    }

    private fun showParticipantsDialog() {
        val myId = auth.currentUser?.uid ?: return
        database.reference.child("chatrooms").child(chatroomId).child("meta/settings/showParticipantsToAll")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.getValue(Boolean::class.java) == false && !isHost) {
                        Toast.makeText(this@ChatActivity, "Hidden by host", Toast.LENGTH_SHORT).show()
                        return
                    }
                    openParticipantsDialog(myId)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun openParticipantsDialog(myId: String) {
        val dialogBinding = DialogParticipantsBinding.inflate(LayoutInflater.from(this))
        dialogBinding.layoutHostActions.isVisible = isHost
        participantAdapter = ParticipantAdapter(participantsList, myId, isHost,
            onKick = { p -> kickParticipant(p.uid) },
            onMute = { p ->
                val mutedRef = database.reference.child("chatrooms").child(chatroomId).child("meta/mutedParticipants").child(p.uid)
                if (p.isMuted) mutedRef.removeValue() else mutedRef.setValue(true)
            }
        )
        dialogBinding.rvParticipants.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvParticipants.adapter = participantAdapter

        dialogBinding.btnMuteAll.setOnClickListener { muteAllParticipants() }
        dialogBinding.btnKickAll.setOnClickListener { kickAllParticipants() }

        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()
        setupDialog(dialog)
        dialogBinding.btnCloseParticipants.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun muteAllParticipants() {
        val updates = mutableMapOf<String, Any?>()
        val allCurrentlyMuted = participantsList.filter { !it.isHost }.all { it.isMuted }
        
        participantsList.forEach { p ->
            if (!p.isHost) {
                // If everyone is already muted, unmute all. Otherwise, mute all.
                if (allCurrentlyMuted) {
                    updates["meta/mutedParticipants/${p.uid}"] = null
                } else {
                    updates["meta/mutedParticipants/${p.uid}"] = true
                }
            }
        }
        if (updates.isNotEmpty()) {
            database.reference.child("chatrooms").child(chatroomId).updateChildren(updates)
        }
    }

    private fun kickAllParticipants() {
        // Capture the participants to kick before removing them from the local list
        val kickedParticipants = participantsList.filter { !it.isHost }
        participantsList.removeAll { !it.isHost }
        participantAdapter?.notifyDataSetChanged()

        val updates = mutableMapOf<String, Any?>()
        val now = System.currentTimeMillis()
        kickedParticipants.forEach { p ->
            updates["meta/participants/${p.uid}"] = null
            updates["meta/kickedParticipants/${p.uid}"] = now
            updates["meta/participantNames/${p.uid}"] = null
            updates["meta/participantPublicKeys/${p.uid}"] = null
        }
        if (updates.isNotEmpty()) {
            database.reference.child("chatrooms").child(chatroomId).updateChildren(updates)
        }
    }

    private fun kickParticipant(uid: String) {
        // Immediately remove from local list for instant UI update
        participantsList.removeAll { it.uid == uid }
        participantAdapter?.notifyDataSetChanged()

        val updates = mapOf(
            "chatrooms/$chatroomId/meta/participants/$uid" to null,
            "chatrooms/$chatroomId/meta/kickedParticipants/$uid" to System.currentTimeMillis(),
            "chatrooms/$chatroomId/meta/participantNames/$uid" to null,
            "chatrooms/$chatroomId/meta/participantPublicKeys/$uid" to null
        )
        database.reference.updateChildren(updates)
    }

    private fun listenForKick() {
        val myId = auth.currentUser?.uid ?: return
        kickListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    if (!isFinishing) {
                        AlertDialog.Builder(this@ChatActivity)
                            .setTitle("Kicked")
                            .setMessage("You have been kicked out by the host.")
                            .setCancelable(false)
                            .setPositiveButton("OK") { _, _ -> finish() }
                            .show()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        database.reference.child("chatrooms").child(chatroomId).child("meta/participants").child(myId).addValueEventListener(kickListener!!)
    }

    private fun showHostSettingsDialog() {
        val dialogBinding = DialogHostSettingsBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this).setView(dialogBinding.root).create()
        setupDialog(dialog)
        database.reference.child("chatrooms").child(chatroomId).child("meta").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                dialogBinding.etRoomName.setText(snapshot.child("roomName").getValue(String::class.java) ?: "")
                val s = snapshot.child("settings")
                dialogBinding.switchLockRoom.isChecked = s.child("locked").getValue(Boolean::class.java) ?: false
                dialogBinding.switchShowParticipants.isChecked = s.child("showParticipantsToAll").getValue(Boolean::class.java) ?: true
                dialogBinding.switchAllowHistory.isChecked = s.child("allowHistory").getValue(Boolean::class.java) ?: true
                dialogBinding.switchDeleteDataFromServer.isChecked = s.child("deleteDataFromServer").getValue(Boolean::class.java) ?: false
                if (s.child("entryMode").getValue(String::class.java) == "request") dialogBinding.radioRequest.isChecked = true else dialogBinding.radioOpen.isChecked = true
                
                val f = s.child("allowedFileTypes")
                dialogBinding.cbPdf.isChecked = f.child("pdf").getValue(Boolean::class.java) ?: true
                dialogBinding.cbImages.isChecked = f.child("images").getValue(Boolean::class.java) ?: true
                dialogBinding.cbVideos.isChecked = f.child("videos").getValue(Boolean::class.java) ?: true
                dialogBinding.cbAudio.isChecked = f.child("audio").getValue(Boolean::class.java) ?: true
                dialogBinding.cbArchives.isChecked = f.child("archives").getValue(Boolean::class.java) ?: true
                dialogBinding.cbEverything.isChecked = f.child("everything").getValue(Boolean::class.java) ?: false
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        dialogBinding.btnEndSession.setOnClickListener {
            AlertDialog.Builder(this).setTitle("End Session?").setMessage("Room will close permanently.")
                .setPositiveButton("End") { _, _ ->
                    val updates = mutableMapOf<String, Any>("meta/ended" to true)
                    if (dialogBinding.switchDeleteDataFromServer.isChecked) {
                        updates["meta/dataDeleted"] = true
                        database.reference.child("chatrooms").child(chatroomId).child("messages").removeValue()
                    }
                    database.reference.child("chatrooms").child(chatroomId).updateChildren(updates)
                    finish()
                }
                .setNegativeButton("Cancel", null).show()
        }
        dialogBinding.btnTransferHost.setOnClickListener { showTransferHostDialog() }
        dialogBinding.btnCloseSettings.setOnClickListener {
            val updates = mapOf(
                "meta/roomName" to dialogBinding.etRoomName.text.toString(),
                "meta/settings/locked" to dialogBinding.switchLockRoom.isChecked,
                "meta/settings/entryMode" to if (dialogBinding.radioRequest.isChecked) "request" else "open",
                "meta/settings/showParticipantsToAll" to dialogBinding.switchShowParticipants.isChecked,
                "meta/settings/allowHistory" to dialogBinding.switchAllowHistory.isChecked,
            "meta/settings/deleteDataFromServer" to dialogBinding.switchDeleteDataFromServer.isChecked,
                "meta/settings/allowedFileTypes/pdf" to dialogBinding.cbPdf.isChecked,
                "meta/settings/allowedFileTypes/images" to dialogBinding.cbImages.isChecked,
                "meta/settings/allowedFileTypes/videos" to dialogBinding.cbVideos.isChecked,
                "meta/settings/allowedFileTypes/audio" to dialogBinding.cbAudio.isChecked,
                "meta/settings/allowedFileTypes/archives" to dialogBinding.cbArchives.isChecked,
                "meta/settings/allowedFileTypes/everything" to dialogBinding.cbEverything.isChecked
            )
            database.reference.child("chatrooms").child(chatroomId).updateChildren(updates)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showCopyCodeDialog(code: String) {
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

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    private fun showTransferHostDialog() {
        val others = participantsList.filter { !it.isHost }
        if (others.isEmpty()) { Toast.makeText(this, "No one to transfer to", Toast.LENGTH_SHORT).show(); return }
        val names = others.map { it.name }.toTypedArray()
        val dialog = AlertDialog.Builder(this).setTitle("Transfer Host To").setItems(names) { _, i ->
            database.reference.child("chatrooms").child(chatroomId).child("meta/hostId").setValue(others[i].uid)
                .addOnSuccessListener { isHost = false; invalidateOptionsMenu(); Toast.makeText(this, "Host transferred", Toast.LENGTH_SHORT).show() }
        }.setNegativeButton("Cancel", null).create()
        setupDialog(dialog)
        dialog.show()
    }

    private fun listenForParticipants() {
        database.reference.child("chatrooms").child(chatroomId).child("meta/participantPublicKeys").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val oldKeys = participantPublicKeys.toMap()
                participantPublicKeys.clear()
                for (child in snapshot.children) {
                    val uid = child.key ?: continue
                    val key = child.getValue(String::class.java) ?: continue
                    participantPublicKeys[uid] = key
                    if (isHost && uid != auth.currentUser?.uid && (!oldKeys.containsKey(uid) || oldKeys[uid] != key)) reencryptMessagesForNewUser(uid, key)
                }
                rebuildParticipantsList()
                participantAdapter?.notifyDataSetChanged()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        database.reference.child("chatrooms").child(chatroomId).child("meta/participantNames").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                participantNames.clear()
                for (child in snapshot.children) participantNames[child.key ?: ""] = child.getValue(String::class.java) ?: ""
                rebuildParticipantsList()
                participantAdapter?.notifyDataSetChanged()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
        database.reference.child("chatrooms").child(chatroomId).child("meta/mutedParticipants").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                mutedParticipants.clear()
                for (child in snapshot.children) if (child.getValue(Boolean::class.java) == true) mutedParticipants.add(child.key ?: "")
                rebuildParticipantsList(); participantAdapter?.notifyDataSetChanged()
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun rebuildParticipantsList() {
        participantsList.clear()
        for ((uid, name) in participantNames) {
            if (uid == CryptoManager.BOT_UID) continue
            participantsList.add(Participant(uid, name, uid == hostId, mutedParticipants.contains(uid)))
        }
    }

    private fun reencryptMessagesForNewUser(newUid: String, newPubKey: String) {
        if (!isHost) return
        val myId = auth.currentUser?.uid ?: return
        database.reference.child("chatrooms").child(chatroomId).child("messages").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val updates = mutableMapOf<String, Any>()
                for (msgSnap in snapshot.children) {
                    if (msgSnap.child("encryptedKeys").child(newUid).exists()) continue
                    val hostEncKey = msgSnap.child("encryptedKeys").child(myId).getValue(String::class.java) ?: continue
                    try {
                        val aesKeyBytes = CryptoManager.rsaDecrypt(hostEncKey)
                        updates["chatrooms/$chatroomId/messages/${msgSnap.key}/encryptedKeys/$newUid"] = CryptoManager.rsaEncrypt(aesKeyBytes, newPubKey)
                    } catch (e: Exception) {}
                }
                if (updates.isNotEmpty()) database.reference.updateChildren(updates)
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun listenForMessages() {
        messagesListener = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, p: String?) { processMessage(s) }
            override fun onChildChanged(s: DataSnapshot, p: String?) {
                val index = messages.indexOfFirst { it.timestamp == s.child("timestamp").getValue(Long::class.java) }
                if (index != -1) { messages.removeAt(index); processMessage(s) }
            }
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        database.reference.child("chatrooms").child(chatroomId).child("messages").orderByChild("timestamp").addChildEventListener(messagesListener!!)
    }

    private fun loadMessagesFromFile(filePath: String) {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                Toast.makeText(this, "Chat file not found", Toast.LENGTH_SHORT).show()
                return
            }
            val json = file.readText()
            val jsonArray = org.json.JSONArray(json)
            messages.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val senderId = obj.getString("senderId")
                val senderName = obj.getString("senderName")
                val timestamp = obj.getLong("timestamp")
                val isFile = obj.optBoolean("isFile", false)
                if (isFile) {
                    val fileName = obj.getString("fileName")
                    val fileType = obj.optString("fileType", "")
                    val fileData = obj.optString("fileData", "")
                    val fileCategory = obj.optString("fileCategory", "other")
                    messages.add(Message(senderId, senderName, fileName, timestamp, true, fileName, fileType, fileData, fileCategory))
                } else {
                    val text = obj.getString("text")
                    messages.add(Message(senderId, senderName, text, timestamp))
                }
            }
            messageAdapter.notifyDataSetChanged()
            binding.rvMessages.smoothScrollToPosition(messages.size - 1)
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading chat: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveMessagesToFile(createdAt: Long) {
        try {
            val docsDir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            if (docsDir == null) {
                Log.e("ChatActivity", "External files dir is null")
                return
            }
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            val dateStr = dateFormat.format(Date(createdAt))
            val chatFile = File(docsDir, "chat-$dateStr.json")
            Log.d("ChatActivity", "Saving to file: ${chatFile.absolutePath}, messages: ${messages.size}")
            val jsonArray = org.json.JSONArray()
            messages.forEach { msg ->
                val obj = org.json.JSONObject().apply {
                    put("senderId", msg.senderId)
                    put("senderName", msg.senderName)
                    put("timestamp", msg.timestamp)
                    if (msg.isFile) {
                        put("isFile", true)
                        put("fileName", msg.fileName)
                        put("fileType", msg.fileType)
                        put("fileData", msg.fileData)
                        put("fileCategory", msg.fileCategory)
                    } else {
                        put("text", msg.text)
                    }
                }
                jsonArray.put(obj)
            }
            chatFile.writeText(jsonArray.toString())
            Log.d("ChatActivity", "File saved successfully")
        } catch (e: Exception) {
            Log.e("ChatActivity", "Error saving messages to file", e)
        }
    }

    private fun processMessage(msgSnap: DataSnapshot) {
        val myId = auth.currentUser?.uid ?: return
        val senderId = msgSnap.child("senderId").getValue(String::class.java) ?: ""
        val senderName = msgSnap.child("senderName").getValue(String::class.java) ?: "Unknown"
        val timestamp = msgSnap.child("timestamp").getValue(Long::class.java) ?: 0L
        if (msgSnap.child("isFile").getValue(Boolean::class.java) == true) {
            val name = msgSnap.child("fileName").getValue(String::class.java) ?: "File"
            messages.add(Message(senderId, senderName, name, timestamp, true, name, msgSnap.child("fileType").getValue(String::class.java) ?: "", msgSnap.child("fileData").getValue(String::class.java) ?: "", msgSnap.child("fileCategory").getValue(String::class.java) ?: "other"))
        } else {
            val encText = msgSnap.child("encryptedText").getValue(String::class.java) ?: return
            val iv = msgSnap.child("iv").getValue(String::class.java) ?: return
            val myEncKey = msgSnap.child("encryptedKeys").child(myId).getValue(String::class.java)
            val text = if (myEncKey == null) (if (senderId == myId) "\uD83D\uDCE4 [Sent]" else "\uD83D\uDD12 [Encrypted]")
            else try { CryptoManager.aesDecrypt(encText, iv, CryptoManager.rsaDecrypt(myEncKey)) } catch (e: Exception) { "[Decrypt Error]" }
            messages.add(Message(senderId, senderName, text, timestamp))
        }
        messages.sortBy { it.timestamp }; messageAdapter.notifyDataSetChanged(); binding.rvMessages.smoothScrollToPosition(messages.size - 1)
    }

    private fun sendEncryptedMessage() {
        val myId = auth.currentUser?.uid ?: return
        if (mutedParticipants.contains(myId)) {
            AlertDialog.Builder(this)
                .setTitle("Muted")
                .setMessage("You have been muted by the host.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val plaintext = binding.etMessageInput.text.toString().trim()
        if (plaintext.isEmpty()) return
        val myPubKey = participantPublicKeys[myId] ?: return
        try {
            val aesKey = CryptoManager.generateAESKey()
            val (encText, iv) = CryptoManager.aesEncrypt(plaintext, aesKey)
            val encKeys = mutableMapOf<String, String>()
            participantPublicKeys.forEach { (uid, key) -> try { encKeys[uid] = CryptoManager.rsaEncrypt(aesKey.encoded, key) } catch (e: Exception) {} }
            database.reference.child("chatrooms").child(chatroomId).child("messages").push().setValue(mapOf("senderId" to myId, "senderName" to participantNames[myId], "encryptedText" to encText, "iv" to iv, "encryptedKeys" to encKeys, "timestamp" to System.currentTimeMillis()))
                .addOnSuccessListener { binding.etMessageInput.text?.clear() }
        } catch (e: Exception) { Toast.makeText(this, "Encryption failed", Toast.LENGTH_SHORT).show() }
    }

    override fun finish() {
        if (!isViewMode) removeMeFromParticipants()
        super.finish()
    }

    private fun removeMeFromParticipants() {
        val myId = auth.currentUser?.uid ?: return
        // Use a direct reference to avoid issues during activity destruction
        val participantsRef = FirebaseDatabase.getInstance(WhisprrApplication.DATABASE_URL)
            .reference.child("chatrooms").child(chatroomId).child("meta/participants")
        
        participantsRef.child(myId).removeValue()
        
        // Also remove name and public key to keep meta clean
        val metaRef = FirebaseDatabase.getInstance(WhisprrApplication.DATABASE_URL)
            .reference.child("chatrooms").child(chatroomId).child("meta")
        metaRef.child("participantNames").child(myId).removeValue()
        metaRef.child("participantPublicKeys").child(myId).removeValue()

        // Check if room should end (only bot remains)
        participantsRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                var humanCount = 0
                for (child in snapshot.children) {
                    if (child.key != CryptoManager.BOT_UID) humanCount++
                }
                if (humanCount == 0) {
                    metaRef.child("ended").setValue(true)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        val myId = auth.currentUser?.uid ?: return
        val ref = database.reference.child("chatrooms").child(chatroomId)
        
        messagesListener?.let { ref.child("messages").removeEventListener(it) }
        hostIdListener?.let { ref.child("meta/hostId").removeEventListener(it) }
        kickListener?.let { ref.child("meta/participants/${myId}").removeEventListener(it) }
        approvalListener?.let { ref.child("meta/participants/${myId}").removeEventListener(it) }
        
        // If host is leaving, kick all participants and delete room
        if (isHost) {
            ref.child("meta/participants").addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val updates = mutableMapOf<String, Any?>()
                    val now = System.currentTimeMillis()
                    for (child in snapshot.children) {
                        val uid = child.key ?: continue
                        if (uid != myId) {
                            updates["meta/participants/${uid}"] = null
                            updates["meta/kickedParticipants/${uid}"] = now
                        }
                    }
                    // Remove host and delete room
                    updates["meta/participants/${myId}"] = null
                    ref.updateChildren(updates).addOnCompleteListener {
                        ref.removeValue()
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    // Just remove current user if check fails
                    ref.child("meta/participants/${myId}").removeValue()
                    checkAndDeleteEmptyRoom(ref, myId)
                }
            })
        } else {
            // Non-host participant leaving
            ref.child("meta/participants/${myId}").removeValue()
            checkAndDeleteEmptyRoom(ref, myId)
        }
    }

    private fun checkAndDeleteEmptyRoom(ref: DatabaseReference, myId: String) {
        ref.child("meta/participants").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.hasChildren()) {
                    // No participants left, delete entire room
                    ref.removeValue()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }
}
