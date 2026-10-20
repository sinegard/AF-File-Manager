package com.affilemanager.app.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow

internal val CanonicalPathSource = SemanticsPropertyKey<String>("CanonicalPathSource")

/** Isolate path components, not their glyphs: neutral slashes otherwise join adjacent RTL runs. */
internal fun canonicalPathForDisplay(path: String): String {
    var offset = 0
    var hasRtl = false
    while (offset < path.length) {
        val codePoint = Character.codePointAt(path, offset)
        val direction = Character.getDirectionality(codePoint)
        if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
            hasRtl = true
            break
        }
        offset += Character.charCount(codePoint)
    }
    if (!hasRtl) return path
    return buildString(path.length + 16) {
        var start = 0
        for (index in path.indices) {
            if (path[index] != '/' && path[index] != '\\') continue
            if (start < index) append('\u2068').append(path, start, index).append('\u2069')
            append(path[index])
            start = index + 1
        }
        if (start < path.length) append('\u2068').append(path, start, path.length).append('\u2069')
    }
}

/** Display-only bidi isolation. Actions and persistence continue to use the unmodified source. */
@Composable
internal fun CanonicalPathText(
    path: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val pathStyle = remember(style) { style.copy(textDirection = TextDirection.Ltr) }
    val displayPath = remember(path) { canonicalPathForDisplay(path) }
    Text(displayPath, modifier = modifier.semantics { this[CanonicalPathSource] = path },
        style = pathStyle, maxLines = maxLines, overflow = overflow)
}
