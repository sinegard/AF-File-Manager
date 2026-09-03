package com.affilemanager.app.network

import com.jcraft.jsch.JSchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class RemoteErrorPresenterTest {
    @Test
    fun webDavHttpFailuresKeepStatusAndOfferProtocolSpecificHelp() {
        for (status in listOf(200, 401, 403, 404, 405, 409, 412, 423, 429, 500, 507)) {
            val shown = RemoteErrorPresenter.present(NetworkProtocol.WEBDAV, RemoteOperation.CONNECT, WebDavHttpException(status))
            assertEquals("WEBDAV-CONNECT-HTTP-$status", shown.diagnosticCode)
            assertEquals("HTTP $status", shown.detail)
            assertTrue(shown.suggestion.isNotBlank())
            assertFalse(shown.diagnosticCode.endsWith("UNEXPECTED"))
        }
        val wrongPath = RemoteErrorPresenter.present(NetworkProtocol.WEBDAV, RemoteOperation.CONNECT, WebDavHttpException(405))
        assertTrue(wrongPath.suggestion.contains("/dav/"))
    }

    @Test
    fun webDavUnsafeRedirectHasStableNonSecretDiagnostic() {
        val shown = RemoteErrorPresenter.present(
            NetworkProtocol.WEBDAV, RemoteOperation.LIST, WebDavRedirectException(WebDavRedirectFailure.UNSAFE),
        )
        assertEquals("WEBDAV-LIST-UNSAFE-REDIRECT", shown.diagnosticCode)
        assertTrue(shown.detail.contains("nebuvo persiųsti"))
    }

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
    fun jschFailureUsesStableDiagnosticWithoutEchoingItsMessage() {
        val marker = "TOP_SECRET_MARKER"
        val shown = RemoteErrorPresenter.present(
            NetworkProtocol.SFTP,
            RemoteOperation.CONNECT,
            JSchException(marker),
        )
        val visible = listOf(shown.title, shown.detail, shown.suggestion, shown.diagnosticCode).joinToString(" ")

        assertEquals("SFTP-CONNECT-SSH", shown.diagnosticCode)
        assertFalse(marker in visible)
    }

    @Test
    fun unknownFailureUsesStableDiagnosticIndependentOfExceptionClassName() {
        val marker = "TOP_SECRET_MARKER"
        val shown = RemoteErrorPresenter.present(
            NetworkProtocol.FTP,
            RemoteOperation.CONNECT,
            IllegalStateException(marker),
        )
        val visible = listOf(shown.title, shown.detail, shown.suggestion, shown.diagnosticCode).joinToString(" ")

        assertFalse(marker in visible)
        assertEquals("FTP-CONNECT-UNEXPECTED", shown.diagnosticCode)
        assertFalse("ILLEGALSTATE" in shown.diagnosticCode)
    }
}
