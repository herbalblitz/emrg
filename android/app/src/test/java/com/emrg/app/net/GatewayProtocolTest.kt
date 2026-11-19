package com.emrg.app.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

class GatewayProtocolTest {

    @Test
    fun api_version_is_one_dot_zero() {
        assertEquals("1.0", GatewayProtocol.API_VERSION)
        assertEquals("1.0", GatewayProtocol.MIN_API_VERSION)
    }

    @Test
    fun compatible_accepts_same_major() {
        assertTrue(GatewayProtocol.compatible("1.0"))
        assertTrue(GatewayProtocol.compatible("1.7"))
        assertFalse(GatewayProtocol.compatible("2.0"))
    }

    @Test
    fun slash_commands_are_all_required() {
        val required = listOf(
            "/resume", "/sessions", "/clear", "/compact", "/memory", "/rename",
            "/model", "/sandbox", "/rant", "/help", "/image", "/delete",
            "/rewind", "/trigger", "/task-session", "/skills", "/version",
        )
        for (command in required) {
            assertTrue(command in GatewayProtocol.SLASH_COMMANDS, "missing $command")
        }
    }

    @Test
    fun frame_includes_type_and_rid() {
        val frame = GatewayProtocol.frame("gw_ping", rid = "r1")
        assertNotNull(frame["type"])
        assertEquals("gw_ping", frame["type"]!!.jsonPrimitive.content)
        assertEquals("r1", frame["rid"]!!.jsonPrimitive.content)
    }

    @Test
    fun daemon_frame_has_no_rid() {
        val frame = GatewayProtocol.daemon("task", jsonOf("prompt" to "hi"))
        assertEquals("task", frame["type"]!!.jsonPrimitive.content)
        assertTrue(frame["rid"] == null)
    }

    @Test
    fun rid_is_unique() {
        assertNotEquals(GatewayProtocol.rid(), GatewayProtocol.rid())
    }
}
