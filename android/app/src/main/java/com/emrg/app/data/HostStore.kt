package com.emrg.app.data

import android.content.Context
import com.emrg.app.model.HostProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Paired hosts, minus their tokens.
 *
 * The fingerprint is public data (it is what the host prints for comparison),
 * so it lives here. The bearer token is kept in Android Keystore through
 * [com.emrg.app.security.SecureTokenStore], keyed by the host id.
 */
class HostStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("emrg_hosts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Serializable
    private data class Record(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val fingerprint: String,
        val deviceId: String = "",
        val lastUsed: Long = 0L,
    )

    fun load(): List<HostProfile> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(Record.serializer()), raw).map {
                HostProfile(it.id, it.name, it.host, it.port, it.fingerprint, it.deviceId, it.lastUsed)
            }
        }.getOrDefault(emptyList())
    }

    fun save(host: HostProfile) {
        val next = load().filterNot { it.id == host.id }.toMutableList()
        next.add(0, host)
        write(next)
    }

    fun remove(hostId: String) {
        write(load().filterNot { it.id == hostId })
    }

    fun touch(hostId: String) {
        write(load().map { if (it.id == hostId) it.copy(lastUsed = System.currentTimeMillis()) else it })
    }

    private fun write(hosts: List<HostProfile>) {
        val records = hosts.map {
            Record(it.id, it.name, it.host, it.port, it.fingerprint, it.deviceId, it.lastUsed)
        }
        prefs.edit()
            .putString(KEY, json.encodeToString(ListSerializer(Record.serializer()), records))
            .apply()
    }

    private companion object {
        const val KEY = "hosts.v1"
    }
}
