package com.example.whisprr

import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import java.nio.ByteBuffer
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
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
            holder.binding.tvMessageText.isVisible = false
            holder.binding.layoutFile.isVisible = true
            holder.binding.tvFileName.text = message.fileName
            holder.binding.tvFileCategory.text = message.fileCategory.uppercase()
            holder.binding.tvFileIcon.text = getFileIcon(message.fileCategory)

            // Image/GIF Preview only - videos should show file info and open externally
            val isPreviewableImage = message.fileCategory.lowercase() in listOf("images", "gifs")
            if (isPreviewableImage && message.fileData.isNotBlank()) {
                try {
                    val imageBytes = Base64.decode(message.fileData, Base64.DEFAULT)
                    if (message.fileCategory.lowercase() == "gifs" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(ByteBuffer.wrap(imageBytes))
                        val drawable = ImageDecoder.decodeDrawable(source)
                        holder.binding.ivPreview.setImageDrawable(drawable)
                    } else {
                        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        holder.binding.ivPreview.setImageBitmap(bitmap)
                    }
                    holder.binding.cvPreview.isVisible = true
                    holder.binding.layoutFileInfo.isVisible = false
                } catch (e: Exception) {
                    holder.binding.cvPreview.isVisible = false
                    holder.binding.layoutFileInfo.isVisible = true
                }
            } else {
                holder.binding.cvPreview.isVisible = false
                holder.binding.layoutFileInfo.isVisible = true
            }

            holder.binding.layoutFile.setOnClickListener {
                if (message.fileData.isNotBlank()) {
                    saveAndOpenFile(holder.itemView.context, message.fileData, message.fileName, message.fileType)
                }
            }
        } else {
            holder.binding.tvMessageText.isVisible = true
            holder.binding.layoutFile.isVisible = false
            holder.binding.tvMessageText.text = message.text
        }

        val context = holder.itemView.context
        
        if (isSentByMe) {
            holder.binding.tvSenderName.isVisible = false
            val bg = ContextCompat.getDrawable(context, R.drawable.bg_message_sent)
            holder.binding.tvMessageText.background = bg
            holder.binding.layoutFile.background = bg
            holder.binding.tvMessageText.setTextColor(ContextCompat.getColor(context, android.R.color.white))
            
            setAlignment(holder.binding.tvMessageText, Gravity.END, 120, 0)
            setAlignment(holder.binding.layoutFile, Gravity.END, 120, 0)
            setAlignment(holder.binding.tvMessageTime, Gravity.END, 0, 0)
            
            holder.binding.tvMessageText.gravity = Gravity.START 
            holder.binding.tvMessageTime.gravity = Gravity.END
        } else {
            holder.binding.tvSenderName.isVisible = true
            holder.binding.tvSenderName.text = message.senderName
            val bg = ContextCompat.getDrawable(context, R.drawable.bg_message_received)
            holder.binding.tvMessageText.background = bg
            holder.binding.layoutFile.background = bg
            holder.binding.tvMessageText.setTextColor(ContextCompat.getColor(context, android.R.color.white))
            
            setAlignment(holder.binding.tvMessageText, Gravity.START, 0, 120)
            setAlignment(holder.binding.layoutFile, Gravity.START, 0, 120)
            setAlignment(holder.binding.tvMessageTime, Gravity.START, 0, 0)
            setAlignment(holder.binding.tvSenderName, Gravity.START, 0, 0)
            
            holder.binding.tvMessageText.gravity = Gravity.START
            holder.binding.tvMessageTime.gravity = Gravity.START
        }
    }

    private fun setAlignment(view: android.view.View, gravity: Int, marginStart: Int, marginEnd: Int) {
        val params = view.layoutParams as LinearLayout.LayoutParams
        params.gravity = gravity
        params.marginStart = marginStart
        params.marginEnd = marginEnd
        view.layoutParams = params
    }

    override fun getItemCount() = messages.size

    private fun getFileIcon(category: String): String = when (category.lowercase()) {
        "images" -> "🖼️"
        "gifs" -> "🎞️"
        "videos" -> "🎬"
        "audio" -> "🎵"
        "pdf" -> "📄"
        "archives" -> "🗜️"
        else -> "📎"
    }

    private fun saveAndOpenFile(context: android.content.Context, base64Data: String, fileName: String, mimeType: String) {
        try {
            val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
            val outFile = File(sharedDir, fileName)

            // Avoid multiple downloads/decodes if file exists
            if (!outFile.exists()) {
                val fileBytes = Base64.decode(base64Data, Base64.DEFAULT)
                FileOutputStream(outFile).use { it.write(fileBytes) }
                
                // Save to Downloads folder only once
                saveToDownloads(context, fileBytes, fileName, mimeType)
            }

            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToDownloads(context: android.content.Context, fileBytes: ByteArray, fileName: String, mimeType: String) {
        try {
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
                if (!downloadFile.exists()) {
                    FileOutputStream(downloadFile).use { it.write(fileBytes) }
                }
            }
        } catch (e: Exception) {
            // Silently fail downloads save if needed
        }
    }
}
