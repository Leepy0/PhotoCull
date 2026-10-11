package com.leepy0.photocull.update

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress

/** 실제 HttpURLConnection 구현: 리다이렉트(github.com → githubusercontent.com 같은 다른 호스트 포함), 헤더, 오류 코드 */
class UrlConnectionHttpTest {
    private lateinit var server: HttpServer
    private lateinit var other: HttpServer
    private val seenUserAgents = mutableListOf<String?>()
    private val seenAccept = mutableListOf<String?>()

    @Before fun start() {
        other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/asset") { ex ->
                seenUserAgents += ex.requestHeaders.getFirst("User-Agent")
                val body = "{\"version\":\"0.2.0\"}".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/latest") { ex ->
                seenAccept += ex.requestHeaders.getFirst("Accept")
                ex.responseHeaders.add("Location", "http://127.0.0.1:${other.address.port}/asset")
                ex.sendResponseHeaders(302, -1)
                ex.close()
            }
            createContext("/relative") { ex ->
                ex.responseHeaders.add("Location", "/missing")
                ex.sendResponseHeaders(301, -1)
                ex.close()
            }
            createContext("/loop") { ex ->
                ex.responseHeaders.add("Location", "/loop")
                ex.sendResponseHeaders(302, -1)
                ex.close()
            }
            createContext("/missing") { ex ->
                ex.sendResponseHeaders(404, -1)
                ex.close()
            }
            createContext("/big") { ex ->
                val body = ByteArray(5000) { 'x'.code.toByte() }
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
    }

    @After fun stop() {
        server.stop(0)
        other.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
    private val http = UrlConnectionHttp(userAgent = "PhotoCull/9.9.9", connectTimeoutMs = 2000, readTimeoutMs = 2000)

    @Test fun followsRedirectToOtherHostKeepingHeaders() {
        val r = http.getText(url("/latest"), mapOf("Accept" to "application/vnd.github+json"))
        assertThat(r.code).isEqualTo(200)
        assertThat(r.body).isEqualTo("{\"version\":\"0.2.0\"}")
        assertThat(seenAccept).containsExactly("application/vnd.github+json")
        assertThat(seenUserAgents).containsExactly("PhotoCull/9.9.9")
    }

    @Test fun relativeRedirectAndNotFound() {
        assertThat(http.getText(url("/relative")).code).isEqualTo(404)
        http.openStream(url("/missing")).use { assertThat(it.code).isEqualTo(404); assertThat(it.body).isNull() }
    }

    @Test fun streamReportsLength() {
        http.openStream(url("/big")).use { s ->
            assertThat(s.code).isEqualTo(200)
            assertThat(s.contentLength).isEqualTo(5000)
            assertThat(s.body!!.readBytes().size).isEqualTo(5000)
        }
    }

    @Test(expected = IOException::class) fun redirectLoopFails() {
        http.getText(url("/loop"))
    }

    @Test(expected = IOException::class) fun oversizedTextFails() {
        http.getText(url("/big"), maxBytes = 100)
    }

    @Test(expected = IOException::class) fun connectionRefusedIsIOException() {
        val port = server.address.port
        server.stop(0)
        http.getText("http://127.0.0.1:$port/latest")
    }
}
