package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.mcp.McpPhoneTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.Base64

/** CLI adapter for the same direct-control use cases exposed over MCP. Authentication happens before routing. */
class RemotePhoneApi(private val phone: McpPhoneTools) {
    fun handle(req: HttpRequest): HttpResponse {
        if (req.method != "POST") return RemoteTaskApi.error(405, "method_not_allowed", "Use POST", mapOf("Allow" to "POST"))
        if (req.query.isNotEmpty()) return RemoteTaskApi.error(400, "invalid_argument", "Phone endpoints accept no query parameters")
        if (req.header("content-type")?.substringBefore(';')?.trim()?.lowercase() != "application/json")
            return RemoteTaskApi.error(415, "unsupported_media_type", "Content-Type must be application/json")
        val name = McpPhoneTools.PREFIX + req.path.removePrefix("$ROOT/")
        if (!phone.has(name)) return RemoteTaskApi.error(404, "not_found", "No such phone endpoint")
        val args = runCatching { Json.parseToJsonElement(req.body.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: return RemoteTaskApi.error(400, "invalid_argument", "Body must be a JSON object")
        return try {
            val result = runBlocking { phone.call(name, args) }
            if (result.data["ok"]?.jsonPrimitive?.booleanOrNull == false) {
                val code = result.data.getValue("error").jsonPrimitive.content
                val status = when (code) {
                    "invalid_arguments" -> 400
                    "session_not_found" -> 404
                    "app_blocked", "app_access_protected" -> 403
                    "helper_unavailable", "helper_disconnected", "accessibility_unavailable" -> 503
                    else -> 409
                }
                RemoteTaskApi.error(status, code, result.data.getValue("message").jsonPrimitive.content)
            } else HttpResponse.json(200, buildJsonObject {
                result.data.forEach { (k, v) -> put(k, v) }
                result.png?.let { png -> putJsonObject("image") {
                    put("mime_type", "image/png"); put("data", Base64.getEncoder().encodeToString(png))
                } }
            }.toString())
        } catch (_: Exception) {
            // Do not suggest replaying an input whose outcome might be unknown.
            RemoteTaskApi.error(503, "phone_operation_failed", "Phone operation did not return a result. Read the screen before deciding whether to retry input.")
        }
    }
    companion object { const val ROOT = "/api/v1/phone" }
}
