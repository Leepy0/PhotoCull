package com.leepy0.photocull.update

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** URL별 응답을 미리 정해 두는 가짜 HTTP. 등록되지 않은 URL은 404. */
class FakeUpdateHttp : UpdateHttp {
    sealed interface Route {
        data class Text(val code: Int, val body: String = "") : Route
        data class Bytes(val code: Int, val bytes: ByteArray, val contentLength: Long = bytes.size.toLong(), val failAfter: Int = -1) : Route
        data object NetworkError : Route
    }

    private val routes = mutableMapOf<String, Route>()
    val requests = mutableListOf<String>()
    val requestHeaders = mutableMapOf<String, Map<String, String>>()

    fun text(url: String, code: Int, body: String = "") = apply { routes[url] = Route.Text(code, body) }
    fun bytes(url: String, bytes: ByteArray, code: Int = 200, contentLength: Long = bytes.size.toLong(), failAfter: Int = -1) =
        apply { routes[url] = Route.Bytes(code, bytes, contentLength, failAfter) }
    fun networkError(url: String) = apply { routes[url] = Route.NetworkError }

    /** 다음 한 번만 연결이 끊기고, 그 뒤로는 원래 응답 */
    fun networkErrorOnce(url: String) = apply { oneShot.getOrPut(url) { ArrayDeque() }.addLast(Route.NetworkError) }

    private val oneShot = mutableMapOf<String, ArrayDeque<Route>>()

    private fun route(url: String): Route? = oneShot[url]?.removeFirstOrNull() ?: routes[url]

    override fun getText(url: String, headers: Map<String, String>, maxBytes: Int): HttpText {
        requests += url
        requestHeaders[url] = headers
        return when (val r = route(url)) {
            null -> HttpText(404, "")
            is Route.Text -> HttpText(r.code, if (r.code in 200..299) r.body else "")
            is Route.Bytes -> HttpText(r.code, String(r.bytes))
            Route.NetworkError -> throw IOException("fake network error")
        }
    }

    override fun openStream(url: String): HttpStream {
        requests += url
        return when (val r = route(url)) {
            null -> HttpStream(404, -1, null)
            is Route.Text -> HttpStream(r.code, r.body.length.toLong(), if (r.code in 200..299) ByteArrayInputStream(r.body.toByteArray()) else null)
            is Route.Bytes -> HttpStream(
                r.code,
                r.contentLength,
                if (r.code in 200..299) failingStream(r.bytes, r.failAfter) else null,
            )
            Route.NetworkError -> throw IOException("fake network error")
        }
    }

    private fun failingStream(bytes: ByteArray, failAfter: Int): InputStream {
        if (failAfter < 0) return ByteArrayInputStream(bytes)
        return object : InputStream() {
            private var pos = 0
            override fun read(): Int {
                if (pos >= failAfter) throw IOException("connection reset")
                return if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= failAfter) throw IOException("connection reset")
                if (pos >= bytes.size) return -1
                val n = minOf(len, bytes.size - pos, failAfter - pos)
                System.arraycopy(bytes, pos, b, off, n)
                pos += n
                return n
            }
        }
    }
}

object TestReleases {
    const val REPO = "Leepy0/PhotoCull"
    const val MANIFEST_URL = "https://github.com/$REPO/releases/latest/download/PhotoCull.json"
    const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val APK_URL = "https://github.com/$REPO/releases/download/v0.2.0/PhotoCull.apk"

    fun manifest(
        version: String = "0.2.0",
        versionCode: Int? = 200,
        sha: String? = "a".repeat(64),
        size: Long = 1234,
        notes: String = "- 흐림 판정 개선\n- 연사 묶음 속도 향상",
        extra: String = "",
    ): String = buildString {
        append("{")
        append("\"version\":\"$version\",")
        if (versionCode != null) append("\"versionCode\":$versionCode,")
        append("\"file\":\"PhotoCull.apk\",")
        append("\"url\":\"https://github.com/$REPO/releases/download/v$version/PhotoCull.apk\",")
        if (sha != null) append("\"sha256\":\"$sha\",")
        append("\"size\":$size,")
        append("\"published\":\"2026-10-11T05:03:00Z\",")
        append("\"page\":\"https://github.com/$REPO/releases/tag/v$version\",")
        append(extra)
        append("\"notes\":${quote(notes)}")
        append("}")
    }

    fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
