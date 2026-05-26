package dev.anilbeesetti.nextplayer.core.common.updater

/**
 * @param releaseTag When set, only this GitHub release tag is checked (e.g. `build-filmstrip-seekbar-pr`).
 * @param preferReleaseApk Prefer APK assets whose filename contains `-release-` over `-debug-`.
 * @param buildGitCommit Current app build commit; used to detect CI rebuilds with the same [versionCode].
 */
data class GithubUpdateConfig(
    val owner: String,
    val repo: String,
    val releaseTag: String? = null,
    val preferReleaseApk: Boolean = true,
    val buildGitCommit: String = "",
)
