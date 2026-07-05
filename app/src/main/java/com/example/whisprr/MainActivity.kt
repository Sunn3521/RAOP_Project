package com.example.whisprr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.whisprr.databinding.ActivityMainBinding
import com.example.whisprr.databinding.DialogCreateRoomBinding
import com.example.whisprr.databinding.DialogJoinRoomBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var prefs: SharedPreferences
    private var darkMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("whisprr_prefs", MODE_PRIVATE)
        darkMode = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth     = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance(WhisprrApplication.DATABASE_URL)

        setSupportActionBar(binding.toolbar)

        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_history -> {
                    startActivity(Intent(this, HistoryActivity::class.java))
                    true
                }
                R.id.action_logout -> {
                    auth.signOut()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                    true
                }
                R.id.action_toggle_theme -> {
                    darkMode = !darkMode
                    prefs.edit().putBoolean("dark_mode", darkMode).apply()
                    AppCompatDelegate.setDefaultNightMode(
                        if (darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                    )
                    recreate()
                    true
                }
                else -> false
            }
        }

        binding.btnCreateChat.setOnClickListener { showCreateRoomDialog() }
        binding.btnJoinChat.setOnClickListener { showJoinRoomDialog() }
    }

    // ======================= CREATE ROOM =======================

    private fun showCreateRoomDialog() {
        val myId = auth.currentUser?.uid ?: return
        val chatroomId = java.util.UUID.randomUUID().toString()
        val inviteCode = generateInviteCode()

        val dialogBinding = DialogCreateRoomBinding.inflate(LayoutInflater.from(this))
        dialogBinding.tvRoomCode.text = inviteCode

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()

        dialogBinding.btnCopyCode.setOnClickListener {
            copyToClipboard("Whisprr Room Code", inviteCode)
            Toast.makeText(this, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnEnterChat.setOnClickListener {
            val roomName = dialogBinding.etRoomName.text.toString().trim()
                .ifEmpty { "Room $inviteCode" }
            dialog.dismiss()
            createRoom(chatroomId, inviteCode, roomName)
        }

        dialog.show()
    }

    private fun createRoom(chatroomId: String, inviteCode: String, roomName: String) {
        val myId = auth.currentUser?.uid ?: return

        val metaRef = database.reference.child("chatrooms").child(chatroomId).child("meta")
        val metaData = mapOf<String, Any>(
            "hostId" to myId,
            "roomName" to roomName,
            "inviteCode" to inviteCode,
            "isTemporary" to true,
            "createdAt" to System.currentTimeMillis(),
            "settings" to mapOf(
                "entryMode" to "open",
                "locked" to false,
                "showParticipantsToAll" to true,
                "allowedFileTypes" to mapOf(
                    "pdf" to true,
                    "images" to true,
                    "videos" to true,
                    "audio" to true,
                    "archives" to true,
                    "other" to true,
                    "everything" to false
                )
            ),
            "participantPublicKeys" to mapOf(
                CryptoManager.BOT_UID to CryptoManager.BOT_PUBLIC_KEY
            ),
            "participantNames" to mapOf(
                CryptoManager.BOT_UID to "Whisprr Bot"
            )
        )

        metaRef.setValue(metaData)
            .addOnSuccessListener {
                joinRoomAndEnterChat(chatroomId, inviteCode)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to create room: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ======================= JOIN ROOM =======================

    private fun showJoinRoomDialog() {
        val dialogBinding = DialogJoinRoomBinding.inflate(LayoutInflater.from(this))

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnEnterChatJoin.setOnClickListener {
            val code = dialogBinding.etRoomCode.text.toString().trim().uppercase()
            if (code.isEmpty()) {
                Toast.makeText(this, "Please enter a room code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            findAndJoinRoom(code)
        }

        dialog.show()
    }

    private fun findAndJoinRoom(code: String) {
        val myId = auth.currentUser?.uid ?: return
        database.reference.child("chatrooms")
            .orderByChild("meta/inviteCode").equalTo(code)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        Toast.makeText(this@MainActivity, "Room not found. Check the code and try again.", Toast.LENGTH_LONG).show()
                        return
                    }

                    val room = snapshot.children.first()
                    val chatroomId = room.key ?: run {
                        Toast.makeText(this@MainActivity, "Invalid room data", Toast.LENGTH_SHORT).show()
                        return
                    }
                    val meta = room.child("meta")
                    val ended = meta.child("ended").getValue(Boolean::class.java) ?: false

                    if (ended) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Session Ended")
                            .setMessage("This chatroom session has already ended.")
                            .setPositiveButton("OK", null)
                            .show()
                        return
                    }

                    // Check if permanently banned
                    val isBanned = meta.child("bannedParticipants/$myId").getValue(Boolean::class.java) ?: false
                    if (isBanned) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Access Denied")
                            .setMessage("You have been permanently banned from this room.")
                            .setPositiveButton("OK", null)
                            .show()
                        return
                    }

                    val locked = meta.child("settings/locked").getValue(Boolean::class.java) ?: false

                    if (locked) {
                        Toast.makeText(this@MainActivity, "Room is locked. Cannot join.", Toast.LENGTH_LONG).show()
                        return
                    }

                    // Check if previously kicked
                    val wasKicked = meta.child("kickedParticipants/$myId").exists()

                    val entryMode = meta.child("settings/entryMode").getValue(String::class.java) ?: "open"
                    if (entryMode == "request" || wasKicked) {
                        submitJoinRequest(chatroomId, code, wasKicked)
                    } else {
                        joinRoomAndEnterChat(chatroomId, code)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@MainActivity, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun submitJoinRequest(chatroomId: String, inviteCode: String, wasKicked: Boolean = false) {
        val myId = auth.currentUser?.uid ?: return
        database.reference.child("users").child(myId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(mySnapshot: DataSnapshot) {
                    val myName = mySnapshot.child("name").getValue(String::class.java) ?: "User"
                    val requestData = mapOf(
                        "name" to myName,
                        "timestamp" to System.currentTimeMillis()
                    )
                    database.reference.child("chatrooms").child(chatroomId).child("meta")
                        .child("joinRequests").child(myId).setValue(requestData)
                        .addOnSuccessListener {
                            val msg = if (wasKicked) {
                                "You were previously kicked. Join request sent to host for re-approval..."
                            } else {
                                "Join request sent. Waiting for host approval..."
                            }
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            waitForJoinApproval(chatroomId, inviteCode)
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this@MainActivity, "Failed to send request: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun waitForJoinApproval(chatroomId: String, inviteCode: String) {
        val myId = auth.currentUser?.uid ?: return
        val approvalRef = database.reference.child("chatrooms").child(chatroomId)
            .child("meta").child("participants").child(myId)

        approvalRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists() && snapshot.getValue(Boolean::class.java) == true) {
                    // Approved! Join the room
                    approvalRef.removeEventListener(this)
                    joinRoomAndEnterChat(chatroomId, inviteCode)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // ======================= ENTER CHAT =======================

    private fun joinRoomAndEnterChat(chatroomId: String, inviteCode: String) {
        val myId = auth.currentUser?.uid ?: return

        database.reference.child("users").child(myId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(mySnapshot: DataSnapshot) {
                    var myPublicKey = mySnapshot.child("publicKey").getValue(String::class.java)
                    // If public key is missing or empty, generate a new one and save to profile
                    if (myPublicKey.isNullOrBlank()) {
                        myPublicKey = CryptoManager.generateRSAKeyPair()
                        database.reference.child("users").child(myId).child("publicKey").setValue(myPublicKey)
                    }
                    val myName = mySnapshot.child("name").getValue(String::class.java) ?: "Me"

                    val participantRef = database.reference
                        .child("chatrooms")
                        .child(chatroomId)
                        .child("meta")
                        .child("participants")
                        .child(myId)

                    participantRef.setValue(true)
                        .addOnSuccessListener {
                            val updates = mapOf<String, Any>(
                                "chatrooms/$chatroomId/meta/participantPublicKeys/$myId" to myPublicKey,
                                "chatrooms/$chatroomId/meta/participantNames/$myId" to myName,
                                "chatrooms/$chatroomId/meta/participantPublicKeys/${CryptoManager.BOT_UID}" to CryptoManager.BOT_PUBLIC_KEY,
                                "chatrooms/$chatroomId/meta/participantNames/${CryptoManager.BOT_UID}" to "Whisprr Bot"
                            )
                            database.reference.updateChildren(updates)
                                .addOnSuccessListener {
                                    startChatActivity(chatroomId, inviteCode, true)
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(this@MainActivity, "Error joining room: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                        }
                        .addOnFailureListener { e ->
                            Toast.makeText(this@MainActivity, "Failed to join room: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(this@MainActivity, "Error: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun startChatActivity(chatroomId: String, inviteCode: String, isTemporary: Boolean) {
        saveRoomToHistory(chatroomId, inviteCode)
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("chatroomId", chatroomId)
            putExtra("inviteCode", inviteCode)
            putExtra("isTemporary", isTemporary)
        }
        startActivity(intent)
    }

    private fun saveRoomToHistory(chatroomId: String, inviteCode: String) {
        val historyKey = "room_history"
        val historyJson = prefs.getString(historyKey, "[]") ?: "[]"
        val history = try {
            org.json.JSONArray(historyJson)
        } catch (e: Exception) {
            org.json.JSONArray()
        }

        // Check if already exists
        var exists = false
        for (i in 0 until history.length()) {
            val item = history.getJSONObject(i)
            if (item.getString("chatroomId") == chatroomId) {
                item.put("visitedAt", System.currentTimeMillis())
                exists = true
                break
            }
        }

        if (!exists) {
            val newItem = org.json.JSONObject().apply {
                put("chatroomId", chatroomId)
                put("inviteCode", inviteCode)
                put("visitedAt", System.currentTimeMillis())
            }
            history.put(newItem)
        }

        prefs.edit().putString(historyKey, history.toString()).apply()
    }

    // ======================= UTILS =======================

    private fun generateInviteCode(): String {
        return java.util.UUID.randomUUID().toString().replace("-", "").uppercase().take(8)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }
}

