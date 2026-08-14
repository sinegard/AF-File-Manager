package com.affilemanager.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class RemoteErrorPresenterTest {
    @Test
    fun dnsErrorNeverEchoesExceptionTextOrCredentialMarker() {
        val marker = "TOP_SECRET_MARKER"
        val shown = RemoteErrorPresenter.present(
            NetworkProtocol.FTP,
            RemoteOperation.CONNECT,
            UnknownHostException("192.0.2.10\naccount\n$marker"),
        )
        val visible = listOf(shown.title, shown.detail, shown.suggestion, shown.diagnosticCode).joinToString(" ")

        assertEquals("Serverio adresas neteisingas", shown.title)
        assertEquals("NET-DNS", shown.diagnosticCode)
        assertFalse(marker in visible)
        assertFalse("account" in visible)
    }

    @Test
    fun ftpReplyCodesIdentifyAuthenticationAndInitialPathStages() {
        val auth = RemoteErrorPresenter.present(
            NetworkProtocol.FTP,
            RemoteOperation.CONNECT,
            FtpCommandException(FtpFailureStage.LOGIN, 530),
        )
        val path = RemoteErrorPresenter.present(
            NetworkProtocol.FTP,
            RemoteOperation.LIST,
            FtpCommandException(FtpFailureStage.LIST, 550),
        )

        assertEquals("FTP-AUTH-530", auth.diagnosticCode)
        assertTrue("prisijungimas" in auth.title.lowercase())
        assertEquals("FTP-LIST-550", path.diagnosticCode)
        assertTrue("katalogo" in path.title.lowercase())
    }

    @Test
    fun unknownFailureUsesOnlySafeTypeBasedDiagnostic() {
        val marker = "TOP_SECRET_MARKER"
        val shown = RemoteErrorPresenter.present(
            NetworkProtocol.FTP,
            RemoteOperation.CONNECT,
            IllegalStateException(marker),
        )
        val visible = listOf(shown.title, shown.detail, shown.suggestion, shown.diagnosticCode).joinToString(" ")

        assertFalse(marker in visible)
        assertTrue(shown.diagnosticCode.startsWith("FTP-CONNECT-"))
    }
}
