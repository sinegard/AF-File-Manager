package com.affilemanager.app.media.editing

/** Class identifiers only: never expose native messages, source paths or URIs. */
internal fun mediaLinkageDiagnostic(error: Throwable): String? {
    var cause: Throwable? = error
    repeat(4) {
        val current = cause ?: return null
        if (current is NoClassDefFoundError || current is ClassNotFoundException) {
            val message = current.message.orEmpty().take(512)
            val descriptor = Regex("(?:Failed resolution of: )?L([A-Za-z_$][A-Za-z0-9_$/]*);")
                .matchEntire(message)?.groupValues?.get(1)
            val quoted = Regex("Didn't find class \"([A-Za-z_$][A-Za-z0-9_$.]*)\".*")
                .matchEntire(message)?.groupValues?.get(1)
            val plain = message.takeIf { Regex("[A-Za-z_$][A-Za-z0-9_$.]*").matches(it) }
            return (descriptor ?: quoted ?: plain)?.takeIf { it.length <= 160 }
                ?.replace('/', '.')?.let { "AF_MEDIA_CLASS=" + it }
        }
        cause = current.cause.takeUnless { it === current }
    }
    return null
}

/** Bounded artifact-code locations for diagnosing linkage failures; no file names. */
internal fun mediaLinkageOrigin(error: Throwable): String? {
    if (mediaLinkageDiagnostic(error) == null) return null
    return error.stackTrace.take(4).joinToString("\n") {
        "AF_MEDIA_AT=" + it.className + "#" + it.methodName + ":" + it.lineNumber
    }.take(768).ifEmpty { null }
}
