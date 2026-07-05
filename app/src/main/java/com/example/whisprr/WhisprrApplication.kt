package com.example.whisprr

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

class WhisprrApplication : Application() {
    companion object {
        const val DATABASE_URL = "https://whisprr-1596d-default-rtdb.firebaseio.com"
    }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        AppCompatDelegate.setDefaultNightMode(
            if (getSharedPreferences("whisprr_prefs", MODE_PRIVATE).getBoolean("dark_mode", false))
                AppCompatDelegate.MODE_NIGHT_YES
            else
                AppCompatDelegate.MODE_NIGHT_NO
        )
        FirebaseDatabase.getInstance(DATABASE_URL).setPersistenceEnabled(true)
    }
}
