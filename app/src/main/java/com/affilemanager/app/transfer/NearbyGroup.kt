package com.affilemanager.app.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class NearbyGroupInvite(
    val organizer: NearbyPairing,
    val groupName: String = "AF group",
) {
    fun encoded(): String = buildString {
        append(NearbyPairing.SCHEME).append("://group?")
        append("host=").append(encode(organizer.host))
        append("&port=").append(organizer.port)
        append("&code=").append(encode(organizer.code))
        append("&name=").append(encode(organizer.receiverName))
        append("&group=").append(encode(groupName.take(MAX_GROUP_NAME_LENGTH)))
    }

    companion object {
        const val MAX_GROUP_NAME_LENGTH = 64

        fun parse(payload: String): NearbyGroupInvite {
            val normalized = payload.trim()
            require(normalized.length in 1..NearbyPairing.MAX_PAYLOAD_LENGTH) { "Netinkamas grupės kodas" }
            val uri = URI(normalized)
            require(uri.scheme == NearbyPairing.SCHEME && uri.host == "group" && uri.fragment == null && uri.userInfo == null) {
                "Tai nėra AF grupės kodas"
            }
            val query = uri.rawQuery.orEmpty().split('&').take(10).associate { part ->
                decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
            }
            val groupName = query["group"].orEmpty().trim().ifBlank { "AF group" }
            require(groupName.length <= MAX_GROUP_NAME_LENGTH && groupName.none(Char::isISOControl)) { "Netinkamas grupės vardas" }
            return NearbyGroupInvite(
                organizer = NearbyPairing.create(
                    query["host"].orEmpty(),
                    query["port"]?.toIntOrNull() ?: -1,
                    query["code"].orEmpty(),
                    query["name"].orEmpty(),
                ),
                groupName = groupName,
            )
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

        private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}

data class NearbyGroupMember(
    val pairing: NearbyPairing,
    val organizer: Boolean = false,
    val messagesBlocked: Boolean = false,
)

internal object NearbyGroupCodec {
    fun encode(members: List<NearbyGroupMember>): ByteArray {
        require(members.size <= NearbyGroupDirectory.MAX_MEMBERS) { "Grupėje per daug dalyvių" }
        val array = JSONArray()
        members.forEach { member ->
            array.put(JSONObject().put("pairing", member.pairing.encoded()).put("organizer", member.organizer)
                .put("messagesBlocked", member.messagesBlocked))
        }
        return JSONObject().put("version", 1).put("members", array).toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): List<NearbyGroupMember> {
        require(bytes.size in 1..MAX_BYTES) { "Netinkamas grupės dalyvių sąrašas" }
        val root = JSONObject(bytes.toString(StandardCharsets.UTF_8))
        require(root.getInt("version") == 1) { "Nepalaikoma grupės versija" }
        val array = root.getJSONArray("members")
        require(array.length() <= NearbyGroupDirectory.MAX_MEMBERS) { "Grupėje per daug dalyvių" }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            NearbyGroupMember(NearbyPairing.parse(item.getString("pairing")), item.optBoolean("organizer", false), item.optBoolean("messagesBlocked", false))
        }.distinctBy { "${it.pairing.host}:${it.pairing.port}" }
    }

    const val MAX_BYTES = 32 * 1_024
}

internal class NearbyGroupDirectory(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val MAX_MEMBERS = 10
        private const val MEMBER_TIMEOUT_MILLIS = 30_000L
    }

    private data class Record(val pairing: NearbyPairing, var lastSeenMillis: Long)
    private val members = linkedMapOf<String, Record>()
    private val removedIdentities = hashSetOf<String>()
    private val blockedMessages = hashSetOf<String>()

    @Synchronized
    fun join(pairing: NearbyPairing): Boolean {
        prune()
        val key = pairing.key()
        require(pairing.identity() !in removedIdentities) { "Organizatorius pašalino šį telefoną iš grupės" }
        val existing = members[key]
        if (existing == null) {
            require(members.size < MAX_MEMBERS - 1) { "Grupė pilna" }
            members[key] = Record(pairing, nowMillis())
            return true
        }
        existing.lastSeenMillis = nowMillis()
        members[key] = existing.copy(pairing = pairing)
        return false
    }

    @Synchronized
    fun heartbeat(pairing: NearbyPairing): Boolean {
        prune()
        val record = members[pairing.key()] ?: return false
        record.lastSeenMillis = nowMillis()
        return true
    }

    @Synchronized
    fun leave(pairing: NearbyPairing): Boolean = members.remove(pairing.key()) != null

    /** Organizer-only local action; blocks this pairing identity for the current session. */
    @Synchronized
    fun remove(pairing: NearbyPairing): Boolean {
        val key = pairing.key()
        require(removedIdentities.size < 100) { "Grupės sesijos valdymo riba pasiekta" }
        if (members.remove(key) == null) return false
        removedIdentities += pairing.identity()
        blockedMessages -= pairing.identity()
        return true
    }

    @Synchronized
    fun setMessagesBlocked(pairing: NearbyPairing, blocked: Boolean): Boolean {
        if (members[pairing.key()]?.pairing != pairing) return false
        if (blocked) blockedMessages += pairing.identity() else blockedMessages -= pairing.identity()
        return true
    }

    @Synchronized
    fun messagesAllowed(host: String): Boolean = members.values.none {
        it.pairing.host == host && it.pairing.identity() in blockedMessages
    } && removedIdentities.none { it.startsWith("$host:") }

    @Synchronized
    fun messagesAllowed(pairing: NearbyPairing): Boolean =
        pairing.identity() !in blockedMessages && pairing.identity() !in removedIdentities

    @Synchronized
    fun memberAt(host: String): NearbyPairing? = members.values.map(Record::pairing)
        .filter { it.host == host }.singleOrNull()

    @Synchronized
    fun snapshot(organizer: NearbyPairing): List<NearbyGroupMember> {
        prune()
        return listOf(NearbyGroupMember(organizer, organizer = true)) +
            members.values.map { NearbyGroupMember(it.pairing, messagesBlocked = it.pairing.identity() in blockedMessages) }
    }

    @Synchronized
    private fun prune() {
        val minimum = nowMillis() - MEMBER_TIMEOUT_MILLIS
        members.entries.removeAll { it.value.lastSeenMillis < minimum }
        blockedMessages.retainAll(members.values.map { it.pairing.identity() }.toSet())
    }

    private fun NearbyPairing.key(): String = "$host:$port"
    private fun NearbyPairing.identity(): String = "$host:$code"
}

enum class NearbyGroupStatus { IDLE, HOSTING, JOINING, JOINED, ERROR }

data class NearbyGroupState(
    val status: NearbyGroupStatus = NearbyGroupStatus.IDLE,
    val groupName: String = "",
    val ownPairing: NearbyPairing? = null,
    val organizer: NearbyPairing? = null,
    val members: List<NearbyGroupMember> = emptyList(),
    val memberNotice: String? = null,
    val error: String? = null,
)

internal fun groupPairingConfirmed(previous: NearbyGroupState, current: NearbyGroupState): Boolean {
    if (current.status == NearbyGroupStatus.JOINED && previous.status != NearbyGroupStatus.JOINED) return true
    if (previous.status != NearbyGroupStatus.HOSTING || current.status != NearbyGroupStatus.HOSTING) return false
    val known = previous.members.mapTo(hashSetOf()) { "${it.pairing.host}:${it.pairing.port}" }
    return current.members.any { !it.organizer && "${it.pairing.host}:${it.pairing.port}" !in known }
}

object NearbyGroupController {
    private const val HEARTBEAT_MILLIS = 3_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private val _state = MutableStateFlow(NearbyGroupState())
    val state: StateFlow<NearbyGroupState> = _state.asStateFlow()
    private var polling: Job? = null
    private var cookie: String? = null

    fun host(invite: NearbyGroupInvite) {
        polling?.cancel()
        polling = null
        cookie = null
        _state.value = NearbyGroupState(
            status = NearbyGroupStatus.HOSTING,
            groupName = invite.groupName,
            ownPairing = invite.organizer,
            organizer = invite.organizer,
            members = listOf(NearbyGroupMember(invite.organizer, organizer = true)),
        )
    }

    internal fun hostMembers(invite: NearbyGroupInvite, members: List<NearbyGroupMember>) {
        val current = _state.value
        if (current.status == NearbyGroupStatus.HOSTING && current.organizer == invite.organizer) {
            _state.value = current.copy(
                members = members,
                memberNotice = membershipNotice(current.members, members) ?: current.memberNotice,
                error = null,
            )
        }
    }

    fun join(invite: NearbyGroupInvite, ownPairing: NearbyPairing) {
        require(ownPairing != invite.organizer) { "Negalima prisijungti prie savo grupės" }
        polling?.cancel()
        cookie = null
        _state.value = NearbyGroupState(
            status = NearbyGroupStatus.JOINING,
            groupName = invite.groupName,
            ownPairing = ownPairing,
            organizer = invite.organizer,
        )
        polling = scope.launch {
            try {
                val activeCookie = login(invite.organizer)
                cookie = activeCookie
                postPairing(invite.organizer, activeCookie, "/nearby/group/join", ownPairing)
                while (true) {
                    postPairing(invite.organizer, activeCookie, "/nearby/group/heartbeat", ownPairing)
                    val members = loadMembers(invite.organizer, activeCookie)
                    val current = _state.value
                    _state.value = current.copy(
                        status = NearbyGroupStatus.JOINED,
                        members = members,
                        memberNotice = if (current.status == NearbyGroupStatus.JOINED) {
                            membershipNotice(current.members, members) ?: current.memberNotice
                        } else {
                            null
                        },
                        error = null,
                    )
                    delay(HEARTBEAT_MILLIS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    status = NearbyGroupStatus.ERROR,
                    error = (error.message ?: "Prisijungti prie grupės nepavyko").take(240),
                )
            }
        }
    }

    fun leave() {
        val snapshot = _state.value
        val activeCookie = cookie
        polling?.cancel()
        polling = null
        cookie = null
        _state.value = NearbyGroupState()
        if (snapshot.status == NearbyGroupStatus.JOINED && snapshot.organizer != null && snapshot.ownPairing != null && activeCookie != null) {
            scope.launch {
                runCatching { postPairing(snapshot.organizer, activeCookie, "/nearby/group/leave", snapshot.ownPairing) }
            }
        }
    }

    internal fun sessionStopped() = leave()

    fun clearMemberNotice(notice: String) {
        _state.value.takeIf { it.memberNotice == notice }?.let { current ->
            _state.value = current.copy(memberNotice = null)
        }
    }

    private fun membershipNotice(
        previous: List<NearbyGroupMember>,
        current: List<NearbyGroupMember>,
    ): String? {
        val previousKeys = previous.mapTo(hashSetOf()) { "${it.pairing.host}:${it.pairing.port}" }
        val currentKeys = current.mapTo(hashSetOf()) { "${it.pairing.host}:${it.pairing.port}" }
        val joined = current.firstOrNull { member ->
            !member.organizer && "${member.pairing.host}:${member.pairing.port}" !in previousKeys
        }
        if (joined != null) return "${joined.pairing.receiverName} prisijungė prie grupės"
        val left = previous.firstOrNull { member ->
            !member.organizer && "${member.pairing.host}:${member.pairing.port}" !in currentKeys
        }
        return left?.let { "${it.pairing.receiverName} paliko grupę" }
    }

    private fun login(organizer: NearbyPairing): String {
        val request = Request.Builder()
            .url(url(organizer, "/login"))
            .post(FormBody.Builder().add("code", organizer.code).build())
            .build()
        return client.newCall(request).execute().use { response ->
            require(response.code == 200) { "Grupės kodas atmestas (HTTP ${response.code})" }
            response.header("Set-Cookie")?.substringBefore(';')?.takeIf { it.startsWith("af_session=") }
                ?: throw IllegalStateException("Grupės sesija nesukurta")
        }
    }

    private fun postPairing(organizer: NearbyPairing, cookie: String, path: String, pairing: NearbyPairing) {
        val body = pairing.encoded().toByteArray(StandardCharsets.UTF_8).toRequestBody(null)
        val request = Request.Builder().url(url(organizer, path)).header("Cookie", cookie).post(body).build()
        client.newCall(request).execute().use { response ->
            require(response.code == 200) { "Grupės ryšys atmestas (HTTP ${response.code})" }
        }
    }

    private fun loadMembers(organizer: NearbyPairing, cookie: String): List<NearbyGroupMember> {
        val request = Request.Builder().url(url(organizer, "/nearby/group/members")).header("Cookie", cookie).get().build()
        return client.newCall(request).execute().use { response ->
            require(response.code == 200) { "Grupės sąrašo gauti nepavyko (HTTP ${response.code})" }
            val body = requireNotNull(response.body)
            val size = body.contentLength()
            require(size < 0 || size <= NearbyGroupCodec.MAX_BYTES) { "Grupės sąrašas per didelis" }
            val bytes = body.bytes()
            require(bytes.size <= NearbyGroupCodec.MAX_BYTES) { "Grupės sąrašas per didelis" }
            NearbyGroupCodec.decode(bytes)
        }
    }

    private fun url(pairing: NearbyPairing, path: String): String = "http://${pairing.host}:${pairing.port}$path"
}
