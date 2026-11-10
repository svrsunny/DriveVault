package com.drivevault.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.drivevault.app.ui.DriveVaultApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // keep vault contents out of screenshots and the recent-apps preview
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        handleShare(intent)
        setContent { DriveVaultApp(this) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    @Suppress("DEPRECATION")
    private fun handleShare(i: Intent?) {
        i ?: return
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) { Session.pendingShares.clear(); Session.pendingShares.addAll(uris) }
    }
}
