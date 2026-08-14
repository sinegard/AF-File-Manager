package com.affilemanager.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import com.affilemanager.app.ui.AFFileManagerApp
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import com.affilemanager.app.ui.localization.AppLanguageManager

data class IncomingViewRequest(val uri: Uri, val mimeType: String?)

class MainActivity : AppCompatActivity() {
    private val pendingViewRequest = mutableStateOf<IncomingViewRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.ensureEnglishDefault(this)
        super.onCreate(savedInstanceState)
        pendingViewRequest.value = intent.toIncomingViewRequest()
        enableEdgeToEdge()
        setContent {
            AFFileManagerTheme {
                AFFileManagerApp(
                    incomingViewRequest = pendingViewRequest.value,
                    onIncomingViewRequestConsumed = { pendingViewRequest.value = null },
                )
            }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingViewRequest.value = intent.toIncomingViewRequest()
    }

    private fun Intent.toIncomingViewRequest(): IncomingViewRequest? {
        if (action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_EDIT)) return null
        val viewUri = data ?: return null
        if (viewUri.scheme !in setOf("content", "file")) return null
        return IncomingViewRequest(viewUri, type)
    }
}
