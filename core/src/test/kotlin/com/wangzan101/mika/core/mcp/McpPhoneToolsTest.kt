package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.access.AppAccessDenied
import com.wangzan101.mika.core.remote.HttpRequest
import com.wangzan101.mika.core.remote.RemoteEndpoints
import com.wangzan101.mika.core.remote.RemoteHost
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.Base64

class McpPhoneToolsTest {
    private class Phone(val fake: FakeScreen = FakeScreen()) : McpPhoneSession {
        override var screen: ScreenPort = fake
        override val displayId = 7
        var closed = 0
        var ready = true
        override suspend fun checkReady() { if (!ready) throw PhoneControlError("phone_locked", "Unlock phone") }
        override suspend fun close() { closed++ }
    }
    private fun args(vararg values: Pair<String, Any>) = buildJsonObject {
        values.forEach { (key, v) -> when (v) { is Int -> put(key, v); else -> put(key, v.toString()) } }
    }
    private suspend fun McpPhoneTools.invoke(name: String, vararg values: Pair<String, Any>) = call(McpPhoneTools.PREFIX + name, args(*values))
    private fun JsonObject.s(key: String) = getValue(key).jsonPrimitive.content
    private suspend fun McpPhoneTools.start() = invoke("open").data

    @Test fun sessionIsExclusiveAndCloseReleasesTheDisplay() = runTest {
        val made = mutableListOf<Phone>()
        val tools = McpPhoneTools({ Phone().also(made::add) })
        val first = tools.start()
        assertEquals("session_busy", tools.start().s("error"))
        assertEquals(1, made.size)
        assertEquals("session_not_found", tools.invoke("close", "session_id" to "wrong").data.s("error"))
        assertEquals(0, made[0].closed)
        assertTrue(tools.invoke("close", "session_id" to first.s("session_id")).data.getValue("closed").jsonPrimitive.boolean)
        assertEquals(1, made[0].closed)
        assertNotEquals(first.s("session_id"), tools.start().s("session_id"))
        assertEquals("session_not_found", tools.invoke("read_screen", "session_id" to first.s("session_id")).data.s("error"))
        tools.closeAll()
        assertEquals(listOf(1, 1), made.map { it.closed })
    }

    @Test fun normalizedCoordinatesAndStaleSnapshotsAreEnforcedBeforeInput() = runTest {
        val phone = Phone(); val tools = McpPhoneTools({ phone }); val open = tools.start()
        val id = open.s("session_id"); val snap = open.s("snapshot_id")
        val invalid = tools.invoke("tap", "session_id" to id, "snapshot_id" to snap, "ref" to "@e1", "x" to 4)
        assertEquals("invalid_arguments", invalid.data.s("error")); assertTrue(phone.fake.calls.isEmpty())
        val tapped = tools.invoke("tap", "session_id" to id, "snapshot_id" to snap, "x" to 500, "y" to 999)
        assertTrue(tapped.data.getValue("ok").jsonPrimitive.boolean)
        assertEquals(listOf("tapXY 540 2399"), phone.fake.calls)
        assertEquals("stale_snapshot", tools.invoke("tap", "session_id" to id, "snapshot_id" to snap, "ref" to "@e1").data.s("error"))
        assertEquals(1, phone.fake.calls.size)
        tools.invoke("tap", "session_id" to id, "snapshot_id" to tapped.data.s("snapshot_id"), "ref" to "@e1")
        assertTrue(phone.fake.calls.last().startsWith("tapRef @e1 snap"))
    }

    @Test fun unicodeInputScrollAndBackUseTheSameModelFreeSession() = runTest {
        val phone = Phone(); val tools = McpPhoneTools({ phone }); val open = tools.start(); val id = open.s("session_id")
        val typed = tools.invoke("input_text", "session_id" to id, "snapshot_id" to open.s("snapshot_id"), "text" to "中文 Mika 123")
        assertTrue(phone.fake.calls.single().endsWith("中文 Mika 123"))
        val scrolled = tools.invoke("scroll", "session_id" to id, "snapshot_id" to typed.data.s("snapshot_id"), "direction" to "down")
        assertTrue(scrolled.data.getValue("ok").jsonPrimitive.boolean)
        tools.invoke("back", "session_id" to id)
        assertEquals(listOf("scroll", "back"), phone.fake.calls.takeLast(2))
        assertEquals("normalized_0_999", typed.data.s("coordinate_space"))
    }

    @Test fun failedInputDoesNotKeepTheOldSnapshotUsable() = runTest {
        val phone = Phone(); val tools = McpPhoneTools({ phone }); val open = tools.start(); val id = open.s("session_id")
        phone.fake.result = ActionResult.Failed("ref_expired")
        assertEquals("ref_expired", tools.invoke("tap", "session_id" to id, "snapshot_id" to open.s("snapshot_id"), "ref" to "@e1").data.s("error"))
        assertEquals("stale_snapshot", tools.invoke("input_text", "session_id" to id, "snapshot_id" to open.s("snapshot_id"), "text" to "test").data.s("error"))
        assertEquals(1, phone.fake.calls.size)
    }

    @Test fun protectedScreensAndAppDenialsAreNotReturnedAsImages() = runTest {
        val phone = Phone(); val tools = McpPhoneTools({ phone }); val id = tools.start().s("session_id")
        phone.screen = object : ScreenPort by phone.fake { override suspend fun isProtected() = true }
        val shot = tools.invoke("screenshot", "session_id" to id)
        assertNull(shot.png); assertEquals("screen_protected", shot.data.s("error"))
        phone.screen = object : ScreenPort by phone.fake {
            override suspend fun snapshot() = throw AppAccessDenied("app_blocked", "com.x")
            override suspend fun screenshotPng(): ByteArray? = error("must not capture")
        }
        val blocked = tools.invoke("screenshot", "session_id" to id)
        assertNull(blocked.png); assertEquals("app_blocked", blocked.data.s("error"))
    }

    @Test fun leasesExpireAndFailedSetupDoesNotLeakTheSession() = runTest {
        var now = 0L; val made = mutableListOf<Phone>()
        val tools = McpPhoneTools({ Phone().also(made::add) }, clock = { now }, idleMs = 100)
        val id = tools.start().s("session_id")
        now = 101; tools.closeIdle()
        assertEquals(1, made.single().closed)
        assertEquals("session_not_found", tools.invoke("read_screen", "session_id" to id).data.s("error"))
        val bad = Phone().apply { ready = false }
        val broken = McpPhoneTools({ bad })
        assertEquals("phone_locked", broken.start().s("error")); assertEquals(1, bad.closed)
        bad.ready = true
        assertTrue(broken.start().getValue("ok").jsonPrimitive.boolean)
    }

    @Test fun concurrentMutationsAreSerializedAndOnlyOneCanUseASnapshot() = runTest {
        val phone = Phone()
        phone.screen = object : ScreenPort by phone.fake {
            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                delay(10); return phone.fake.tapRef(ref, snapshotId)
            }
        }
        val tools = McpPhoneTools({ phone }); val open = tools.start()
        val values = arrayOf("session_id" to open.s("session_id"), "snapshot_id" to open.s("snapshot_id"), "ref" to "@e1")
        val a = async { tools.invoke("tap", *values).data }
        val b = async { tools.invoke("tap", *values).data }
        assertEquals(1, listOf(a.await(), b.await()).count { it["ok"]?.jsonPrimitive?.boolean == true })
        assertEquals(1, phone.fake.calls.size)
    }

    @Test fun stoppingAccessRevokesOldSessionsWithoutClosingANewConnection() = runTest {
        var available = false
        val made = mutableListOf<Phone>()
        val tools = McpPhoneTools({ Phone().also(made::add) }, available = { available })
        assertEquals("connection_closed", tools.start().s("error")); assertTrue(made.isEmpty())
        available = true
        val old = tools.start()
        available = false
        val cutoff = tools.revoke()
        assertEquals("connection_closed", tools.invoke("read_screen", "session_id" to old.s("session_id")).data.s("error"))
        available = true
        val current = tools.start() // Reopening releases the revoked display first.
        assertEquals(1, made[0].closed)
        tools.closeRevoked(cutoff) // A late cleanup from the previous listener cannot close this session.
        assertEquals(0, made[1].closed)
        assertTrue(tools.invoke("read_screen", "session_id" to current.s("session_id")).data.getValue("ok").jsonPrimitive.boolean)
        assertEquals("session_not_found", tools.invoke("read_screen", "session_id" to old.s("session_id")).data.s("error"))
    }

    private fun request(path: String, body: String, method: String = "POST", token: Boolean = true) = HttpRequest(method, path,
        mapOf("content-type" to "application/json", "accept" to "application/json") + if (token) mapOf("authorization" to "Bearer test") else emptyMap(),
        body.toByteArray(), InetAddress.getLoopbackAddress())

    @Test fun mcpNativeImagesAndCliApiShareTheSessionButRelayCannotAccessIt() {
        val phone = Phone(); val tools = McpPhoneTools({ phone })
        val endpoints = RemoteEndpoints(FakeRemoteTasks(), "test", phone = tools)
        fun handle(path: String, body: String) = Json.parseToJsonElement(endpoints.handle(request(path, body)).body.toString(Charsets.UTF_8)).jsonObject
        val opened = handle("/api/v1/phone/open", "{}")
        val id = opened.s("session_id")
        val response = handle("/mcp", """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"mika_phone_screenshot","arguments":{"session_id":"$id"}}}""")
        val result = response.getValue("result").jsonObject
        val image = result.getValue("content").jsonArray.single { it.jsonObject["type"]?.jsonPrimitive?.content == "image" }.jsonObject
        assertArrayEquals(phone.fake.png, Base64.getDecoder().decode(image.s("data")))
        val cliShot = handle("/api/v1/phone/screenshot", """{"session_id":"$id"}""")
        assertArrayEquals(phone.fake.png, Base64.getDecoder().decode(cliShot.getValue("image").jsonObject.s("data")))
        val remoteList = endpoints.handle(request("/mcp", """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""), phoneAccess = false)
        assertFalse(remoteList.body.toString(Charsets.UTF_8).contains("mika_phone_"))
        assertEquals(404, endpoints.handle(request("/api/v1/phone/read_screen", """{"session_id":"$id"}"""), phoneAccess = false).status)
        assertTrue(handle("/api/v1/phone/close", """{"session_id":"$id"}""").getValue("closed").jsonPrimitive.boolean)
        assertEquals(1, phone.closed)
    }

    @Test fun authenticationRunsBeforeDirectControlAndLegacyConnectionsRemainTaskOnly() {
        var opened = 0
        val tools = McpPhoneTools({ opened++; Phone() })
        val tasks = FakeRemoteTasks()
        val host = RemoteHost(tasks, { "test" }, 0, false, "test", endpoints = RemoteEndpoints(tasks, "test", phone = tools))
        assertEquals(401, host.handle(request("/api/v1/phone/open", "{}", token = false)).status)
        assertEquals(0, opened)
        assertEquals(200, host.handle(request("/api/v1/phone/open", "{}")).status)
        assertEquals(1, opened)
        val legacy = RemoteEndpoints(tasks, "test")
        assertEquals(404, legacy.handle(request("/api/v1/phone/open", "{}")).status)
    }
}
