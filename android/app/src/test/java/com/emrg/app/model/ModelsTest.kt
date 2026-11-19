package com.emrg.app.model

import com.emrg.app.net.EmrgJson
import kotlin.test.Test
import kotlin.test.assertEquals

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
        // Explicit serializer, the same shape HostStore uses for its Record:
        // the reified Json.encodeToString(host) overload does not resolve in
        // this module, so pass the generated serializer directly.
        val json = EmrgJson.encodeToString(HostProfile.serializer(), host)
        val back = EmrgJson.decodeFromString(HostProfile.serializer(), json)
        assertEquals(host, back)
    }

    @Test
    fun chat_message_roles() {
        assertEquals(ChatMessage.Role.USER, ChatMessage.Role.valueOf("USER"))
    }
}
