package com.wangzan101.mika.cap.phone.mcp

import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.PhoneServices
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.display.AgentDisplay
import com.wangzan101.mika.cap.phone.display.LazyAgentDisplay
import com.wangzan101.mika.core.mcp.McpPhoneSession
import com.wangzan101.mika.core.mcp.PhoneControlError
import com.wangzan101.mika.core.uistate.AppAccessScreenPort
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.ScreenWakeLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Computer-driven phone port. Reuses the normal helper and access boundary, without creating a model or TaskRun. */
object AndroidMcpPhone {
    suspend fun open(context: Context): McpPhoneSession = withContext(Dispatchers.IO) {
        val app = context.applicationContext as MikaApp
        if (Build.VERSION.SDK_INT < 34) throw PhoneControlError("unsupported_android", "Direct background control requires Android 14 or later for independent display focus.")
        fun checkUnlocked() {
            if (app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                throw PhoneControlError("phone_locked", "Unlock the phone before using direct phone control.")
        }
        checkUnlocked()
        if (app.taskState.busy.value) throw PhoneControlError("phone_busy", "A local Mika task is running. Wait for it to finish before opening a phone session.")
        val phone = PhoneServices.of(app)
        val connection = phone.sharedShell.acquire()
        val awake = if (FeatureFlags.get(app).keepScreenOn()) ScreenWakeLock(app) else null
        var display: AgentDisplay? = null
        try {
            // Match local phone tasks: keep the unlocked device interactive while this bounded lease is active.
            // This allows normal dimming and never wakes or unlocks a manually locked phone.
            awake?.acquire()
            val shell = withTimeoutOrNull(8_000) { connection.shell.filterNotNull().first() }
                ?: throw PhoneControlError("helper_unavailable", "Start Mika's phone helper using its computer startup script, or pair wireless debugging on the phone.")
            val a11y = withTimeoutOrNull(8_000) { ScreenAccessibility.instance.filterNotNull().first() }
                ?: throw PhoneControlError("accessibility_unavailable", "Enable Mika's accessibility service on the phone.")
            val metrics = DisplayMetrics().also {
                @Suppress("DEPRECATION")
                app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
            }
            // Cancellation cannot discard a display between successful allocation and recording ownership.
            val made = withContext(NonCancellable) {
                AgentDisplay(shell, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi).also { display = it }
            }
            val flags = app.getSystemService(DisplayManager::class.java).getDisplay(made.displayId)?.flags ?: 0
            if (flags and (1 shl 14) == 0) throw PhoneControlError("independent_focus_unavailable", "This system cannot give the background display its own focus.")
            val lazyDisplay = LazyAgentDisplay(metrics.widthPixels, metrics.heightPixels) { made }.also { it.get() }
            val raw = AndroidScreen(a11y, shell, lazyDisplay, checkOtherTasks = true, appAccess = app.appAccess)
            val guarded = AppAccessScreenPort(raw, app.appAccess, raw::accessPackages,
                protectedUi = { app.appAccess.sensitiveUi }, ownPackage = app.packageName,
                windowToken = { ScreenAccessibility.windowGeneration() })
            object : McpPhoneSession {
                override val displayId = made.displayId
                override val screen = guarded
                override suspend fun checkReady() {
                    checkUnlocked()
                    if (!shell.asBinder().isBinderAlive) throw PhoneControlError("helper_disconnected", "Phone helper disconnected. Close this session and reconnect.")
                    if (ScreenAccessibility.instance.value == null) throw PhoneControlError("accessibility_unavailable", "Mika's accessibility service is not running.")
                    if (app.taskState.busy.value) throw PhoneControlError("phone_busy", "A local Mika task is running. Direct input is paused until it finishes.")
                }
                override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
                    try { made.release() } finally {
                        try { awake?.release() } finally { phone.sharedShell.release() }
                    }
                }
            }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                try { display?.release() } finally {
                    try { awake?.release() } finally { phone.sharedShell.release() }
                }
            }
            throw e
        }
    }
}
