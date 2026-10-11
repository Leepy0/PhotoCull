package com.leepy0.photocull.update

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.update.TestReleases.REPO
import org.junit.Test

class UpdateManifestTest {

    @Test fun parsesManifestAndIgnoresUnknownFields() {
        val text = TestReleases.manifest(extra = "\"futureField\":{\"nested\":[1,2]},\"channel\":\"release\",")
        val m = UpdateManifests.parse(text)!!
        val info = UpdateManifests.toInfo(m, REPO, "PhotoCull.apk")!!
        assertThat(info.versionName).isEqualTo("0.2.0")
        assertThat(info.versionCode).isEqualTo(200)
        assertThat(info.apkUrl).isEqualTo(TestReleases.APK_URL)
        assertThat(info.sha256).isEqualTo("a".repeat(64))
        assertThat(info.sizeBytes).isEqualTo(1234)
        assertThat(info.notes).contains("흐림 판정 개선")
        assertThat(info.pageUrl).isEqualTo("https://github.com/$REPO/releases/tag/v0.2.0")
        assertThat(info.publishedAt).isEqualTo("2026-10-11T05:03:00Z")
    }

    /** scripts/release_manifest.py가 실제로 만드는 형식(들여쓰기, 한국어 노트, 줄바꿈 이스케이프) */
    @Test fun parsesManifestProducedByCiScript() {
        val text = """
            {
              "version": "0.1.0",
              "versionCode": 100,
              "file": "PhotoCull.apk",
              "url": "https://github.com/Leepy0/PhotoCull/releases/download/v0.1.0/PhotoCull.apk",
              "sha256": "b76063baec18acce6b41ed404a27314d2818745d0f6c55ad517375e5454c4649",
              "size": 123457,
              "published": "2026-10-11T05:03:00Z",
              "page": "https://github.com/Leepy0/PhotoCull/releases/tag/v0.1.0",
              "notes": "- 앱 기반 구조: 빌드 설정, 데이터 계층\n- 개발 계획 문서(PLAN.md) 추가"
            }
        """.trimIndent()
        val info = UpdateManifests.toInfo(UpdateManifests.parse(text)!!, REPO, "PhotoCull.apk")!!
        assertThat(info.versionCode).isEqualTo(100)
        assertThat(info.sha256).isEqualTo("b76063baec18acce6b41ed404a27314d2818745d0f6c55ad517375e5454c4649")
        assertThat(info.sizeBytes).isEqualTo(123457)
        assertThat(info.notes.lines()).containsExactly("- 앱 기반 구조: 빌드 설정, 데이터 계층", "- 개발 계획 문서(PLAN.md) 추가").inOrder()
    }

    @Test fun missingShaGivesNullAndInvalidShaIsRejected() {
        val noSha = UpdateManifests.toInfo(UpdateManifests.parse(TestReleases.manifest(sha = null))!!, REPO, "PhotoCull.apk")!!
        assertThat(noSha.sha256).isNull()
        val badSha = UpdateManifests.toInfo(UpdateManifests.parse(TestReleases.manifest(sha = "xyz"))!!, REPO, "PhotoCull.apk")!!
        assertThat(badSha.sha256).isNull()
    }

    @Test fun missingVersionCodeFallsBackToVersionString() {
        val info = UpdateManifests.toInfo(UpdateManifests.parse(TestReleases.manifest(version = "1.2.3", versionCode = null))!!, REPO, "PhotoCull.apk")!!
        assertThat(info.versionCode).isEqualTo(10203)
    }

    @Test fun minimalManifestBuildsDefaultUrls() {
        val info = UpdateManifests.toInfo(UpdateManifests.parse("""{"version":"v0.3.1"}""")!!, REPO, "PhotoCull.apk")!!
        assertThat(info.versionName).isEqualTo("0.3.1")
        assertThat(info.versionCode).isEqualTo(301)
        assertThat(info.apkUrl).isEqualTo("https://github.com/$REPO/releases/download/v0.3.1/PhotoCull.apk")
        assertThat(info.pageUrl).isEqualTo("https://github.com/$REPO/releases/tag/v0.3.1")
        assertThat(info.sizeBytes).isNull()
        assertThat(info.notes).isEmpty()
    }

    @Test fun nonHttpsUrlIsReplacedByDefault() {
        val m = UpdateManifest(version = "0.2.0", url = "http://evil.example/PhotoCull.apk", page = "javascript:alert(1)")
        val info = UpdateManifests.toInfo(m, REPO, "PhotoCull.apk")!!
        assertThat(info.apkUrl).isEqualTo(TestReleases.APK_URL)
        assertThat(info.pageUrl).startsWith("https://github.com/")
    }

    @Test fun malformedOrVersionlessManifestIsRejected() {
        assertThat(UpdateManifests.parse("not json")).isNull()
        assertThat(UpdateManifests.parse("{\"version\":")).isNull()
        assertThat(UpdateManifests.toInfo(UpdateManifest(notes = "x"), REPO, "PhotoCull.apk")).isNull()
        assertThat(UpdateManifests.toInfo(UpdateManifest(version = "banana"), REPO, "PhotoCull.apk")).isNull()
    }

    @Test fun normalizesSha256Formats() {
        val hex = "0123456789abcdef".repeat(4)
        assertThat(UpdateManifests.normalizeSha256(hex.uppercase())).isEqualTo(hex)
        assertThat(UpdateManifests.normalizeSha256("$hex  PhotoCull.apk\n")).isEqualTo(hex)
        assertThat(UpdateManifests.normalizeSha256("sha256:$hex")).isEqualTo(hex)
        assertThat(UpdateManifests.normalizeSha256(hex.dropLast(1))).isNull()
        assertThat(UpdateManifests.normalizeSha256("")).isNull()
        assertThat(UpdateManifests.normalizeSha256(null)).isNull()
    }

    @Test fun parsesGitHubRelease() {
        val json = """
            {"tag_name":"v0.2.0","name":"v0.2.0","body":"- 수정","html_url":"https://github.com/x/y/releases/tag/v0.2.0",
             "published_at":"2026-10-11T05:03:00Z","draft":false,"prerelease":false,"author":{"login":"x"},
             "assets":[{"name":"PhotoCull.apk","browser_download_url":"https://github.com/x/y/releases/download/v0.2.0/PhotoCull.apk","size":10,"uploader":{}}]}
        """.trimIndent()
        val r = UpdateManifests.parseRelease(json)!!
        assertThat(r.tagName).isEqualTo("v0.2.0")
        assertThat(r.assets.single().size).isEqualTo(10)
    }

    @Test fun versionCodesMatchGradleFormula() {
        assertThat(Versions.codeOf("0.1.0")).isEqualTo(100)
        assertThat(Versions.codeOf("0.2.0")).isEqualTo(200)
        assertThat(Versions.codeOf("v1.2.3")).isEqualTo(10203)
        assertThat(Versions.codeOf("1.2")).isEqualTo(10200)
        assertThat(Versions.codeOf("2.0.1-beta+5")).isEqualTo(20001)
        assertThat(Versions.codeOf("0.100.0")).isNull()
        assertThat(Versions.codeOf("0.0.0")).isNull()
        assertThat(Versions.codeOf("1.2.3.4")).isNull()
        assertThat(Versions.codeOf("a.b.c")).isNull()
        assertThat(Versions.nameOf(10203)).isEqualTo("1.2.3")
        assertThat(Versions.nameOf(100)).isEqualTo("0.1.0")
    }

    @Test fun versionComparison() {
        assertThat(Versions.isNewer(200, 100)).isTrue()
        assertThat(Versions.isNewer(100, 100)).isFalse()
        assertThat(Versions.isNewer(99, 100)).isFalse()
        // 0.10.0(1000)은 0.9.0(900)보다 새 버전(문자열 비교였다면 반대)
        assertThat(Versions.isNewer(Versions.codeOf("0.10.0")!!, Versions.codeOf("0.9.0")!!)).isTrue()
    }

    @Test fun justUpdatedMessage() {
        assertThat(JustUpdated.message(100, 200, "0.2.0")).isEqualTo("v0.1.0 → v0.2.0")
        assertThat(JustUpdated.message(0, 200, "0.2.0")).isNull() // 첫 설치
        assertThat(JustUpdated.message(200, 200, "0.2.0")).isNull() // 같은 버전
        assertThat(JustUpdated.message(300, 200, "0.2.0")).isNull() // 다운그레이드
    }
}
