package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTransferOptionsTest {
    @Test
    fun blankValuesKeepAutomaticSafeDefaults() {
        val options = LanTransferOptions().validated(LanTransferProtocol.WEBDAV)

        assertEquals(0, options.port)
        assertEquals("", options.username)
        assertEquals("", options.password)
        assertTrue(!options.readOnly)
    }

    @Test
    fun usernameIsTrimmedButPasswordIsPreservedExactly() {
        val options = LanTransferOptions(
            port = 8_080,
            username = "  owner  ",
            password = "  temporary-pass  ",
            readOnly = true,
        ).validated(LanTransferProtocol.FTP)

        assertEquals(8_080, options.port)
        assertEquals("owner", options.username)
        assertEquals("  temporary-pass  ", options.password)
        assertTrue(options.readOnly)
    }

    @Test
    fun unsafePortsCredentialsAndShortPasswordsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LanTransferOptions(port = 21).validated(LanTransferProtocol.FTP)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LanTransferOptions(username = "bad:user").validated(LanTransferProtocol.WEBDAV)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LanTransferOptions(password = "short").validated(LanTransferProtocol.WEB)
        }
    }
}
