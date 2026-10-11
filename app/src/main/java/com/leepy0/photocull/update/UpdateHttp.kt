package com.leepy0.photocull.update

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 텍스트 응답(매니페스트, API JSON, .sha256). 2xx가 아니면 body는 비어 있을 수 있다. */
data class HttpText(val code: Int, val body: String)

/** 스트림 응답(APK). [body]는 2xx일 때만 있다. 사용 후 반드시 close. */
class HttpStream(
    val code: Int,
    /** 모르면 -1 */
    val contentLength: Long,
    val body: InputStream?,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { body?.close() }
        runCatching { onClose() }
    }
}

/** 업데이트용 HTTP. 네트워크 오류는 IOException. 테스트에서는 가짜로 바꿔 끼운다. */
interface UpdateHttp {
    @Throws(IOException::class)
    fun getText(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = MAX_TEXT_BYTES): HttpText

    @Throws(IOException::class)
    fun openStream(url: String): HttpStream

    companion object {
        const val MAX_TEXT_BYTES = 1 shl 20
    }
}

/**
 * HttpURLConnection 구현. 리다이렉트는 직접 따라간다(github.com → *.githubusercontent.com, 프로토콜이 바뀌는 경우 포함).
 */
class UrlConnectionHttp(
    private val userAgent: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
    private val maxRedirects: Int = 6,
) : UpdateHttp {

    override fun getText(url: String, headers: Map<String, String>, maxBytes: Int): HttpText {
        val conn = connect(url, headers)
        try {
            val code = conn.responseCode
            if (code !in 200..299) return HttpText(code, "")
            val bytes = conn.inputStream.use { readLimited(it, maxBytes) }
            return HttpText(code, bytes.toString(Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }

    override fun openStream(url: String): HttpStream {
        val conn = connect(url, emptyMap())
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            return HttpStream(code, -1, null)
        }
        return HttpStream(code, conn.contentLengthLong, conn.inputStream) { conn.disconnect() }
    }

    private fun connect(start: String, headers: Map<String, String>): HttpURLConnection {
        var current = URL(start)
        repeat(maxRedirects + 1) {
            if (current.protocol != "https" && current.protocol != "http") throw IOException("unsupported protocol")
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("User-Agent", userAgent)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                throw e
            }
            if (code in REDIRECT_CODES) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("redirect without location")
                current = URL(current, location)
                return@repeat
            }
            return conn
        }
        throw IOException("too many redirects")
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) throw IOException("response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
