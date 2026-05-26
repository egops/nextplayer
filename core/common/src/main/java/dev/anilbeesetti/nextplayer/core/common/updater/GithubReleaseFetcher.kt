package dev.anilbeesetti.nextplayer.core.common.updater

import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class GithubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val prerelease: Boolean = false,
    val assets: List<GithubAssetDto> = emptyList(),
)

@Serializable
private data class GithubAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

internal class GithubReleaseFetcher(
    private val config: GithubUpdateConfig,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun fetchLatestUpdate(currentVersionCode: Int): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val releases = fetchReleases()
            val update = releases
                .mapNotNull { it.toAppUpdateInfo() }
                .filter { it.versionCode > currentVersionCode }
                .maxByOrNull { it.versionCode }
                ?: return@withContext AppUpdateCheckResult.UpToDate
            AppUpdateCheckResult.UpdateAvailable(update)
        } catch (e: Exception) {
            AppUpdateCheckResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun fetchReleases(): List<GithubReleaseDto> {
        val url = URL("https://api.github.com/repos/${config.owner}/${config.repo}/releases?per_page=20")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NextPlayer-Fork")
        }
        return try {
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = stream.bufferedReader().use { it.readText() }
            if (responseCode !in 200..299) {
                error("GitHub API error ($responseCode): $body")
            }
            json.decodeFromString<List<GithubReleaseDto>>(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun GithubReleaseDto.toAppUpdateInfo(): AppUpdateInfo? {
        val apkAsset = selectApkAsset(assets) ?: return null
        val versionCode = parseVersionCode(body) ?: parseVersionCodeFromTag(tagName) ?: return null
        val versionName = parseVersionName(body) ?: tagName.removePrefix("v").ifBlank { tagName }
        return AppUpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            releaseNotes = body.trim(),
            downloadUrl = apkAsset.browserDownloadUrl,
            assetName = apkAsset.name,
        )
    }

    private fun selectApkAsset(assets: List<GithubAssetDto>): GithubAssetDto? {
        val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null

        val preferredAbi = preferredAbiSuffix()
        return apkAssets.firstOrNull { it.name.contains(preferredAbi, ignoreCase = true) }
            ?: apkAssets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apkAssets.firstOrNull()
    }

    private fun preferredAbiSuffix(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            abi.contains("arm64", ignoreCase = true) -> "arm64-v8a"
            abi.contains("armeabi", ignoreCase = true) -> "armeabi-v7a"
            abi.contains("x86_64", ignoreCase = true) -> "x86_64"
            abi.contains("x86", ignoreCase = true) -> "x86"
            else -> "universal"
        }
    }

    private fun parseVersionCode(body: String): Int? {
        val versionLine = body.lines().firstOrNull { it.contains("Version", ignoreCase = true) } ?: body
        return VERSION_CODE_REGEX.find(versionLine)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseVersionName(body: String): String? {
        val versionLine = body.lines().firstOrNull { it.contains("Version", ignoreCase = true) } ?: body
        return VERSION_NAME_REGEX.find(versionLine)?.groupValues?.getOrNull(1)
    }

    private fun parseVersionCodeFromTag(tagName: String): Int? {
        return tagName.filter { it.isDigit() }.toIntOrNull()?.takeIf { it > 0 }
    }

    companion object {
        private val VERSION_CODE_REGEX = Regex("\\((\\d+)\\)")
        private val VERSION_NAME_REGEX = Regex("`([^`]+)`")
    }
}
