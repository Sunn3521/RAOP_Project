package com.example.whisprr

data class Message(
    val senderId: String = "",
    val senderName: String = "",
    val text: String = "",
    val timestamp: Long = 0L,
    val isFile: Boolean = false,
    val fileName: String = "",
    val fileType: String = "",
    val fileData: String = "",
    val fileCategory: String = ""
)

