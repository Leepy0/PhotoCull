package com.leepy0.photocull.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

sealed interface DownloadResult {
    data class Success(val apk: File) : DownloadResult
    /** 받은 파일의 SHA-256이 매니페스트와 다름(파일은 지움) */
    data object ChecksumMismatch : DownloadResult
    /** 릴리스에 SHA-256이 없어 검증할 수 없음(받지 않음) */
    data object MissingChecksum : DownloadResult
    data class NotEnoughSpace(val requiredBytes: Long, val availableBytes: Long) : DownloadResult
    /** 서버가 2xx가 아닌 응답(404 등) */
    data class HttpError(val code: Int) : DownloadResult
    /** 연결 끊김·시간 초과·쓰기 실패 */
    data object NetworkError : DownloadResult
}

/**
 * 업데이트 APK를 [dir](cacheDir/update)에 받는다.
 * `PhotoCull-<ver>.apk.partial`로 받으면서 SHA-256을 계산하고, 일치할 때만 `PhotoCull-<ver>.apk`로 이름을 바꾼다.
 * 불일치·중단·취소 시 .partial을 지운다.
 */
class ApkDownloader(
    private val http: UpdateHttp,
    private val dir: File,
    private val availableBytes: (File) -> Long = { it.usableSpace },
) {
    fun apkFile(info: UpdateInfo): File = File(dir, "$PREFIX${safeVersion(info.versionName)}$APK")
    fun partialFile(info: UpdateInfo): File = File(dir, "$PREFIX${safeVersion(info.versionName)}$PARTIAL")

    /**
     * 받아서 검증까지 한다. [onProgress]: 0..1, 전체 크기를 모르면 -1.
     * 취소되면 .partial을 지우고 CancellationException을 다시 던진다.
     */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): DownloadResult {
        val expected = info.sha256?.let { UpdateManifests.normalizeSha256(it) } ?: return DownloadResult.MissingChecksum

        // 이미 받아 검증한 파일이 있으면 다시 받지 않는다(앱 재시작 후 등).
        verifiedApk(info)?.let { return DownloadResult.Success(it) }

        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) return DownloadResult.NetworkError
        info.sizeBytes?.let { size ->
            val required = size + SPACE_MARGIN
            val available = availableBytes(dir)
            if (available in 0 until required) return DownloadResult.NotEnoughSpace(required, available)
        }

        val partial = partialFile(info)
        val target = apkFile(info)
        partial.delete()
        try {
            val actual = http.openStream(info.apkUrl).use { response ->
                val body = response.body
                if (response.code !in 200..299 || body == null) return DownloadResult.HttpError(response.code)
                val total = info.sizeBytes ?: response.contentLength.takeIf { it > 0 }
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var lastReported = -1f
                onProgress(if (total != null) 0f else -1f)
                FileOutputStream(partial).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = body.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        // 크기를 넘게 받으면 잘못된 파일이다(무한 응답 방지).
                        if (total != null && done > total + SIZE_SLACK) return DownloadResult.ChecksumMismatch.also { partial.delete() }
                        if (total != null) {
                            val p = (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
                            if (p - lastReported >= PROGRESS_STEP || p >= 1f) {
                                lastReported = p
                                onProgress(p)
                            }
                        }
                    }
                    out.flush()
                    out.fd.sync()
                }
                digest.digest().toHex()
            }
            currentCoroutineContext().ensureActive()
            if (actual != expected) {
                partial.delete()
                return DownloadResult.ChecksumMismatch
            }
            target.delete()
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            return DownloadResult.Success(target)
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: IOException) {
            partial.delete()
            return DownloadResult.NetworkError
        } finally {
            // 성공했으면 이미 이름이 바뀌어 없다. 어떤 경로로 끝나도 .partial은 남기지 않는다.
            if (partial.exists()) partial.delete()
        }
    }

    /** 이미 받은 APK가 있고 SHA-256이 맞으면 그 파일. 맞지 않으면 지운다. */
    fun verifiedApk(info: UpdateInfo): File? {
        val expected = info.sha256?.let { UpdateManifests.normalizeSha256(it) } ?: return null
        val file = apkFile(info)
        if (!file.isFile) return null
        val actual = runCatching { sha256Of(file) }.getOrNull()
        if (actual == expected) return file
        file.delete()
        return null
    }

    /**
     * 정리: .partial 전부 + 지금 버전 이하(이미 설치됐거나 오래된) APK. 더 새 버전의 검증된 APK는 남겨 다시 받지 않게 한다.
     */
    fun cleanup(currentVersionCode: Int) = cleanupDir(dir, currentVersionCode)

    companion object {
        const val PREFIX = "PhotoCull-"
        private const val APK = ".apk"
        private const val PARTIAL = ".apk.partial"
        private const val BUFFER = 64 * 1024
        private const val PROGRESS_STEP = 0.005f
        /** 받은 뒤 설치 세션에 복사할 여유까지 고려한 최소 여유 공간 */
        const val SPACE_MARGIN = 50L * 1024 * 1024
        private const val SIZE_SLACK = 1024L * 1024

        /** [currentVersionCode] = null이면 전부 지운다(업데이트 설치 직후). */
        fun cleanupDir(dir: File, currentVersionCode: Int?) {
            val files = dir.listFiles() ?: return
            for (f in files) {
                val name = f.name
                val stale = when {
                    currentVersionCode == null -> true
                    name.endsWith(PARTIAL) -> true
                    name.startsWith(PREFIX) && name.endsWith(APK) -> {
                        val code = Versions.codeOf(name.removePrefix(PREFIX).removeSuffix(APK))
                        code == null || code <= currentVersionCode
                    }
                    else -> false
                }
                if (stale && f.isFile) f.delete()
            }
        }

        fun safeVersion(v: String): String = v.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.ifEmpty { "unknown" }

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
