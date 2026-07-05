import os

path = r'e:\chat\app\src\main\java\com\example\whisprr\ChatActivity.kt'

with open(path, 'w', encoding='utf-8') as f:
    f.write("""package com.example.whisprr

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

        database.reference.child("chatrooms").child(chatroomId).child("meta/roomName")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val roomName = snapshot.getValue(String::class.java) ?: "Room "
                    supportActionBar?.title = "\\uD83D\\uDD12 "
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
                        Toast.makeText(this@ChatActivity, "You are muted by the host", Toast.LENGTH_SHORT).show()
                        return
                    }
                    validateAndSendFile(uri)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun validateAndSendFile(uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val category = getFileCategory(mimeType)
        val fileName = getFileName(uri) ?: "unknown"
        val fileSize = getFileSize(uri)
        val maxSize = 10 * 1024 * 1024
        if (fileSize > maxSize) {
            AlertDialog.Builder(this@ChatActivity)
                .setTitle("File Too Large")
                .setMessage("This file is  which exceeds the 10MB limit.\\n\\nPlease choose a smaller file.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
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
                            "Files of this type are not allowed.\\n\\nAllowed file types:\\n\\u2022 "
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
            else -> " B"
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
            Log.e("ChatActivity", "File read error: ", e)
            Toast.makeText(this, "File read failed: ", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Send failed: ", Toast.LENGTH_SHORT).show()
            }
    }
""")

print('Part 1 done')
