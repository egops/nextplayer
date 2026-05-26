package dev.anilbeesetti.nextplayer.core.common.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.common.di.ApplicationScope
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: GithubUpdateConfig,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val fetcher = GithubReleaseFetcher(config)

    private val _checkState = MutableStateFlow<AppUpdateCheckResult?>(null)
    val checkState: StateFlow<AppUpdateCheckResult?> = _checkState.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    fun checkForUpdate() {
        applicationScope.launch {
            _isChecking.value = true
            _checkState.value = checkForUpdateNow()
            _isChecking.value = false
        }
    }

    suspend fun checkForUpdateNow(): AppUpdateCheckResult {
        val currentVersionCode = currentVersionCode()
        return fetcher.fetchLatestUpdate(
            currentVersionCode = currentVersionCode,
            currentGitCommit = config.buildGitCommit,
            installedPackageName = context.packageName,
        ).also { _checkState.value = it }
    }

    fun downloadAndInstall(update: AppUpdateInfo) {
        applicationScope.launch {
            _isDownloading.value = true
            _downloadProgress.value = 0f
            when (val result = downloadApk(update)) {
                is AppUpdateDownloadResult.Success -> installApk(result.apkFile)
                is AppUpdateDownloadResult.Error -> {
                    _checkState.value = AppUpdateCheckResult.Error(result.message)
                }
            }
            _isDownloading.value = false
            _downloadProgress.value = null
        }
    }

    suspend fun downloadApk(update: AppUpdateInfo): AppUpdateDownloadResult = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val destination = File(updatesDir, update.assetName.ifBlank { "update.apk" })
            val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "NextPlayer-Fork")
            }
            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    return@withContext AppUpdateDownloadResult.Error("Download failed ($responseCode)")
                }
                val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
                connection.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes != null) {
                                _downloadProgress.value = downloaded.toFloat() / totalBytes.toFloat()
                            }
                        }
                    }
                }
                AppUpdateDownloadResult.Success(destination)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            AppUpdateDownloadResult.Error(e.message ?: "Download failed")
        }
    }

    fun installApk(apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun clearCheckState() {
        _checkState.value = null
    }

    private fun currentVersionCode(): Int {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }
    }
}
