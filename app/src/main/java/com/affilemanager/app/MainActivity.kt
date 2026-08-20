package com.affilemanager.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.affilemanager.app.ui.AFFileManagerApp
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import com.affilemanager.app.ui.localization.AppLanguageManager
import java.io.File

data class IncomingViewRequest(val uri: Uri, val mimeType: String?)

class MainActivity : AppCompatActivity() {
    private val pendingViewRequest = mutableStateOf<IncomingViewRequest?>(null)
    private val pendingBenchmarkRequest = mutableStateOf<BenchmarkRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.ensureEnglishDefault(this)
        super.onCreate(savedInstanceState)
        pendingViewRequest.value = intent.toIncomingViewRequest()
        pendingBenchmarkRequest.value = intent.toBenchmarkRequest()
        enableEdgeToEdge()
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val appearance = mainViewModel.appearanceSettings.collectAsStateWithLifecycle()
            val leftPanel = mainViewModel.leftPanel.collectAsStateWithLifecycle()
            val network = mainViewModel.networkState.collectAsStateWithLifecycle()
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
                AFFileManagerApp(
                    viewModel = mainViewModel,
                    incomingViewRequest = pendingViewRequest.value,
                    onIncomingViewRequestConsumed = { pendingViewRequest.value = null },
                )
            }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingViewRequest.value = intent.toIncomingViewRequest()
        pendingBenchmarkRequest.value = intent.toBenchmarkRequest()
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
    }
}
