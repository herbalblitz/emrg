package com.emrg.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/**
 * The shared contract between this app and `emrg/gateway/`.
 *
 * The gateway's Python side states the same version in
 * `emrg/gateway/__init__.py`; `tests/gateway/test_android_contract.py` reads
 * both files and fails when they drift, so the pair cannot disagree about a
 * frame an older app cannot read.
 */
object GatewayProtocol {
    const val API_VERSION = "1.0"
    const val MIN_API_VERSION = "1.0"

    const val PAIR_PATH = "/v1/pair"
    const val SOCKET_PATH = "/v1/ws"

    val CAPABILITIES = listOf(
        "chat.stream", "tools.monitor", "sessions", "memory",
        "files.read", "files.write", "terminal", "sandbox",
        "rant", "evolution", "tasks", "skills", "logs",
        "config", "devices",
    )

    /** Host-lifecycle frames the gateway never forwards. Kept here so the app
     *  can explain the refusal instead of showing a mystery error. */
    val HOST_LIFECYCLE = setOf("shutdown", "init_auto_evolve")

    /** Every slash command the desktop client implements. The registry is the
     *  app's help screen and its parser, so a command cannot be listed and
     *  unhandled at the same time. */
    val SLASH_COMMANDS = listOf(
        "/resume", "/sessions", "/clear", "/compact", "/memory", "/rename",
        "/model", "/sandbox", "/rant", "/help", "/image", "/delete",
        "/rewind", "/trigger", "/task-session", "/skills", "/version",
    )

    fun rid(): String = "and-" + UUID.randomUUID().toString().take(12)

    fun frame(type: String, rid: String? = null, fields: JsonObject = JsonObject(emptyMap())): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive(type))
            if (rid != null) put("rid", JsonPrimitive(rid))
            fields.forEach { (key, value) -> put(key, value) }
        }

    fun daemon(type: String, fields: JsonObject = JsonObject(emptyMap())): JsonObject = frame(type, null, fields)

    fun compatible(version: String?): Boolean {
        val mine = API_VERSION.substringBefore('.').toIntOrNull() ?: return false
        val theirs = version?.substringBefore('.')?.toIntOrNull() ?: return false
        return mine == theirs
    }
}

val EmrgJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

fun JsonElement?.asObject(): JsonObject? = this?.let { runCatching { it.jsonObject }.getOrNull() }
fun JsonElement?.asArray(): JsonArray? = this?.let { runCatching { it.jsonArray }.getOrNull() }
fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()
fun JsonObject.arr(key: String): JsonArray? = this[key].asArray()
fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull
fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
fun JsonObject.has(key: String): Boolean = this[key] != null && this[key] !is JsonNull

fun JsonPrimitive.contentOrNull(): String? = content

fun field(key: String, value: String?): Pair<String, JsonElement> = key to JsonPrimitive(value)
fun field(key: String, value: Number?): Pair<String, JsonElement> = key to JsonPrimitive(value)
fun field(key: String, value: Boolean): Pair<String, JsonElement> = key to JsonPrimitive(value)

fun jsonOf(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())
