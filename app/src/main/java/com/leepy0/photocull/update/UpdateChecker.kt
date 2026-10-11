package com.leepy0.photocull.update

import java.io.IOException

/** 확인 실패 이유. 화면 문구는 컨트롤러가 정한다(예외 원문은 노출하지 않음). */
enum class CheckFailure {
    /** 연결 실패·시간 초과 */
    NETWORK,
    /** api.github.com 요청 한도 초과(403/429) */
    RATE_LIMITED,
    /** 5xx 등 서버 응답 이상 */
    SERVER,
    /** 매니페스트·릴리스 형식을 해석할 수 없음 */
    INVALID_RELEASE,
}

sealed interface CheckResult {
    data class Available(val info: UpdateInfo) : CheckResult
    /** [latestVersionName] = 최신 릴리스 버전. 릴리스가 아직 없으면 null */
    data class UpToDate(val latestVersionName: String?) : CheckResult
    data class Failed(val reason: CheckFailure) : CheckResult
}

/**
 * GitHub Releases에서 최신 버전을 찾는다(블로킹, IO 스레드에서 호출).
 * ① github.com/<repo>/releases/latest/download/PhotoCull.json
 * ② 실패하거나 404면 api.github.com/repos/<repo>/releases/latest의 자산 목록(PhotoCull.json, 없으면 PhotoCull.apk + .sha256)
 * 둘 다 404 = 릴리스 없음 = 최신.
 */
class UpdateChecker(
    private val http: UpdateHttp,
    private val repo: String,
    private val manifestName: String,
    private val apkName: String,
) {
    val manifestUrl: String get() = "https://github.com/$repo/releases/latest/download/$manifestName"
    val apiUrl: String get() = "https://api.github.com/repos/$repo/releases/latest"

    sealed interface Latest {
        data class Found(val info: UpdateInfo) : Latest
        data object NoRelease : Latest
        /** 릴리스는 있지만 설치할 APK가 없음(자산 업로드 중 등) */
        data class NotInstallable(val versionName: String?) : Latest
        data class Error(val reason: CheckFailure) : Latest
    }

    fun check(currentVersionCode: Int): CheckResult = when (val latest = latest()) {
        is Latest.Found ->
            if (Versions.isNewer(latest.info.versionCode, currentVersionCode)) CheckResult.Available(latest.info)
            else CheckResult.UpToDate(latest.info.versionName)
        Latest.NoRelease -> CheckResult.UpToDate(null)
        is Latest.NotInstallable -> CheckResult.UpToDate(latest.versionName)
        is Latest.Error -> CheckResult.Failed(latest.reason)
    }

    fun latest(): Latest {
        var manifestFailure: CheckFailure? = null
        try {
            val r = http.getText(manifestUrl)
            when {
                r.code in 200..299 -> {
                    val info = UpdateManifests.parse(r.body)?.let { UpdateManifests.toInfo(it, repo, apkName) }
                    if (info != null) return Latest.Found(withShaFallback(info))
                    manifestFailure = CheckFailure.INVALID_RELEASE
                }
                r.code == 404 -> Unit
                r.code == 403 || r.code == 429 -> manifestFailure = CheckFailure.RATE_LIMITED
                else -> manifestFailure = CheckFailure.SERVER
            }
        } catch (e: IOException) {
            manifestFailure = CheckFailure.NETWORK
        }

        return try {
            val r = http.getText(apiUrl, API_HEADERS)
            when {
                r.code in 200..299 -> {
                    val release = UpdateManifests.parseRelease(r.body)
                        ?: return Latest.Error(manifestFailure ?: CheckFailure.INVALID_RELEASE)
                    fromRelease(release)
                }
                // API가 404면 정식 릴리스가 하나도 없다.
                r.code == 404 -> Latest.NoRelease
                r.code == 403 || r.code == 429 -> Latest.Error(CheckFailure.RATE_LIMITED)
                else -> Latest.Error(manifestFailure ?: CheckFailure.SERVER)
            }
        } catch (e: IOException) {
            Latest.Error(if (manifestFailure == CheckFailure.INVALID_RELEASE) manifestFailure else CheckFailure.NETWORK)
        }
    }

    private fun fromRelease(release: GitHubRelease): Latest {
        if (release.draft) return Latest.NoRelease
        val tagVersion = (release.tagName ?: release.name)?.let(Versions::normalize)?.takeIf { it.isNotEmpty() }

        release.assets.firstOrNull { it.name == manifestName }?.downloadUrl?.let { url ->
            val info = runCatching { http.getText(url) }.getOrNull()
                ?.takeIf { it.code in 200..299 }
                ?.let { UpdateManifests.parse(it.body) }
                ?.let { UpdateManifests.toInfo(it, repo, apkName) }
            if (info != null) {
                return Latest.Found(
                    withShaFallback(
                        info.copy(
                            notes = info.notes.ifBlank { release.body?.trim().orEmpty() },
                            publishedAt = info.publishedAt ?: release.publishedAt,
                        ),
                    ),
                )
            }
        }

        val apk = release.assets.firstOrNull { it.name == apkName }
        val apkUrl = apk?.downloadUrl?.takeIf { it.startsWith("https://") } ?: return Latest.NotInstallable(tagVersion)
        val code = tagVersion?.let(Versions::codeOf) ?: return Latest.Error(CheckFailure.INVALID_RELEASE)
        val versionName = Versions.normalize(tagVersion)
        val sha = release.assets.firstOrNull { it.name == "$apkName.sha256" }?.downloadUrl?.let(::fetchSha)
        return Latest.Found(
            UpdateInfo(
                versionName = versionName,
                versionCode = code,
                apkUrl = apkUrl,
                sha256 = sha,
                sizeBytes = apk.size?.takeIf { it > 0 },
                notes = release.body?.trim().orEmpty(),
                pageUrl = release.htmlUrl?.takeIf { it.startsWith("https://") }
                    ?: "https://github.com/$repo/releases/tag/v$versionName",
                publishedAt = release.publishedAt,
            ),
        )
    }

    /** 매니페스트에 sha256이 없으면 같은 위치의 .sha256 자산을 시도한다. */
    private fun withShaFallback(info: UpdateInfo): UpdateInfo =
        if (info.sha256 != null) info else info.copy(sha256 = fetchSha("${info.apkUrl}.sha256"))

    private fun fetchSha(url: String): String? = runCatching { http.getText(url, maxBytes = 4096) }.getOrNull()
        ?.takeIf { it.code in 200..299 }
        ?.let { UpdateManifests.normalizeSha256(it.body) }

    private companion object {
        val API_HEADERS = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
        )
    }
}
