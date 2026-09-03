package com.affilemanager.app.network

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebDavRedirectsTest {
    private fun request(method: String = "PROPFIND") = Request.Builder()
        .url("https://files.example.test/dav")
        .method(method, if (method == "GET") null else "test".toRequestBody())
        .build()

    @Test
    fun acceptsRelativeAndAbsoluteCollectionRedirectsOnSameOrigin() {
        for (code in listOf(301, 302, 307, 308)) {
            assertEquals("https://files.example.test/dav/", WebDavRedirects.next(request(), code, "/dav/", 0).toString())
            assertEquals("https://files.example.test/dav/", WebDavRedirects.next(request(), code, "https://files.example.test/dav/", 0).toString())
        }
        assertEquals("https://files.example.test/file.txt", WebDavRedirects.next(request("GET"), 302, "/file.txt", 0).toString())
    }

    @Test
    fun rejectsEveryOriginOrCredentialChangeWithoutEchoingLocation() {
        val marker = "TOP_SECRET_MARKER"
        for (location in listOf(
            "https://other.example.test/$marker", "http://files.example.test/$marker",
            "https://files.example.test:8443/$marker", "https://account:$marker@files.example.test/dav/",
            "/dav/?secret=$marker", "/dav/#$marker", "\n$marker", "x".repeat(8_193), "",
        )) {
            val failure = assertThrows(WebDavRedirectException::class.java) { WebDavRedirects.next(request(), 302, location, 0) }
            assertEquals(WebDavRedirectFailure.UNSAFE, failure.reason)
            assertEquals("WebDAV UNSAFE-REDIRECT", failure.message)
        }
    }

    @Test
    fun neverReplaysWritesOrChangesPropfindToGet() {
        for (method in listOf("PUT", "MOVE", "MKCOL", "DELETE")) {
            val failure = assertThrows(WebDavRedirectException::class.java) { WebDavRedirects.next(request(method), 307, "/dav/", 0) }
            assertEquals(WebDavRedirectFailure.UNSUPPORTED, failure.reason)
        }
        val failure = assertThrows(WebDavRedirectException::class.java) { WebDavRedirects.next(request(), 303, "/dav/", 0) }
        assertEquals(WebDavRedirectFailure.UNSUPPORTED, failure.reason)
    }

    @Test
    fun limitsRedirectChains() {
        val failure = assertThrows(WebDavRedirectException::class.java) {
            WebDavRedirects.next(request(), 302, "/dav/", WebDavRedirects.MAX_REDIRECTS)
        }
        assertEquals(WebDavRedirectFailure.LIMIT, failure.reason)
    }
}
