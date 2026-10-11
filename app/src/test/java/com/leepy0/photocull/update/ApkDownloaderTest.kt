package com.leepy0.photocull.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ApkDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val http = FakeUpdateHttp()
    private val apkBytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val goodSha = sha(apkBytes)
    private val url = TestReleases.APK_URL

    private fun info(sha: String? = goodSha, size: Long? = apkBytes.size.toLong(), version: String = "0.2.0") = UpdateInfo(
        versionName = version,
        versionCode = Versions.codeOf(version)!!,
        apkUrl = url,
        sha256 = sha,
        sizeBytes = size,
        notes = "",
        pageUrl = "https://github.com/x/y/releases/tag/v$version",
        publishedAt = null,
    )

    private fun dir(): File = File(tmp.root, "update")

    @Test fun successVerifiesAndRenames() = runTest {
        http.bytes(url, apkBytes)
        val progress = mutableListOf<Float>()
        val d = ApkDownloader(http, dir())
        val r = d.download(info()) { progress += it }
        assertThat(r).isInstanceOf(DownloadResult.Success::class.java)
        val apk = (r as DownloadResult.Success).apk
        assertThat(apk.name).isEqualTo("PhotoCull-0.2.0.apk")
        assertThat(apk.readBytes()).isEqualTo(apkBytes)
        assertThat(dir().list()!!.toList()).containsExactly("PhotoCull-0.2.0.apk")
        assertThat(progress.first()).isEqualTo(0f)
        assertThat(progress.last()).isEqualTo(1f)
        assertThat(progress).isInOrder()
    }

    @Test fun unknownSizeReportsIndeterminateProgress() = runTest {
        http.bytes(url, apkBytes, contentLength = -1)
        val progress = mutableListOf<Float>()
        val r = ApkDownloader(http, dir()).download(info(size = null)) { progress += it }
        assertThat(r).isInstanceOf(DownloadResult.Success::class.java)
        assertThat(progress).containsExactly(-1f)
    }

    @Test fun contentLengthUsedWhenManifestHasNoSize() = runTest {
        http.bytes(url, apkBytes)
        val progress = mutableListOf<Float>()
        ApkDownloader(http, dir()).download(info(size = null)) { progress += it }
        assertThat(progress.last()).isEqualTo(1f)
    }

    @Test fun shaMismatchDeletesEverything() = runTest {
        http.bytes(url, apkBytes)
        val r = ApkDownloader(http, dir()).download(info(sha = "c".repeat(64))) {}
        assertThat(r).isEqualTo(DownloadResult.ChecksumMismatch)
        assertThat(dir().list()!!.toList()).isEmpty()
    }

    @Test fun missingShaRefusesWithoutDownloading() = runTest {
        http.bytes(url, apkBytes)
        val r = ApkDownloader(http, dir()).download(info(sha = null)) {}
        assertThat(r).isEqualTo(DownloadResult.MissingChecksum)
        assertThat(http.requests).isEmpty()
    }

    @Test fun oversizedResponseIsRejected() = runTest {
        http.bytes(url, apkBytes + ByteArray(2 * 1024 * 1024))
        val r = ApkDownloader(http, dir()).download(info()) {}
        assertThat(r).isEqualTo(DownloadResult.ChecksumMismatch)
        assertThat(dir().list()!!.toList()).isEmpty()
    }

    @Test fun connectionDropRemovesPartial() = runTest {
        http.bytes(url, apkBytes, failAfter = 100_000)
        val r = ApkDownloader(http, dir()).download(info()) {}
        assertThat(r).isEqualTo(DownloadResult.NetworkError)
        assertThat(dir().list()!!.toList()).isEmpty()
    }

    @Test fun httpErrorIsReported() = runTest {
        http.bytes(url, apkBytes, code = 404)
        val r = ApkDownloader(http, dir()).download(info()) {}
        assertThat(r).isEqualTo(DownloadResult.HttpError(404))
    }

    @Test fun notEnoughSpaceRefusesBeforeDownloading() = runTest {
        http.bytes(url, apkBytes)
        val d = ApkDownloader(http, dir(), availableBytes = { 1000L })
        val r = d.download(info()) {}
        assertThat(r).isInstanceOf(DownloadResult.NotEnoughSpace::class.java)
        assertThat(http.requests).isEmpty()
    }

    @Test fun cancellationRemovesPartial() = runTest {
        http.bytes(url, apkBytes)
        val d = ApkDownloader(http, dir())
        val job = async {
            val self = coroutineContext.job
            d.download(info()) { p -> if (p > 0.3f) self.cancel() }
        }
        val thrown = runCatching { job.await() }.exceptionOrNull()
        assertThat(job.isCancelled).isTrue()
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(dir().list()!!.toList()).isEmpty()
    }

    @Test fun existingVerifiedApkIsReusedAndCorruptOneReplaced() = runTest {
        http.bytes(url, apkBytes)
        val d = ApkDownloader(http, dir())
        d.download(info()) {}
        http.requests.clear()
        assertThat(d.download(info()) {}).isInstanceOf(DownloadResult.Success::class.java)
        assertThat(http.requests).isEmpty()

        // 손상된 파일은 검증에서 걸러지고 다시 받는다.
        d.apkFile(info()).writeBytes(byteArrayOf(1, 2, 3))
        assertThat(d.verifiedApk(info())).isNull()
        assertThat(d.apkFile(info()).exists()).isFalse()
        assertThat(d.download(info()) {}).isInstanceOf(DownloadResult.Success::class.java)
        assertThat(http.requests).containsExactly(url)
    }

    @Test fun cleanupKeepsOnlyNewerVerifiedApks() {
        val dir = dir().apply { mkdirs() }
        listOf("PhotoCull-0.1.0.apk", "PhotoCull-0.2.0.apk", "PhotoCull-0.3.0.apk", "PhotoCull-0.3.0.apk.partial", "PhotoCull-x.apk", "other.txt")
            .forEach { File(dir, it).writeText("x") }
        ApkDownloader(http, dir).cleanup(currentVersionCode = 200)
        assertThat(dir.list()!!.toList()).containsExactly("PhotoCull-0.3.0.apk", "other.txt")

        ApkDownloader.cleanupDir(dir, currentVersionCode = null)
        assertThat(dir.list()!!.toList()).isEmpty()
    }

    @Test fun cleanupOfMissingDirIsHarmless() {
        ApkDownloader.cleanupDir(File(tmp.root, "nope"), 100)
    }

    @Test fun versionIsSanitizedForFileName() {
        val d = ApkDownloader(http, dir())
        assertThat(d.apkFile(info(version = "0.2.0")).name).isEqualTo("PhotoCull-0.2.0.apk")
        assertThat(ApkDownloader.safeVersion("../../0.2.0")).isEqualTo("....0.2.0")
        assertThat(ApkDownloader.safeVersion("/")).isEqualTo("unknown")
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
