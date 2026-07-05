package com.example.whisprr

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.whisprr.databinding.ItemMessageBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private val messages: List<Message>,
    private val currentUserId: String
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    inner class MessageViewHolder(val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val isSentByMe = message.senderId == currentUserId

        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        holder.binding.tvMessageTime.text = sdf.format(Date(message.timestamp))

        if (message.isFile) {
            // File message
            holder.binding.tvMessageText.isVisible = false
            holder.binding.layoutFile.isVisible = true
            holder.binding.tvFileName.text = message.fileName
            holder.binding.tvFileCategory.text = message.fileCategory.uppercase()
            holder.binding.tvFileIcon.text = getFileIcon(message.fileCategory)

            holder.binding.layoutFile.setOnClickListener {
                if (message.fileData.isNotBlank()) {
                    saveAndOpenFile(holder.itemView.context, message.fileData, message.fileName, message.fileType)
                }
            }
        } else {
            // Text message
            holder.binding.tvMessageText.isVisible = true
            holder.binding.layoutFile.isVisible = false
            holder.binding.tvMessageText.text = message.text
        }

        // Reset layout params to avoid recycling artifacts
        (holder.binding.tvMessageText.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = 0
            it.marginEnd = 0
        }
        (holder.binding.layoutFile.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = 0
            it.marginEnd = 0
        }

        if (isSentByMe) {
            holder.binding.tvSenderName.isVisible = false
            val bg = ContextCompat.getDrawable(holder.itemView.context, R.drawable.bg_message_sent)
            holder.binding.tvMessageText.background = bg
            holder.binding.layoutFile.background = bg
            // White text on blue sent bubble
            holder.binding.tvMessageText.setTextColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.white)
            )
            holder.binding.tvMessageText.gravity = Gravity.END
            holder.binding.tvMessageTime.gravity = Gravity.END
            (holder.binding.tvMessageText.layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginStart = 80
                marginEnd = 0
            }
            (holder.binding.layoutFile.layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginStart = 80
                marginEnd = 0
            }
        } else {
            holder.binding.tvSenderName.isVisible = true
            holder.binding.tvSenderName.text = message.senderName
            val bg = ContextCompat.getDrawable(holder.itemView.context, R.drawable.bg_message_received)
            holder.binding.tvMessageText.background = bg
            holder.binding.layoutFile.background = bg
            // White text on dark received bubble (dark bg in both light/dark mode)
            holder.binding.tvMessageText.setTextColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.white)
            )
            holder.binding.tvMessageText.gravity = Gravity.START
            holder.binding.tvMessageTime.gravity = Gravity.START
            (holder.binding.tvMessageText.layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginStart = 0
                marginEnd = 80
            }
            (holder.binding.layoutFile.layoutParams as ViewGroup.MarginLayoutParams).apply {
                marginStart = 0
                marginEnd = 80
            }
        }
    }

    override fun getItemCount() = messages.size

    private fun getFileIcon(category: String): String = when (category.lowercase()) {
        "images" -> "🖼️"
        "videos" -> "🎬"
        "audio" -> "🎵"
        "pdf" -> "📄"
        "archives" -> "🗜️"
        else -> "📎"
    }

    private fun saveAndOpenFile(context: android.content.Context, base64Data: String, fileName: String, mimeType: String) {
        try {
            val fileBytes = Base64.decode(base64Data, Base64.DEFAULT)

            // Save to app cache directory for sharing
            val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
            val outFile = File(sharedDir, fileName)
            FileOutputStream(outFile).use { it.write(fileBytes) }

            // Also save to Downloads for user access
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { os -> os.write(fileBytes) }
                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val downloadFile = File(downloadsDir, fileName)
                FileOutputStream(downloadFile).use { it.write(fileBytes) }
            }

            Toast.makeText(context, "File saved to Downloads", Toast.LENGTH_SHORT).show()

            // Open file via FileProvider
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to save/open file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

