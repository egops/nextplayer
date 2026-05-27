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
    @SerialName("updated_at") val updatedAt: String = "",
)

internal class GithubReleaseFetcher(
    private val config: GithubUpdateConfig,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun fetchLatestUpdate(
        currentVersionCode: Int,
        currentGitCommit: String = config.buildGitCommit,
        installedPackageName: String = "",
    ): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val releases = fetchReleases()
            val candidates = releases.mapNotNull { it.toAppUpdateInfo(installedPackageName) }
            val update = candidates
                .filter { isNewerThanInstalled(it, currentVersionCode, currentGitCommit) }
                .maxWithOrNull(
                    compareBy<AppUpdateInfo> { it.versionCode }
                        .thenBy { it.buildCommit ?: "" },
                )
                ?: return@withContext AppUpdateCheckResult.UpToDate
            AppUpdateCheckResult.UpdateAvailable(update)
        } catch (e: Exception) {
            AppUpdateCheckResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun isNewerThanInstalled(
        remote: AppUpdateInfo,
        currentVersionCode: Int,
        currentGitCommit: String,
    ): Boolean {
        if (remote.versionCode > currentVersionCode) return true
        if (remote.versionCode < currentVersionCode) return false
        val remoteCommit = remote.buildCommit ?: return false
        return commitsDiffer(remoteCommit, currentGitCommit)
    }

    private fun commitsDiffer(remote: String, local: String): Boolean {
        if (local.isBlank() || local.equals("local", ignoreCase = true)) return true
        val remoteKey = remote.take(COMMIT_PREFIX_LENGTH)
        val localKey = local.take(COMMIT_PREFIX_LENGTH)
        return !remoteKey.equals(localKey, ignoreCase = true) &&
            !remote.startsWith(local, ignoreCase = true) &&
            !local.startsWith(remote, ignoreCase = true)
    }

    private fun fetchReleases(): List<GithubReleaseDto> {
        val tag = config.releaseTag?.takeIf { it.isNotBlank() }
        val url = if (tag != null) {
            URL("https://api.github.com/repos/${config.owner}/${config.repo}/releases/tags/$tag")
        } else {
            URL("https://api.github.com/repos/${config.owner}/${config.repo}/releases?per_page=20")
        }
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
            if (tag != null) {
                listOf(json.decodeFromString<GithubReleaseDto>(body))
            } else {
                json.decodeFromString<List<GithubReleaseDto>>(body)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun GithubReleaseDto.toAppUpdateInfo(installedPackageName: String): AppUpdateInfo? {
        val apkAsset = selectApkAsset(assets, installedPackageName) ?: return null
        val versionName = parseVersionNameFromAssetName(apkAsset.name)
            ?: parseVersionName(body)
            ?: tagName.removePrefix("v").ifBlank { tagName }
        val versionCode = resolveVersionCode(versionName, body, tagName) ?: return null
        return AppUpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            buildCommit = extractCommitFromAssetName(apkAsset.name),
            releaseNotes = body.trim(),
            downloadUrl = apkAsset.browserDownloadUrl,
            assetName = apkAsset.name,
        )
    }

    /**
     * Picks the newest matching APK. A release may accumulate multiple builds under the same tag;
     * version shown to the user must come from the selected asset, not only the release body.
     */
    private fun selectApkAsset(assets: List<GithubAssetDto>, installedPackageName: String): GithubAssetDto? {
        var apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) return null

        val variantMarker = apkVariantMarkerForInstalledPackage(installedPackageName)
        val variantApks = apkAssets.filter { it.name.contains(variantMarker, ignoreCase = true) }
        if (variantApks.isNotEmpty()) {
            apkAssets = variantApks
        }

        val preferredAbi = preferredAbiSuffix()
        val abiCandidates = apkAssets.filter { it.name.contains(preferredAbi, ignoreCase = true) }
            .ifEmpty { apkAssets.filter { it.name.contains("universal", ignoreCase = true) } }
            .ifEmpty { apkAssets }

        return abiCandidates.maxWithOrNull(assetComparator())
    }

    private fun assetComparator(): Comparator<GithubAssetDto> =
        Comparator { a, b ->
            val aVersion = parseVersionNameFromAssetName(a.name)
            val bVersion = parseVersionNameFromAssetName(b.name)
            when {
                aVersion != null && bVersion != null -> {
                    val versionCmp = compareVersionNames(aVersion, bVersion)
                    if (versionCmp != 0) return@Comparator versionCmp
                }
                aVersion != null -> return@Comparator 1
                bVersion != null -> return@Comparator -1
            }
            val updatedCmp = a.updatedAt.compareTo(b.updatedAt)
            if (updatedCmp != 0) return@Comparator updatedCmp
            extractCommitFromAssetName(a.name).orEmpty().compareTo(extractCommitFromAssetName(b.name).orEmpty())
        }

    private fun resolveVersionCode(versionName: String, body: String, tagName: String): Int? {
        val bodyVersionName = parseVersionName(body)
        if (bodyVersionName == versionName) {
            parseVersionCode(body)?.let { return it }
        }
        return parseVersionCodeFromTag(tagName)
    }

    private fun apkVariantMarkerForInstalledPackage(installedPackageName: String): String {
        return when {
            installedPackageName.endsWith(".debug") -> DEBUG_APK_MARKER
            installedPackageName.endsWith(".release") -> RELEASE_APK_MARKER
            config.preferReleaseApk -> RELEASE_APK_MARKER
            else -> DEBUG_APK_MARKER
        }
    }

    private fun extractCommitFromAssetName(assetName: String): String? {
        return ASSET_COMMIT_REGEX.find(assetName)?.groupValues?.getOrNull(1)
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

    private fun parseVersionNameFromAssetName(assetName: String): String? {
        return ASSET_VERSION_REGEX.find(assetName)?.groupValues?.getOrNull(1)
    }

    companion object {
        private const val COMMIT_PREFIX_LENGTH = 12
        private const val RELEASE_APK_MARKER = "-release-"
        private const val DEBUG_APK_MARKER = "-debug-"
        private val VERSION_CODE_REGEX = Regex("\\((\\d+)\\)")
        private val VERSION_NAME_REGEX = Regex("`([^`]+)`")
        /** Matches `nextplayer-v0.20.0-<commit>-release-...apk` */
        private val ASSET_VERSION_REGEX = Regex("""nextplayer-v([\d.]+)-""", RegexOption.IGNORE_CASE)
        /** Matches commit in `nextplayer-v0.16.3-<commit>-release-...apk` */
        private val ASSET_COMMIT_REGEX = Regex("""-v[\d.]+-([0-9a-f]{7,40})-(?:debug|release)-""", RegexOption.IGNORE_CASE)

        /** Compares dotted numeric version segments (e.g. 0.20.0 vs 0.16.3). */
        internal fun compareVersionNames(a: String, b: String): Int {
            val aParts = a.split('.').map { it.toIntOrNull() ?: 0 }
            val bParts = b.split('.').map { it.toIntOrNull() ?: 0 }
            val length = maxOf(aParts.size, bParts.size)
            for (i in 0 until length) {
                val diff = aParts.getOrElse(i) { 0 }.compareTo(bParts.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }
    }
}
