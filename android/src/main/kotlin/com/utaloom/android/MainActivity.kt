package com.utaloom.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import com.utaloom.data.Stores
import com.utaloom.playback.Player
import com.utaloom.ui.UtaloomApp

class MainActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PlaylistDocuments.attach(this)
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                UtaloomApp(backHandler = { enabled, onBack -> BackHandler(enabled, onBack) })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Player.init()
        startService(Intent(this, PlaybackService::class.java))
    }

    override fun onStop() {
        Stores.flushAll()
        super.onStop()
    }
}
