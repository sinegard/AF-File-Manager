package com.affilemanager.app.transfer

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

/** Real sockets, no client UI assumptions: even someone with the code cannot write without consent. */
class NearbySenderConsentHttpTest {
    @get:Rule val temporary = TemporaryFolder()
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val address = InetAddress.getLoopbackAddress()

    @Test fun validMaximumUnicodeCodeAndDeviceNameFitTheBoundedPhoneHandshakeWithoutGrantingConsent() {
        val root = temporary.newFolder("unicode")
        val code = "界".repeat(LanTransferOptions.MAX_PASSWORD_LENGTH)
        LanHttpServer(root, address, requestedCode = code, requireSenderApproval = true).use { server ->
            val body = FormBody.Builder().add("code", code).add("client", NearbySenderApproval.clientId()).add("name", "名".repeat(64)).build()
            assertTrue(body.contentLength() > 1_024 && body.contentLength() <= 2_048)
            val request = Request.Builder().url("http://127.0.0.1:${server.start().port}/login")
                .header("X-AF-Sender-Approval", "1").post(body).build()
            client.newCall(request).execute().use { assertEquals(202, it.code); assertNull(it.header("Set-Cookie")) }
            assertEquals(64, server.pendingNearbySenders().single().name.length)
            assertTrue(root.list()!!.isEmpty())
        }
    }
    private class Reply(val status: Int, val headers: okhttp3.Headers, val body: String) {
        val ticket get() = headers["X-AF-Approval-Request"]
        val cookie get() = headers["Set-Cookie"]?.substringBefore(';')
        override fun toString() = "HttpResult($status)"
    }
    private fun request(port: Int, path: String, body: String? = null, cookie: String? = null, ticket: String? = null): Reply {
        val request = Request.Builder().url("http://127.0.0.1:$port$path")
            .apply { if (body != null) post(body.toRequestBody()); cookie?.let { header("Cookie", it) }; ticket?.let { header("X-AF-Approval-Request", it) } }.build()
        return client.newCall(request).execute().use { Reply(it.code, it.headers, it.body!!.string()) }
    }
    private fun login(port: Int, modern: Boolean = true): Reply {
        val request = Request.Builder().url("http://127.0.0.1:$port/login")
            .apply { if (modern) header("X-AF-Sender-Approval", "1") }
            .post(FormBody.Builder().add("code", "fixture-private-code").add("client", NearbySenderApproval.clientId())
                .add("name", "Owned test sender").build()).build()
        return client.newCall(request).execute().use { Reply(it.code, it.headers, it.body!!.string()) }
    }

    @Test fun codePendingTicketDecisionIdAndForgedCookieCannotBypassConsentOrCreateAnyFiles() {
        val root = temporary.newFolder("no-consent")
        root.resolve("original.txt").writeText("preserve original")
        LanHttpServer(root, address, requestedCode = "fixture-private-code", requireSenderApproval = true).use { server ->
            val port = server.start().port
            assertEquals(400, login(port, modern = false).status)
            val pending = login(port)
            assertEquals(202, pending.status); assertNull(pending.cookie)
            val id = server.pendingNearbySenders().single().id
            assertEquals(202, request(port, "/nearby/approval", "", ticket = pending.ticket).status)
            assertEquals(410, request(port, "/nearby/approval", "", ticket = "a".repeat(64)).status)
            for (cookie in listOf(null, "af_session=fabricated", "af_session=$id", "af_session=${pending.ticket}")) {
                for ((path, body) in listOf(
                    "/upload?name=unexpected.txt" to "unapproved bytes", "/mkdir?path=unexpected" to "",
                    "/nearby/manifest" to "{}", "/nearby/peer" to "invalid", "/nearby/message" to "message",
                    "/nearby/group/join" to "invalid", "/nearby/disconnect" to "", "/nearby/cancel" to "",
                    "/nearby/approve" to id,
                )) assertEquals("Must not authorize $path", 401, request(port, path, body, cookie).status)
                for (path in listOf("/", "/list", "/download?path=original.txt")) assertEquals(401, request(port, path, cookie = cookie).status)
            }
            assertFalse(server.decideNearbySender(pending.ticket!!, true))
            assertEquals(setOf("original.txt"), root.list()!!.toSet())
            assertEquals("preserve original", root.resolve("original.txt").readText())
        }
    }

    @Test fun onlyLocalApprovalEnablesExactUploadNotBrowsingAndNoGrantSurvivesAReceiveRestart() {
        val root = temporary.newFolder("approved")
        root.resolve("private.txt").writeText("not shared for download")
        var granted: String? = null
        LanHttpServer(root, address, requestedCode = "fixture-private-code", requireSenderApproval = true).use { server ->
            val port = server.start().port
            val pending = login(port)
            assertFalse(root.resolve("received.txt").exists())
            assertTrue(server.decideNearbySender(server.pendingNearbySenders().single().id, true))
            val accepted = request(port, "/nearby/approval", "", ticket = pending.ticket)
            assertEquals(200, accepted.status); assertNotNull(accepted.cookie); granted = accepted.cookie
            assertEquals(201, request(port, "/upload?name=received.txt", "byte-exact allowed content", accepted.cookie).status)
            assertEquals("byte-exact allowed content", root.resolve("received.txt").readText())
            for (path in listOf("/", "/list", "/download?path=private.txt")) assertEquals(403, request(port, path, cookie = granted).status)
        }
        LanHttpServer(root, address, requestedCode = "fixture-private-code", requireSenderApproval = true).use { next ->
            val port = next.start().port
            assertEquals(401, request(port, "/upload?name=stale.txt", "no", granted).status)
            assertEquals(202, login(port).status)
            assertFalse(root.resolve("stale.txt").exists())
        }
    }

    @Test fun rejectCancelExpiryAndLateLocalDecisionsNeverTurnIntoAWriteGrant() {
        val root = temporary.newFolder("denied")
        val time = AtomicLong(0)
        LanHttpServer(root, address, requestedCode = "fixture-private-code", requireSenderApproval = true,
            approvalNowMillis = time::get).use { server ->
            val port = server.start().port
            for (outcome in listOf("reject", "cancel", "expire")) {
                val pending = login(port)
                val id = server.pendingNearbySenders().single().id
                when (outcome) {
                    "reject" -> assertTrue(server.decideNearbySender(id, false))
                    "cancel" -> assertEquals(200, request(port, "/nearby/approval/cancel", "", ticket = pending.ticket).status)
                    else -> time.addAndGet(NearbySenderApproval.PENDING_MILLIS)
                }
                val result = request(port, "/nearby/approval", "", ticket = pending.ticket)
                assertEquals(if (outcome == "expire") 410 else 403, result.status); assertNull(result.cookie)
                assertFalse(server.decideNearbySender(id, true))
                assertEquals(401, request(port, "/upload?name=denied.txt", "no", "af_session=${pending.ticket}").status)
            }
            assertTrue(root.list()!!.isEmpty())
        }
    }

    @Test fun eachGroupSenderNeedsIndependentConsentAndRemovalRevokesItsCookieOnly() {
        val root = temporary.newFolder("group")
        val groupAddress = LanTestNetwork.privateAddress()
        // Use the actual private socket IP for group pairing identity validation.
        val local = OkHttpClient.Builder().followRedirects(false).build()
        LanHttpServer(root, groupAddress, requestedCode = "fixture-private-code", groupMode = true, requireSenderApproval = true).use { server ->
            val port = server.start().port
            fun call(path: String, data: String? = null, cookie: String? = null, ticket: String? = null, login: Boolean = false): Reply {
                val req = Request.Builder().url("http://${groupAddress.hostAddress}:$port$path").apply {
                    if (login) header("X-AF-Sender-Approval", "1").post(FormBody.Builder().add("code", "fixture-private-code")
                        .add("client", NearbySenderApproval.clientId()).add("name", "Group sender").build())
                    else if (data != null) post(data.toRequestBody())
                    cookie?.let { header("Cookie", it) }; ticket?.let { header("X-AF-Approval-Request", it) }
                }.build()
                return local.newCall(req).execute().use { Reply(it.code, it.headers, it.body!!.string()) }
            }
            val first = call("/login", login = true); val firstId = server.pendingNearbySenders().single().id
            val second = call("/login", login = true)
            assertTrue(server.decideNearbySender(firstId, true))
            val firstCookie = call("/nearby/approval", "", ticket = first.ticket).cookie!!
            assertEquals(202, call("/nearby/approval", "", ticket = second.ticket).status)
            assertEquals(401, call("/upload?name=unapproved.txt", "no", "af_session=${second.ticket}").status)
            assertTrue(server.decideNearbySender(server.pendingNearbySenders().single().id, true))
            val secondCookie = call("/nearby/approval", "", ticket = second.ticket).cookie!!
            val peer = NearbyPairing.create(groupAddress.hostAddress, 25001, "87654321", "Approved first")
            assertEquals(200, call("/nearby/group/join", peer.encoded(), firstCookie).status)
            assertTrue(server.removeGroupMember(peer))
            assertEquals(401, call("/upload?name=removed.txt", "no", firstCookie).status)
            assertEquals(201, call("/upload?name=second.txt", "second approved", secondCookie).status)
            assertEquals(setOf("second.txt"), root.list()!!.toSet())
        }
    }
}
