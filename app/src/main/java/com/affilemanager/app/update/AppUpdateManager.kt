package com.affilemanager.app.update

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.affilemanager.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AppUpdateManager(
    private val application: Application,
    private val repository: String = BuildConfig.UPDATE_REPOSITORY,
    private val client: OkHttpClient = defaultClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = application.getSharedPreferences("af-file-manager-updates", Application.MODE_PRIVATE)
    private val _state = MutableStateFlow<AppUpdateState>(restoredReadyState() ?: AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()
    private var activeJob: Job? = null
    private var readyFile: File? = restoredReadyFile()

    fun check(automatic: Boolean = false) {
        if (activeJob?.isActive == true) return
        if (automatic && System.currentTimeMillis() - preferences.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MILLIS) return
        activeJob = scope.launch {
            _state.value = AppUpdateState.Checking
            runCatching { fetchLatestRelease() }
                .onSuccess { release ->
                    preferences.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                    if (!UpdateVersionRules.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                        clearReady()
                        _state.value = AppUpdateState.UpToDate(UpdateVersionRules.normalized(BuildConfig.VERSION_NAME))
                    } else if (automatic && !BuildConfig.DEBUG && isUnmeteredNetwork()) {
                        downloadInternal(release)
                    } else {
                        _state.value = AppUpdateState.Available(release)
                    }
                }
                .onFailure { error ->
                    _state.value = AppUpdateState.Failed(humanCheckError(error))
                }
        }
    }

    fun download(release: AppRelease) {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch { downloadInternal(release) }
    }

    fun installReady() {
        val release = when (val current = _state.value) {
            is AppUpdateState.Ready -> current.release
            is AppUpdateState.Failed -> current.release
            else -> null
        } ?: return
        val apk = readyFile?.takeIf(File::isFile) ?: run {
            _state.value = AppUpdateState.Failed("Atsisiųstas APK neberastas. Atsisiųskite jį dar kartą.", release)
            return
        }
        scope.launch {
            val verification = runCatching { verifyApk(apk) }
            if (verification.isFailure) {
                _state.value = AppUpdateState.Failed("APK saugos patikra nepavyko. Failas nebus diegiamas.", release)
                return@launch
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !application.packageManager.canRequestPackageInstalls()) {
                _state.value = AppUpdateState.Ready(release, installPermissionRequired = true)
                application.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${application.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return@launch
            }
            val uri = FileProvider.getUriForFile(application, "${application.packageName}.files", apk)
            application.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setData(uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    }

    private suspend fun fetchLatestRelease(): AppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .header("User-Agent", "AF-File-Manager/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "GitHub atsakė HTTP ${response.code}" }
            val body = requireNotNull(response.body).string()
            GitHubReleaseParser.parse(body, repository)
        }
    }

    private suspend fun downloadInternal(release: AppRelease) = withContext(Dispatchers.IO) {
        _state.value = AppUpdateState.Downloading(release, 0L)
        val directory = File(application.filesDir, "updates").apply { mkdirs() }
        require(directory.isDirectory) { "Nepavyko paruošti atnaujinimų katalogo" }
        val target = File(directory, release.asset.name)
        val partial = File(directory, "${release.asset.name}.part")
        runCatching {
            val request = Request.Builder()
                .url(release.asset.downloadUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "AF-File-Manager/${BuildConfig.VERSION_NAME}")
                .build()
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "APK serveris atsakė HTTP ${response.code}" }
                require(isAllowedDownloadHost(response.request.url.scheme, response.request.url.host)) { "APK nukreiptas į neleistiną serverį" }
                val body = requireNotNull(response.body)
                val declaredLength = body.contentLength()
                require(declaredLength <= 0L || declaredLength == release.asset.sizeBytes) { "APK dydis neatitinka GitHub metaduomenų" }
                body.byteStream().use { input ->
                    FileOutputStream(partial, false).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= release.asset.sizeBytes) { "APK viršijo paskelbtą dydį" }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            if (total == release.asset.sizeBytes || total % (256L * 1024L) < count) {
                                _state.value = AppUpdateState.Downloading(release, total)
                            }
                        }
                        output.fd.sync()
                        require(total == release.asset.sizeBytes) { "Atsiųstas nepilnas APK" }
                    }
                }
            }
            require(digest.digest().toHex() == release.asset.sha256) { "APK SHA-256 neatitinka GitHub leidimo" }
            moveAtomically(partial, target)
            verifyApk(target)
            persistReady(release, target)
            readyFile = target
            _state.value = AppUpdateState.Ready(release)
        }.onFailure {
            partial.delete()
            _state.value = AppUpdateState.Failed("Atnaujinimo atsisiųsti arba patikrinti nepavyko.", release)
        }
    }

    private fun verifyApk(apk: File) {
        val manager = application.packageManager
        val candidate = packageArchiveInfo(manager, apk)
            ?: throw IllegalArgumentException("APK metaduomenų perskaityti nepavyko")
        val installed = packageInfo(manager, application.packageName)
        require(candidate.packageName == application.packageName) { "APK paketas skiriasi" }
        require(candidate.longVersionCodeCompat() > installed.longVersionCodeCompat()) { "APK versija nėra naujesnė" }
        val candidateSigners = signers(candidate)
        require(candidateSigners == signers(installed) && candidateSigners.isNotEmpty()) { "APK pasirašymo sertifikatas skiriasi" }
    }

    private fun isUnmeteredNetwork(): Boolean {
        val connectivity = application.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun restoredReadyState(): AppUpdateState.Ready? {
        val file = restoredReadyFile() ?: return null
        val tag = preferences.getString(KEY_READY_TAG, null) ?: return null
        val version = preferences.getString(KEY_READY_VERSION, null) ?: return null
        if (!runCatching { UpdateVersionRules.isNewer(version, BuildConfig.VERSION_NAME) }.getOrDefault(false)) return null
        val release = AppRelease(
            tag = tag,
            version = version,
            notes = preferences.getString(KEY_READY_NOTES, "").orEmpty(),
            pageUrl = preferences.getString(KEY_READY_PAGE, "").orEmpty(),
            asset = AppReleaseAsset(
                name = file.name,
                downloadUrl = preferences.getString(KEY_READY_DOWNLOAD, "").orEmpty(),
                sizeBytes = file.length(),
                sha256 = preferences.getString(KEY_READY_SHA, "").orEmpty(),
            ),
        )
        return AppUpdateState.Ready(release)
    }

    private fun restoredReadyFile(): File? = preferences.getString(KEY_READY_PATH, null)
        ?.let(::File)
        ?.takeIf { it.isFile && it.toPath().normalize().startsWith(File(application.filesDir, "updates").toPath().normalize()) }

    private fun persistReady(release: AppRelease, apk: File) {
        preferences.edit()
            .putString(KEY_READY_TAG, release.tag)
            .putString(KEY_READY_VERSION, release.version)
            .putString(KEY_READY_NOTES, release.notes)
            .putString(KEY_READY_PAGE, release.pageUrl)
            .putString(KEY_READY_DOWNLOAD, release.asset.downloadUrl)
            .putString(KEY_READY_SHA, release.asset.sha256)
            .putString(KEY_READY_PATH, apk.absolutePath)
            .apply()
    }

    private fun clearReady() {
        readyFile?.delete()
        readyFile = null
        preferences.edit()
            .remove(KEY_READY_TAG)
            .remove(KEY_READY_VERSION)
            .remove(KEY_READY_NOTES)
            .remove(KEY_READY_PAGE)
            .remove(KEY_READY_DOWNLOAD)
            .remove(KEY_READY_SHA)
            .remove(KEY_READY_PATH)
            .apply()
    }

    private fun humanCheckError(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "Nepavyko pasiekti GitHub. Patikrinkite interneto ryšį."
        is java.net.SocketTimeoutException -> "GitHub neatsakė laiku. Bandykite dar kartą."
        else -> "Atnaujinimų patikrinti nepavyko."
    }

    companion object {
        private const val CHECK_INTERVAL_MILLIS = 6L * 60L * 60L * 1_000L
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_READY_TAG = "ready_tag"
        private const val KEY_READY_VERSION = "ready_version"
        private const val KEY_READY_NOTES = "ready_notes"
        private const val KEY_READY_PAGE = "ready_page"
        private const val KEY_READY_DOWNLOAD = "ready_download"
        private const val KEY_READY_SHA = "ready_sha"
        private const val KEY_READY_PATH = "ready_path"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()

        private fun isAllowedDownloadHost(scheme: String, host: String): Boolean = scheme == "https" && (
            host.equals("github.com", ignoreCase = true) ||
                host.endsWith(".githubusercontent.com", ignoreCase = true)
            )

        private fun moveAtomically(source: File, destination: File) {
            try {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun packageArchiveInfo(manager: PackageManager, apk: File): PackageInfo? = when {
            Build.VERSION.SDK_INT >= 33 -> manager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
            Build.VERSION.SDK_INT >= 28 -> {
                @Suppress("DEPRECATION")
                manager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            else -> {
                @Suppress("DEPRECATION")
                manager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            }
        }

        private fun packageInfo(manager: PackageManager, packageName: String): PackageInfo = when {
            Build.VERSION.SDK_INT >= 33 -> manager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
            Build.VERSION.SDK_INT >= 28 -> {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            else -> {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
        }

        private fun PackageInfo.longVersionCodeCompat(): Long = if (Build.VERSION.SDK_INT >= 28) longVersionCode else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

        private fun signers(info: PackageInfo): Set<String> = if (Build.VERSION.SDK_INT >= 28) {
            val signing = requireNotNull(info.signingInfo)
            val certificates = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
            certificates.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet()
        }

        private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
    }
}
