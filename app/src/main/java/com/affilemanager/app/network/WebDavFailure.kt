package com.affilemanager.app.network

import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException

/** Structured failures never retain server response bodies, URLs, or credentials. */
class WebDavHttpException(val statusCode: Int) : IOException("WebDAV HTTP $statusCode")

enum class WebDavRedirectFailure(val diagnosticPart: String) {
    UNSAFE("UNSAFE-REDIRECT"),
    LIMIT("REDIRECT-LIMIT"),
    UNSUPPORTED("REDIRECT"),
}

class WebDavRedirectException(val reason: WebDavRedirectFailure) : IOException("WebDAV ${reason.diagnosticPart}")

internal object WebDavRedirects {
    const val MAX_REDIRECTS = 5
    private const val MAX_LOCATION_LENGTH = 8_192
    private val supportedCodes = setOf(301, 302, 307, 308)

    fun next(request: Request, statusCode: Int, location: String?, followed: Int): HttpUrl {
        if (request.method !in setOf("GET", "PROPFIND") || statusCode !in supportedCodes) {
            throw WebDavRedirectException(WebDavRedirectFailure.UNSUPPORTED)
        }
        if (followed >= MAX_REDIRECTS) throw WebDavRedirectException(WebDavRedirectFailure.LIMIT)
        if (location.isNullOrBlank() || location.length > MAX_LOCATION_LENGTH || location.any { it.code < 32 }) {
            throw WebDavRedirectException(WebDavRedirectFailure.UNSAFE)
        }
        val target = request.url.resolve(location) ?: throw WebDavRedirectException(WebDavRedirectFailure.UNSAFE)
        if (target.scheme != request.url.scheme || target.host != request.url.host || target.port != request.url.port ||
            target.username.isNotEmpty() || target.password.isNotEmpty() || target.query != null || target.fragment != null
        ) {
            throw WebDavRedirectException(WebDavRedirectFailure.UNSAFE)
        }
        return target
    }
}
