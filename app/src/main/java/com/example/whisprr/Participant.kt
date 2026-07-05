package com.example.whisprr

data class Participant(
    val uid: String = "",
    val name: String = "",
    val isHost: Boolean = false,
    val isMuted: Boolean = false
)

