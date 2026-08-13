package moe.n4tsu.dextop

import android.content.ContentResolver
import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceView

internal interface VirtualDisplayBackend {
    fun currentDisplayIds(): Set<Int>
    fun requestDisplay(width: Int, height: Int, density: Int, secure: Boolean, decorations: Boolean)
    fun findCreatedDisplay(previousIds: Set<Int>): Display?
    fun clearRequest()
}

internal interface MirrorAttachBackend {
    val id: String
    fun isSupported(): Boolean
    fun createLayer(displayId: Int): SurfaceControl
}

internal class DisplayMirrorBackend(
    private val context: Context,
    private val resolver: ContentResolver,
    private val displayManager: DisplayManager,
    private val privilegedAccess: PrivilegedAccess,
    private val environment: DesktopEnvironment
) : VirtualDisplayBackend {
    private var attachedLayer: SurfaceControl? = null
    private val attachBackends: Map<String, MirrorAttachBackend> by lazy {
        listOf(WindowManagerMirrorBackend(privilegedAccess), SurfaceControlMirrorBackend())
            .associateBy { it.id }
    }

    override fun currentDisplayIds(): Set<Int> = displayManager.displays.mapTo(mutableSetOf()) { it.displayId }

    override fun requestDisplay(width: Int, height: Int, density: Int, secure: Boolean, decorations: Boolean) {
        val flags = buildList {
            if (secure) add("secure")
            if (decorations) add("should_show_system_decorations")
        }.joinToString(separator = ",", prefix = if (secure || decorations) "," else "")
        val requested = "${width}x$height/$density$flags"
        check(Settings.Global.putString(resolver, DISPLAY_SPECIFICATION, requested)) { "Unable to request overlay display" }
        check(Settings.Global.getString(resolver, DISPLAY_SPECIFICATION) == requested) { "Overlay display request rejected" }
        OperationLog.i(context, "DisplayBackend", "created request strategy=overlay_settings spec=$requested")
    }

    override fun clearRequest() {
        check(Settings.Global.putString(resolver, DISPLAY_SPECIFICATION, "")) { "Unable to clear overlay display" }
    }

    override fun findCreatedDisplay(previousIds: Set<Int>): Display? = displayManager.displays.firstOrNull {
        it.displayId != Display.DEFAULT_DISPLAY && it.displayId !in previousIds &&
            runCatching { Display::class.java.getMethod("getType").invoke(it) as Int == 4 }.getOrDefault(false)
    }

    fun attach(displayId: Int, host: SurfaceView, hostWidth: Int, hostHeight: Int, contentWidth: Int, contentHeight: Int): List<StrategyAttempt> {
        releaseLayer()
        val attempts = mutableListOf<StrategyAttempt>()
        var layer: SurfaceControl? = null
        for (id in environment.mirrorStrategies) {
            val backend = attachBackends[id] ?: continue
            if (!backend.isSupported()) {
                attempts += StrategyAttempt(id, false, "API unavailable")
                continue
            }
            val result = runCatching { backend.createLayer(displayId) }
            if (result.isSuccess) {
                layer = result.getOrThrow()
                attempts += StrategyAttempt(id, true, "attached")
                OperationLog.i(context, "DisplayBackend", "mirror strategy=$id success=true")
                break
            }
            val error = result.exceptionOrNull()!!
            attempts += StrategyAttempt(id, false, error.message.orEmpty())
            OperationLog.w(context, "DisplayBackend", "mirror strategy=$id failed", error)
        }
        checkNotNull(layer) { "No compatible mirror backend: ${attempts.joinToString { "${it.strategy}=${it.detail}" }}" }
        val parent = SurfaceView::class.java.getMethod("getSurfaceControl").invoke(host) as SurfaceControl
        val transaction = SurfaceControl.Transaction().reparent(layer, parent).setLayer(layer, 1)
        SurfaceControl.Transaction::class.java.getMethod(
            "setMatrix", SurfaceControl::class.java, Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType
        ).invoke(transaction, layer, hostWidth.toFloat() / contentWidth, 0f, 0f, hostHeight.toFloat() / contentHeight)
        SurfaceControl.Transaction::class.java.getMethod(
            "setWindowCrop", SurfaceControl::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).invoke(transaction, layer, contentWidth, contentHeight)
        SurfaceControl.Transaction::class.java.getMethod("show", SurfaceControl::class.java).invoke(transaction, layer)
        transaction.apply()
        attachedLayer = layer
        return attempts
    }

    fun releaseLayer() {
        attachedLayer?.let { layer ->
            runCatching {
                val transaction = SurfaceControl.Transaction()
                SurfaceControl.Transaction::class.java.getMethod("remove", SurfaceControl::class.java).invoke(transaction, layer)
                transaction.apply()
            }
            layer.release()
        }
        attachedLayer = null
    }

    companion object { const val DISPLAY_SPECIFICATION = "overlay_display_devices" }
}

private class WindowManagerMirrorBackend(private val privilegedAccess: PrivilegedAccess) : MirrorAttachBackend {
    override val id = "window_manager"
    override fun isSupported() = runCatching {
        Class.forName("android.view.IWindowManager").getMethod("mirrorDisplay", Int::class.javaPrimitiveType, SurfaceControl::class.java)
    }.isSuccess
    override fun createLayer(displayId: Int): SurfaceControl {
        val layer = SurfaceControl::class.java.getConstructor().newInstance()
        val service = privilegedAccess.service("window", "android.view.IWindowManager")
        val mirrored = Class.forName("android.view.IWindowManager")
            .getMethod("mirrorDisplay", Int::class.javaPrimitiveType, SurfaceControl::class.java)
            .invoke(service, displayId, layer) as Boolean
        check(mirrored) { "IWindowManager rejected mirror" }
        return layer
    }
}

private class SurfaceControlMirrorBackend : MirrorAttachBackend {
    override val id = "surface_control"
    override fun isSupported() = runCatching {
        SurfaceControl::class.java.getDeclaredMethod("mirrorDisplay", Int::class.javaPrimitiveType)
    }.isSuccess
    override fun createLayer(displayId: Int): SurfaceControl = SurfaceControl::class.java
        .getDeclaredMethod("mirrorDisplay", Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(null, displayId) as SurfaceControl
}
