package com.example.whisprr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private var currentUserName: String = "User"
    private var currentUserEmail: String = ""
    private var currentProfileBase64: String? = null
    private var selectedProfileBitmap: Bitmap? = null
    private var editProfileDialog: AlertDialog? = null
    private var passwordVisible = false
    
    private val profileImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleProfileImageSelection(it) }
    }

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

        // Ensure encryption keys are ready immediately after login/start
        ensureEncryptionKeys()
        loadUserProfile()

        binding.ivProfilePhoto.setOnClickListener { showEditProfileDialog() }
        binding.tvUserNamePreview.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveInlineUserName()
                true
            } else false
        }
        binding.tvUserNamePreview.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveInlineUserName()
        }

        // Setup Badges
        updateBadges()
        binding.badgeHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.badgeTheme.setOnClickListener {
            toggleTheme()
        }

        binding.btnCreateChat.setOnClickListener { showCreateRoomDialog() }
        binding.btnJoinChat.setOnClickListener { showJoinRoomDialog() }
    }

    private fun ensureEncryptionKeys() {
        val myId = auth.currentUser?.uid ?: return
        
        // If no keys locally, generate and update profile
        if (!CryptoManager.hasRSAKeyPair()) {
            val newPubKey = CryptoManager.generateRSAKeyPair()
            database.reference.child("users").child(myId).child("publicKey").setValue(newPubKey)
                .addOnSuccessListener {
                    Log.d("MainActivity", "Generated and saved new encryption keys")
                }
        } else {
            // Keys exist locally, ensure Firebase is in sync
            val localPubKey = CryptoManager.generateRSAKeyPair()
            database.reference.child("users").child(myId).child("publicKey")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val remotePubKey = snapshot.getValue(String::class.java)
                        if (remotePubKey != localPubKey) {
                            database.reference.child("users").child(myId).child("publicKey").setValue(localPubKey)
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        }
    }

    private fun updateBadges() {
        binding.badgeTheme.setImageResource(
            if (darkMode) R.drawable.ic_sun else R.drawable.ic_moon
        )
        // Yellow for Sun, Grey for Moon
        binding.badgeTheme.setColorFilter(
            if (darkMode) android.graphics.Color.parseColor("#FFD600") 
            else android.graphics.Color.parseColor("#9E9E9E")
        )

        val historyJson = prefs.getString("room_history", "[]") ?: "[]"
        try {
            val history = org.json.JSONArray(historyJson)
            val count = history.length()
            if (count > 0) {
                binding.tvHistoryCount.text = count.toString()
                binding.tvHistoryCount.visibility = View.VISIBLE
            } else {
                binding.tvHistoryCount.visibility = View.GONE
            }
        } catch (e: Exception) {
            binding.tvHistoryCount.visibility = View.GONE
        }
    }

    private fun loadUserProfile() {
        val myId = auth.currentUser?.uid ?: return
        database.reference.child("users").child(myId).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                currentUserName = snapshot.child("name").getValue(String::class.java)
                    ?: auth.currentUser?.displayName ?: "User"
                currentUserEmail = snapshot.child("email").getValue(String::class.java)
                    ?: auth.currentUser?.email ?: ""
                currentProfileBase64 = snapshot.child("profilePic").getValue(String::class.java)
                binding.tvUserNamePreview.setText(currentUserName)
                setProfilePhoto(currentProfileBase64)
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun setProfilePhoto(base64: String?) {
        if (base64.isNullOrBlank()) {
            binding.ivProfilePhoto.setImageResource(R.drawable.logo)
            return
        }
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source)
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            binding.ivProfilePhoto.setImageBitmap(bitmap)
        } catch (e: Exception) {
            binding.ivProfilePhoto.setImageResource(R.drawable.logo)
        }
    }

    private fun handleProfileImageSelection(uri: Uri) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            } else {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
            }
            selectedProfileBitmap = cropSquare(bitmap)
            editProfileDialog?.findViewById<android.widget.ImageView>(R.id.ivDialogProfilePhoto)
                ?.setImageBitmap(selectedProfileBitmap)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to load photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cropSquare(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - size) / 2
        val y = (bitmap.height - size) / 2
        return Bitmap.createScaledBitmap(Bitmap.createBitmap(bitmap, x, y, size, size), 300, 300, true)
    }

    private fun showEditProfileDialog() {
        val dialogBinding = com.example.whisprr.databinding.DialogEditProfileBinding.inflate(LayoutInflater.from(this))
        dialogBinding.tvEditProfileText.setTextColor(
            if (darkMode) getColor(R.color.whisprr_primary) else getColor(R.color.whisprr_primary_dark)
        )
        dialogBinding.etUserName.setText(currentUserName)
        dialogBinding.etEmail.setText(currentUserEmail)
        passwordVisible = false
        dialogBinding.etPassword.setText("")
        dialogBinding.etPassword.hint = "Enter new password"
        dialogBinding.etPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        dialogBinding.etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
        dialogBinding.ivDialogProfilePhoto.setImageBitmap(
            selectedProfileBitmap ?: run {
                val bytes = currentProfileBase64?.let { Base64.decode(it, Base64.DEFAULT) }
                if (bytes != null && bytes.isNotEmpty()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                        ImageDecoder.decodeBitmap(source)
                    } else {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                } else null
            } ?: BitmapFactory.decodeResource(resources, R.drawable.logo)
        )

        dialogBinding.btnChangePhoto.setOnClickListener {
            profileImagePickerLauncher.launch("image/*")
        }

        dialogBinding.ivTogglePassword.setOnClickListener {
            passwordVisible = !passwordVisible
            dialogBinding.etPassword.inputType = if (passwordVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            dialogBinding.etPassword.setSelection(dialogBinding.etPassword.text?.length ?: 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        dialog.setOnDismissListener { selectedProfileBitmap = null }
        dialogBinding.ivCloseDialog.setOnClickListener { dialog.dismiss() }

        editProfileDialog = dialog
        dialogBinding.btnSaveProfile.setOnClickListener {
            val newName = dialogBinding.etUserName.text.toString().trim().ifEmpty { currentUserName }
            val newPassword = dialogBinding.etPassword.text.toString().trim()
            val updates = mutableMapOf<String, Any>("name" to newName)
            selectedProfileBitmap?.let {
                val newBase64 = bitmapToBase64(it)
                updates["profilePic"] = newBase64
                currentProfileBase64 = newBase64
            }
            val myId = auth.currentUser?.uid ?: return@setOnClickListener
            val userRef = database.reference.child("users").child(myId)

            fun finishSave() {
                currentUserName = newName
                binding.tvUserNamePreview.setText(currentUserName)
                selectedProfileBitmap?.let { binding.ivProfilePhoto.setImageBitmap(it) }
                selectedProfileBitmap = null
                dialog.dismiss()
                Toast.makeText(this, "Profile updated successfully", Toast.LENGTH_SHORT).show()
            }

            userRef.updateChildren(updates)
                .addOnSuccessListener {
                    if (newPassword.isNotEmpty()) {
                        auth.currentUser?.updatePassword(newPassword)
                            ?.addOnSuccessListener {
                                finishSave()
                            }
                            ?.addOnFailureListener {
                                finishSave()
                                Toast.makeText(this, "Profile updated, but password change failed: ${it.message}", Toast.LENGTH_LONG).show()
                            } ?: run {
                                Toast.makeText(this, "Unable to update password: no authenticated user", Toast.LENGTH_SHORT).show()
                            }
                    } else {
                        finishSave()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Unable to save profile: ${it.message}", Toast.LENGTH_SHORT).show()
                }
        }

        dialog.show()
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
    }

    private fun toggleTheme() {
        darkMode = !darkMode
        prefs.edit().putBoolean("dark_mode", darkMode).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        recreate()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_logout -> {
                auth.signOut()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ======================= CREATE ROOM =======================

    private fun showCreateRoomDialog() {
        val chatroomId = java.util.UUID.randomUUID().toString()
        val inviteCode = generateInviteCode()

        val dialogBinding = DialogCreateRoomBinding.inflate(LayoutInflater.from(this))
        dialogBinding.tvRoomCode.text = inviteCode
        dialogBinding.switchDeleteOnEnd.isChecked = false

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        // Ensure corners are rounded by making the window background transparent
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.btnCopyCode.setOnClickListener {
            copyToClipboard("Whisprr Room Code", inviteCode)
            Toast.makeText(this, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        dialogBinding.btnEnterChat.setOnClickListener {
            val roomName = dialogBinding.etRoomName.text.toString().trim()
                .ifEmpty { "Room $inviteCode" }
            val deleteOnEnd = dialogBinding.switchDeleteOnEnd.isChecked
            dialog.dismiss()
            showLoadingAndCreateRoom(chatroomId, inviteCode, roomName, deleteOnEnd)
        }

        dialog.show()
    }

    private fun showLoadingAndCreateRoom(chatroomId: String, inviteCode: String, roomName: String, deleteOnEnd: Boolean) {
        val loadingDialog = AlertDialog.Builder(this)
            .setMessage("Setting up your secure room...")
            .setCancelable(false)
            .show()

        createRoom(chatroomId, inviteCode, roomName, deleteOnEnd) {
            loadingDialog.dismiss()
        }
    }

    private fun createRoom(chatroomId: String, inviteCode: String, roomName: String, deleteOnEnd: Boolean, onComplete: () -> Unit) {
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
                "allowHistory" to true,
                "deleteDataFromServer" to deleteOnEnd,
                "allowedFileTypes" to mapOf(
                    "pdf" to true, "images" to true, "videos" to true, "audio" to true, "archives" to true, "other" to true, "everything" to false
                )
            ),
            "participantPublicKeys" to mapOf(CryptoManager.BOT_UID to CryptoManager.BOT_PUBLIC_KEY),
            "participantNames" to mapOf(CryptoManager.BOT_UID to "Whisprr Bot")
        )

        metaRef.setValue(metaData)
            .addOnSuccessListener {
                joinRoomAndEnterChat(chatroomId, inviteCode, onComplete)
            }
            .addOnFailureListener { e ->
                onComplete()
                Toast.makeText(this, "Failed to create room: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ======================= JOIN ROOM =======================

    private fun showJoinRoomDialog() {
        val dialogBinding = DialogJoinRoomBinding.inflate(LayoutInflater.from(this))

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()

        // Ensure corners are rounded by making the window background transparent
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

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
        
        val loadingDialog = AlertDialog.Builder(this)
            .setMessage("Searching for room...")
            .setCancelable(false)
            .show()

        database.reference.child("chatrooms")
            .orderByChild("meta/inviteCode").equalTo(code)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    loadingDialog.dismiss()
                    if (!snapshot.exists()) {
                        Toast.makeText(this@MainActivity, "Room not found.", Toast.LENGTH_LONG).show()
                        return
                    }

                    val room = snapshot.children.first()
                    val chatroomId = room.key ?: return
                    val meta = room.child("meta")
                    val roomName = meta.child("roomName").getValue(String::class.java) ?: "Room"
                    val ended = meta.child("ended").getValue(Boolean::class.java) ?: false

                    val dataDeleted = meta.child("dataDeleted").getValue(Boolean::class.java) ?: false
                    if (dataDeleted) {
                        showDeletedChatDataDialog(roomName)
                        return
                    }
                    if (ended) {
                        Toast.makeText(this@MainActivity, "This room has already ended.", Toast.LENGTH_SHORT).show()
                        return
                    }

                    val isBanned = meta.child("permanentBans/$myId").getValue(Boolean::class.java) ?: false
                    if (isBanned) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Permanently Banned")
                            .setMessage("You have been permanently banned from $roomName chat room by the host.")
                            .setPositiveButton("OK", null)
                            .show()
                        return
                    }

                    val wasKicked = meta.child("kickedParticipants/$myId").exists()
                    if (wasKicked) {
                        showKickedRejoinDialog(chatroomId, code, roomName)
                        return
                    }

                    val entryMode = meta.child("settings/entryMode").getValue(String::class.java) ?: "open"
                    if (entryMode == "request") {
                        submitJoinRequest(chatroomId, code, roomName)
                    } else {
                        joinRoomAndEnterChat(chatroomId, code) {}
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    loadingDialog.dismiss()
                }
            })
    }

    private fun showKickedRejoinDialog(chatroomId: String, inviteCode: String, roomName: String) {
        AlertDialog.Builder(this)
            .setTitle("Access Denied")
            .setMessage("You were kicked out from this room. Would you like to request to rejoin?")
            .setPositiveButton("Request to Rejoin") { _, _ ->
                submitJoinRequest(chatroomId, inviteCode, roomName, wasKicked = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                                Toast.makeText(this@MainActivity, "Join request sent. Waiting for approval...", Toast.LENGTH_LONG).show()
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

    // ======================= ENTER CHAT =======================

    private fun joinRoomAndEnterChat(chatroomId: String, inviteCode: String, onComplete: () -> Unit = {}) {
        val myId = auth.currentUser?.uid ?: return

        database.reference.child("chatrooms").child(chatroomId).child("meta")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(metaSnapshot: DataSnapshot) {
                    val roomName = metaSnapshot.child("roomName").getValue(String::class.java) ?: "Room"
                    val isBanned = metaSnapshot.child("permanentBans/$myId").getValue(Boolean::class.java) ?: false
                    if (isBanned) {
                        showPermanentlyBannedDialog(roomName)
                        onComplete()
                        return
                    }

                    // Allow kicked participants to enter - they'll be detected and kicked out in ChatActivity
                    // This enables the flow: enter chat -> see kick popup -> rejoin dialog

                    database.reference.child("users").child(myId)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(mySnapshot: DataSnapshot) {
                                var myPublicKey = mySnapshot.child("publicKey").getValue(String::class.java)
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
                                            "chatrooms/$chatroomId/meta/participantPublicKeys/$myId" to myPublicKey!!,
                                            "chatrooms/$chatroomId/meta/participantNames/$myId" to myName
                                        )
                                        database.reference.updateChildren(updates)
                                            .addOnSuccessListener {
                                                onComplete()
                                                startChatActivity(chatroomId, inviteCode, false)
                                            }
                                    }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                onComplete()
                            }
                        })
                }

                override fun onCancelled(error: DatabaseError) {
                    onComplete()
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
        updateBadges()
    }

    // ======================= UTILS =======================

    private fun generateInviteCode(): String {
        return java.util.UUID.randomUUID().toString().replace("-", "").uppercase().take(8)
    }

    private fun saveInlineUserName() {
        val newName = binding.tvUserNamePreview.text.toString().trim().ifEmpty { currentUserName }
        if (newName == currentUserName) {
            binding.tvUserNamePreview.setText(currentUserName)
            return
        }
        val myId = auth.currentUser?.uid ?: return
        database.reference.child("users").child(myId).child("name").setValue(newName)
            .addOnSuccessListener {
                currentUserName = newName
                Toast.makeText(this, "Name updated", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                binding.tvUserNamePreview.setText(currentUserName)
                Toast.makeText(this, "Unable to update name", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showDeletedChatDataDialog(roomName: String) {
        AlertDialog.Builder(this)
            .setTitle("Chat Data Deleted")
            .setMessage("The host deleted the chat data from the server for $roomName.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }
}
