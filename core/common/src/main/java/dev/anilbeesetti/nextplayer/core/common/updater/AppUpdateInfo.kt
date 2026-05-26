package dev.anilbeesetti.nextplayer.core.common.updater

data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val buildCommit: String? = null,
    val releaseNotes: String,
    val downloadUrl: String,
    val assetName: String,
)

sealed interface AppUpdateCheckResult {
    data object UpToDate : AppUpdateCheckResult
    data class UpdateAvailable(val info: AppUpdateInfo) : AppUpdateCheckResult
    data class Error(val message: String) : AppUpdateCheckResult
}

sealed interface AppUpdateDownloadResult {
    data class Success(val apkFile: java.io.File) : AppUpdateDownloadResult
    data class Error(val message: String) : AppUpdateDownloadResult
}
