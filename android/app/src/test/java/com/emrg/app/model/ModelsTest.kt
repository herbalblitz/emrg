package com.emrg.app.model

import com.emrg.app.net.EmrgJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.encodeToString

class ModelsTest {

    @Test
    fun host_profile_round_trips() {
        val host = HostProfile(
            id = "dev1",
            name = "Home",
            host = "192.168.1.42",
            port = 56032,
            fingerprint = "AA:BB",
            deviceId = "dev1",
        )
        val json = EmrgJson.encodeToString(host)
        val back = EmrgJson.decodeFromString<HostProfile>(json)
        assertEquals(host, back)
    }

    @Test
    fun chat_message_roles() {
        assertEquals(ChatMessage.Role.USER, ChatMessage.Role.valueOf("USER"))
    }
}
