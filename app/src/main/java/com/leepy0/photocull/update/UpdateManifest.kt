package com.leepy0.photocull.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 릴리스 자산 PhotoCull.json. CI(.github/workflows/android.yml → scripts/release_manifest.py)가 만든다.
 * 모르는 필드는 무시하고, 빠진 필드는 null로 받아 [UpdateManifests.toInfo]에서 보완한다.
 */
@Serializable
data class UpdateManifest(
    val version: String? = null,
    val versionCode: Int? = null,
    val file: String? = null,
    val url: String? = null,
    val sha256: String? = null,
    val size: Long? = null,
    val published: String? = null,
    val page: String? = null,
    val notes: String? = null,
)

/** api.github.com/repos/{repo}/releases/latest 응답 중 필요한 부분 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String? = null,
    val size: Long? = null,
)

object UpdateManifests {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 형식이 깨졌으면 null */
    fun parse(text: String): UpdateManifest? =
        runCatching { json.decodeFromString(UpdateManifest.serializer(), text) }.getOrNull()

    fun parseRelease(text: String): GitHubRelease? =
        runCatching { json.decodeFromString(GitHubRelease.serializer(), text) }.getOrNull()

    /**
     * 매니페스트 → [UpdateInfo]. 버전을 알 수 없으면 null.
     * url이 없으면 태그 규칙(v<version>)으로 만들고, sha256은 형식이 맞을 때만 쓴다.
     */
    fun toInfo(m: UpdateManifest, repo: String, defaultAsset: String): UpdateInfo? {
        val name = m.version?.let(Versions::normalize)?.takeIf { it.isNotEmpty() }
        val code = m.versionCode?.takeIf { it > 0 } ?: name?.let(Versions::codeOf) ?: return null
        val versionName = name ?: Versions.nameOf(code)
        val asset = m.file?.trim()?.takeIf { it.isNotEmpty() && !it.contains('/') } ?: defaultAsset
        val url = m.url?.trim()?.takeIf { it.startsWith("https://") }
            ?: "https://github.com/$repo/releases/download/v$versionName/$asset"
        return UpdateInfo(
            versionName = versionName,
            versionCode = code,
            apkUrl = url,
            sha256 = normalizeSha256(m.sha256),
            sizeBytes = m.size?.takeIf { it > 0 },
            notes = m.notes?.trim().orEmpty(),
            pageUrl = m.page?.trim()?.takeIf { it.startsWith("https://") }
                ?: "https://github.com/$repo/releases/tag/v$versionName",
            publishedAt = m.published?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * "<hex>" 또는 sha256sum 형식("<hex>  PhotoCull.apk")에서 64자리 hex를 소문자로 꺼낸다. 형식이 틀리면 null.
     */
    fun normalizeSha256(raw: String?): String? {
        val token = raw?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.removePrefix("sha256:")?.lowercase() ?: return null
        return token.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }
}

/**
 * 버전 문자열 ↔ versionCode. app/build.gradle.kts와 같은 규칙: major*10000 + minor*100 + patch (minor, patch < 100).
 */
object Versions {
    /** "v0.2.0" → "0.2.0", " 1.2.3-beta " → "1.2.3-beta" */
    fun normalize(raw: String): String = raw.trim().removePrefix("v").removePrefix("V").trim()

    /** "0.2.0" → 200, "v1.2.3" → 10203, "1.2" → 10200. 해석할 수 없으면 null */
    fun codeOf(version: String): Int? {
        val core = normalize(version).substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val nums = parts.map { p -> p.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
        val major = nums[0]
        val minor = nums.getOrElse(1) { 0 }
        val patch = nums.getOrElse(2) { 0 }
        if (minor >= 100 || patch >= 100 || major > 20_000) return null
        val code = major * 10_000 + minor * 100 + patch
        return code.takeIf { it > 0 }
    }

    /** 200 → "0.2.0" */
    fun nameOf(code: Int): String = "${code / 10_000}.${(code / 100) % 100}.${code % 100}"

    fun isNewer(remoteCode: Int, currentCode: Int): Boolean = remoteCode > currentCode
}
