package com.leepy0.photocull.update

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.update.TestReleases.API_URL
import com.leepy0.photocull.update.TestReleases.APK_URL
import com.leepy0.photocull.update.TestReleases.MANIFEST_URL
import com.leepy0.photocull.update.TestReleases.REPO
import org.junit.Test

class UpdateCheckerTest {
    private val http = FakeUpdateHttp()
    private val checker = UpdateChecker(http, REPO, "PhotoCull.json", "PhotoCull.apk")
    private val sha = "b".repeat(64)

    @Test fun manifestNewerIsAvailableWithoutTouchingApi() {
        http.text(MANIFEST_URL, 200, TestReleases.manifest(sha = sha))
        val r = checker.check(currentVersionCode = 100)
        assertThat(r).isInstanceOf(CheckResult.Available::class.java)
        val info = (r as CheckResult.Available).info
        assertThat(info.versionCode).isEqualTo(200)
        assertThat(info.sha256).isEqualTo(sha)
        assertThat(http.requests).containsExactly(MANIFEST_URL)
    }

    @Test fun manifestSameOrOlderIsUpToDate() {
        http.text(MANIFEST_URL, 200, TestReleases.manifest())
        assertThat(checker.check(200)).isEqualTo(CheckResult.UpToDate("0.2.0"))
        assertThat(checker.check(300)).isEqualTo(CheckResult.UpToDate("0.2.0"))
    }

    @Test fun manifestWithoutShaFetchesSidecarFile() {
        http.text(MANIFEST_URL, 200, TestReleases.manifest(sha = null))
        http.text("$APK_URL.sha256", 200, "$sha  PhotoCull.apk\n")
        val info = (checker.check(100) as CheckResult.Available).info
        assertThat(info.sha256).isEqualTo(sha)
    }

    @Test fun manifestWithoutShaAndNoSidecarStillReportsUpdateWithNullSha() {
        http.text(MANIFEST_URL, 200, TestReleases.manifest(sha = null))
        val info = (checker.check(100) as CheckResult.Available).info
        assertThat(info.sha256).isNull()
    }

    @Test fun manifest404FallsBackToApiManifestAsset() {
        val manifestAsset = "https://github.com/$REPO/releases/download/v0.2.0/PhotoCull.json"
        http.text(MANIFEST_URL, 404)
        http.text(API_URL, 200, apiRelease(assets = listOf("PhotoCull.json" to manifestAsset, "PhotoCull.apk" to APK_URL)))
        http.text(manifestAsset, 200, TestReleases.manifest(sha = sha, notes = ""))
        val info = (checker.check(100) as CheckResult.Available).info
        assertThat(info.versionName).isEqualTo("0.2.0")
        assertThat(info.sha256).isEqualTo(sha)
        // 매니페스트에 노트가 없으면 릴리스 본문을 쓴다.
        assertThat(info.notes).isEqualTo("- 릴리스 본문")
        assertThat(http.requestHeaders[API_URL]).containsEntry("Accept", "application/vnd.github+json")
    }

    @Test fun manifestNetworkErrorFallsBackToApiApkAndShaAssets() {
        val shaAsset = "$APK_URL.sha256"
        http.networkError(MANIFEST_URL)
        http.text(API_URL, 200, apiRelease(assets = listOf("PhotoCull.apk" to APK_URL, "PhotoCull.apk.sha256" to shaAsset)))
        http.text(shaAsset, 200, sha)
        val info = (checker.check(100) as CheckResult.Available).info
        assertThat(info.versionCode).isEqualTo(200)
        assertThat(info.apkUrl).isEqualTo(APK_URL)
        assertThat(info.sha256).isEqualTo(sha)
        assertThat(info.sizeBytes).isEqualTo(4321)
        assertThat(info.notes).isEqualTo("- 릴리스 본문")
        assertThat(info.pageUrl).isEqualTo("https://github.com/$REPO/releases/tag/v0.2.0")
    }

    @Test fun apiReleaseWithoutApkIsNotInstallableSoUpToDate() {
        http.text(MANIFEST_URL, 404)
        http.text(API_URL, 200, apiRelease(assets = emptyList()))
        assertThat(checker.check(100)).isEqualTo(CheckResult.UpToDate("0.2.0"))
    }

    @Test fun bothNotFoundMeansNoReleaseYet() {
        http.text(MANIFEST_URL, 404)
        http.text(API_URL, 404)
        assertThat(checker.check(100)).isEqualTo(CheckResult.UpToDate(null))
    }

    @Test fun networkErrorsEverywhereFail() {
        http.networkError(MANIFEST_URL)
        http.networkError(API_URL)
        assertThat(checker.check(100)).isEqualTo(CheckResult.Failed(CheckFailure.NETWORK))
    }

    @Test fun manifest404AndApiNetworkErrorFails() {
        http.text(MANIFEST_URL, 404)
        http.networkError(API_URL)
        assertThat(checker.check(100)).isEqualTo(CheckResult.Failed(CheckFailure.NETWORK))
    }

    @Test fun apiRateLimitIsReported() {
        http.text(MANIFEST_URL, 503)
        http.text(API_URL, 403)
        assertThat(checker.check(100)).isEqualTo(CheckResult.Failed(CheckFailure.RATE_LIMITED))
    }

    @Test fun serverErrorIsReported() {
        http.text(MANIFEST_URL, 502)
        http.text(API_URL, 500)
        assertThat(checker.check(100)).isEqualTo(CheckResult.Failed(CheckFailure.SERVER))
    }

    @Test fun malformedManifestFallsBackToApi() {
        http.text(MANIFEST_URL, 200, "<html>oops</html>")
        http.text(API_URL, 200, apiRelease(assets = listOf("PhotoCull.apk" to APK_URL)))
        val info = (checker.check(100) as CheckResult.Available).info
        assertThat(info.versionCode).isEqualTo(200)
        assertThat(info.sha256).isNull()
    }

    @Test fun malformedManifestAndUnreachableApiIsInvalid() {
        http.text(MANIFEST_URL, 200, "<html>oops</html>")
        http.networkError(API_URL)
        assertThat(checker.check(100)).isEqualTo(CheckResult.Failed(CheckFailure.INVALID_RELEASE))
    }

    private fun apiRelease(tag: String = "v0.2.0", assets: List<Pair<String, String>>): String {
        val assetJson = assets.joinToString(",") { (name, url) ->
            """{"name":"$name","browser_download_url":"$url","size":${if (name.endsWith(".apk")) 4321 else 70},"state":"uploaded"}"""
        }
        return """{"tag_name":"$tag","name":"$tag","body":"- 릴리스 본문","html_url":"https://github.com/$REPO/releases/tag/$tag",
            "published_at":"2026-10-11T05:03:00Z","draft":false,"prerelease":false,"assets":[$assetJson]}"""
    }
}
