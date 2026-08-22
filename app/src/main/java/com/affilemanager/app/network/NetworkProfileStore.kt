package com.affilemanager.app.network

import android.content.Context
import com.affilemanager.app.security.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class NetworkProfileStore(
    context: Context,
    private val credentialVault: CredentialVault,
) {
    companion object {
        private const val PREFS = "network_profiles_v1"
        private const val KEY_PROFILES = "profiles"
        private const val MAX_PROFILES = 100
    }

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun list(): List<NetworkProfile> = withContext(Dispatchers.IO) {
        readRecords().map(ProfileRecord::profile).sortedBy { it.name.lowercase() }
    }

    suspend fun save(
        profile: NetworkProfile,
        newPassword: CharArray?,
        newPrivateKeyPem: CharArray? = null,
    ): Result<NetworkProfile> = withContext(Dispatchers.IO) {
        try {
            runCatching {
                val records = readRecords().toMutableList()
                val normalized = NetworkProfileRules.normalize(profile)
                    .copy(id = profile.id.ifBlank { UUID.randomUUID().toString() })
                NetworkProfileRules.validate(normalized)
                val existingIndex = records.indexOfFirst { it.profile.id == normalized.id }
                val encryptedSecret = when {
                    (newPassword?.isNotEmpty() == true || newPrivateKeyPem?.isNotEmpty() == true) -> {
                        val password = newPassword ?: CharArray(0)
                        val privateKey = newPrivateKeyPem ?: CharArray(0)
                        require(privateKey.size <= 1_048_576) { "Privatus SSH raktas per didelis" }
                        val payload = JSONObject()
                            .put("v", 2)
                            .put("password", password.concatToString())
                            .put("privateKeyPem", privateKey.concatToString())
                            .toString()
                            .toCharArray()
                        try {
                            credentialVault.seal(payload)
                        } finally {
                            password.fill('\u0000')
                            privateKey.fill('\u0000')
                            payload.fill('\u0000')
                        }
                    }
                    existingIndex >= 0 -> records[existingIndex].encryptedSecret
                    else -> throw IllegalArgumentException("Reikalingas slaptažodis arba privatus SSH raktas")
                }
                val record = ProfileRecord(normalized, encryptedSecret)
                if (existingIndex >= 0) records[existingIndex] = record else records += record
                require(records.size <= MAX_PROFILES) { "Pasiektas $MAX_PROFILES profilių limitas" }
                writeRecords(records)
                normalized
            }
        } finally {
            newPassword?.fill('\u0000')
            newPrivateKeyPem?.fill('\u0000')
        }
    }

    suspend fun updateSftpFingerprint(id: String, fingerprint: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(fingerprint.startsWith("SHA256:")) { "Netinkamas SSH rakto atspaudas" }
            val records = readRecords().toMutableList()
            val index = records.indexOfFirst { it.profile.id == id }
            require(index >= 0) { "Profilis neberastas" }
            val old = records[index]
            records[index] = old.copy(
                profile = old.profile.copy(
                    expectedHostKeySha256 = fingerprint,
                    allowFirstUseTrust = false,
                ),
            )
            writeRecords(records)
        }
    }

    suspend fun secret(id: String): Result<ConnectionSecret> = withContext(Dispatchers.IO) {
        runCatching {
            val record = readRecords().firstOrNull { it.profile.id == id }
                ?: throw IllegalArgumentException("Profilis neberastas")
            val plain = credentialVault.open(record.encryptedSecret)
            try {
                val raw = plain.concatToString()
                if (raw.startsWith("{")) {
                    val json = JSONObject(raw)
                    ConnectionSecret(
                        password = json.optString("password").toCharArray(),
                        privateKeyPem = json.optString("privateKeyPem").toCharArray(),
                    )
                } else {
                    ConnectionSecret(plain.copyOf())
                }
            } finally {
                plain.fill('\u0000')
            }
        }
    }

    suspend fun remove(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val records = readRecords()
            val updated = records.filterNot { it.profile.id == id }
            require(updated.size != records.size) { "Profilis neberastas" }
            writeRecords(updated)
        }
    }

    private fun readRecords(): List<ProfileRecord> {
        val raw = preferences.getString(KEY_PROFILES, "[]") ?: "[]"
        val array = JSONArray(raw)
        require(array.length() <= MAX_PROFILES) { "Profilio saugykla viršijo ribą" }
        return (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            val protocol = NetworkProtocol.valueOf(json.getString("protocol"))
            ProfileRecord(
                profile = NetworkProfile(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    protocol = protocol,
                    host = json.getString("host"),
                    port = json.getInt("port"),
                    username = json.optString("username"),
                    basePath = json.optString("basePath", "/"),
                    domain = json.optString("domain"),
                    smbShare = json.optString("smbShare"),
                    expectedHostKeySha256 = json.optString("expectedHostKeySha256").ifBlank { null },
                    allowFirstUseTrust = json.optBoolean("allowFirstUseTrust", false),
                    webDavUseTls = json.optBoolean("webDavUseTls", true),
                ),
                encryptedSecret = json.getString("encryptedSecret"),
            )
        }
    }

    private fun writeRecords(records: List<ProfileRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            val profile = record.profile
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("protocol", profile.protocol.name)
                    .put("host", profile.host)
                    .put("port", profile.port)
                    .put("username", profile.username)
                    .put("basePath", profile.basePath)
                    .put("domain", profile.domain)
                    .put("smbShare", profile.smbShare)
                    .put("expectedHostKeySha256", profile.expectedHostKeySha256 ?: "")
                    .put("allowFirstUseTrust", profile.allowFirstUseTrust)
                    .put("webDavUseTls", profile.webDavUseTls)
                    .put("encryptedSecret", record.encryptedSecret),
            )
        }
        check(preferences.edit().putString(KEY_PROFILES, array.toString()).commit()) { "Profilio įrašyti nepavyko" }
    }

    private data class ProfileRecord(
        val profile: NetworkProfile,
        val encryptedSecret: String,
    )
}
