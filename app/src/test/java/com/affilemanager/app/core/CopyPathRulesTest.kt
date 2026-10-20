package com.affilemanager.app.core

import org.junit.Assert.*
import org.junit.Test

class CopyPathRulesTest {
    @Test fun orderedPathsAreSingleLineQuotedArgumentsWithoutTrailingSpace() {
        assertEquals("'/storage/a b.txt' '/storage/Žąsis.txt' '/storage/o'\"'\"'hare.txt'",
            CopyPathRules.quotedArguments(listOf("/storage/a b.txt", "/storage/Žąsis.txt", "/storage/o'hare.txt")))
    }
    @Test fun shellMetacharactersStayInsideQuotes() {
        val path = "/a/\$(touch x); & `x` * \\"
        assertEquals("'$path'", CopyPathRules.quotedArguments(listOf(path)))
    }
    @Test fun lineBreaksNulAndOversizedResultsAreRejected() {
        listOf("/a\nb", "/a\rb", "/a\u0000b", "x".repeat(CopyPathRules.MAX_BYTES)).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { CopyPathRules.quotedArguments(listOf(path)) }
        }
        assertThrows(IllegalArgumentException::class.java) { CopyPathRules.quotedArguments(List(40_000) { "/a" }) }
    }
}
