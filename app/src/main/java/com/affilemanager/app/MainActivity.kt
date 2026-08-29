package com.affilemanager.app

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.affilemanager.app.ui.AFFileManagerApp
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.ui.components.LocalStorageRoots
import kotlinx.coroutines.delay
import java.io.File

data class IncomingViewRequest(val uri: Uri, val mimeType: String?)

class MainActivity : AppCompatActivity() {
    private val pendingViewRequest = mutableStateOf<IncomingViewRequest?>(null)
    private val pendingBenchmarkRequest = mutableStateOf<BenchmarkRequest?>(null)
    private val pendingStorageRefresh = mutableStateOf(false)
    private var storageReceiversRegistered = false
    private val usbStorageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pendingStorageRefresh.value = true
        }
    }
    private val mediaStorageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pendingStorageRefresh.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.ensureEnglishDefault(this)
        super.onCreate(savedInstanceState)
        pendingViewRequest.value = intent.toIncomingViewRequest()
        pendingBenchmarkRequest.value = intent.toBenchmarkRequest()
        pendingStorageRefresh.value = intent.action in storageChangeActions
        enableEdgeToEdge()
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val appearance = mainViewModel.appearanceSettings.collectAsStateWithLifecycle()
            val leftPanel = mainViewModel.leftPanel.collectAsStateWithLifecycle()
            val network = mainViewModel.networkState.collectAsStateWithLifecycle()
            val storageRoots = mainViewModel.roots.collectAsStateWithLifecycle()
            LaunchedEffect(pendingStorageRefresh.value) {
                if (pendingStorageRefresh.value) {
                    mainViewModel.refreshStorageRoots()
                    delay(750)
                    mainViewModel.refreshStorageRoots()
                    pendingStorageRefresh.value = false
                }
            }
            LaunchedEffect(pendingBenchmarkRequest.value) {
                pendingBenchmarkRequest.value?.let { request ->
                    when (request) {
                        is BenchmarkRequest.LocalDataset -> {
                            val benchmarkRoot = requireNotNull(getExternalFilesDir(null)).canonicalFile
                            val target = File(benchmarkRoot, "benchmark/${request.name}").canonicalFile
                            require(target.toPath().startsWith(benchmarkRoot.toPath()))
                            mainViewModel.setSection(AppSection.FILES)
                            mainViewModel.navigate(PanelId.LEFT, target.path, rememberHistory = false)
                        }
                        is BenchmarkRequest.RemoteProfile -> {
                            mainViewModel.setSection(AppSection.CONNECTIONS)
                            mainViewModel.connectSavedNetworkProfile(request.id)
                        }
                    }
                    pendingBenchmarkRequest.value = null
                }
            }
            LaunchedEffect(leftPanel.value.path, leftPanel.value.loading, leftPanel.value.entries.size, network.value.loading, network.value.entries.size) {
                val localReady = leftPanel.value.entries.isNotEmpty()
                val remoteReady = network.value.connectedProfile != null && network.value.entries.isNotEmpty()
                if (localReady || remoteReady) reportFullyDrawn()
            }
            AFFileManagerTheme(settings = appearance.value) {
                CompositionLocalProvider(LocalStorageRoots provides storageRoots.value) {
                    AFFileManagerApp(
                        viewModel = mainViewModel,
                        incomingViewRequest = pendingViewRequest.value,
                        onIncomingViewRequestConsumed = { pendingViewRequest.value = null },
                    )
                }
            }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingViewRequest.value = intent.toIncomingViewRequest()
        pendingBenchmarkRequest.value = intent.toBenchmarkRequest()
        pendingStorageRefresh.value = intent.action in storageChangeActions
    }

    override fun onStart() {
        super.onStart()
        if (storageReceiversRegistered) return
        ContextCompat.registerReceiver(
            this,
            usbStorageReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            mediaStorageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        storageReceiversRegistered = true
    }

    override fun onStop() {
        if (storageReceiversRegistered) {
            unregisterReceiver(usbStorageReceiver)
            unregisterReceiver(mediaStorageReceiver)
            storageReceiversRegistered = false
        }
        super.onStop()
    }

    private fun Intent.toIncomingViewRequest(): IncomingViewRequest? {
        if (action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_EDIT)) return null
        val viewUri = data ?: return null
        if (viewUri.scheme !in setOf("content", "file")) return null
        return IncomingViewRequest(viewUri, type)
    }

    private fun Intent.toBenchmarkRequest(): BenchmarkRequest? {
        if (BuildConfig.BUILD_TYPE !in BENCHMARK_BUILD_TYPES) return null
        getStringExtra(EXTRA_BENCHMARK_DATASET)?.takeIf { it in BENCHMARK_DATASETS }?.let {
            return BenchmarkRequest.LocalDataset(it)
        }
        getStringExtra(EXTRA_BENCHMARK_REMOTE_PROFILE)?.takeIf(String::isNotBlank)?.let {
            return BenchmarkRequest.RemoteProfile(it)
        }
        return null
    }

    private sealed interface BenchmarkRequest {
        data class LocalDataset(val name: String) : BenchmarkRequest
        data class RemoteProfile(val id: String) : BenchmarkRequest
    }

    companion object {
        const val EXTRA_BENCHMARK_DATASET = "af_benchmark_dataset"
        const val EXTRA_BENCHMARK_REMOTE_PROFILE = "af_benchmark_remote_profile"
        private val BENCHMARK_BUILD_TYPES = setOf("benchmark", "profile")
        private val BENCHMARK_DATASETS = setOf("large", "thumbnails")
        private val storageChangeActions = setOf(
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            UsbManager.ACTION_USB_DEVICE_DETACHED,
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
        )
    }
}
