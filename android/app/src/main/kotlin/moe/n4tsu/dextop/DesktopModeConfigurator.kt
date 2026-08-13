package moe.n4tsu.dextop

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings

internal data class StrategyAttempt(val strategy: String, val succeeded: Boolean, val detail: String)

internal class DesktopModeConfigurator(
    private val context: Context,
    private val resolver: ContentResolver,
    private val privilegedAccess: PrivilegedAccess,
    private val environment: DesktopEnvironment,
    private val sessionJournal: SessionJournal
) {
    private data class SavedGlobal(val key: String, val value: String?)
    private val savedGlobals = mutableListOf<SavedGlobal>()
    private var applied = false

    fun applyForCurrentDevice(): List<StrategyAttempt> {
        if (applied) return emptyList()
        applied = true
        if (environment.platformManaged) {
            OperationLog.i(context, "DesktopMode", "platform-managed environment=${environment.id}")
            return listOf(StrategyAttempt("platform_managed", true, environment.id))
        }
        return environment.temporaryGlobalSettings.map { (key, value) ->
            runCatching {
                val previous = Settings.Global.getString(resolver, key)
                savedGlobals += SavedGlobal(key, previous)
                sessionJournal.rememberGlobal(key, previous)
                check(Settings.Global.putString(resolver, key, value)) { "setting rejected" }
                StrategyAttempt("global:$key", true, "$previous -> $value")
            }.getOrElse {
                OperationLog.w(context, "DesktopMode", "optional global setting failed key=$key", it)
                StrategyAttempt("global:$key", false, it.message.orEmpty())
            }
        }
    }

    /** Optional configuration: failure is isolated to this display and never aborts startup. */
    fun configureDisplay(displayId: Int): List<StrategyAttempt> {
        if (!environment.configureFreeformWindowing) return emptyList()
        val attempts = mutableListOf<StrategyAttempt>()
        for (strategy in environment.windowingStrategies) {
            val args = when (strategy) {
                "wm" -> arrayOf("wm", "set-display-windowing-mode", "-d", displayId.toString(), "5")
                "activity_task_manager" -> arrayOf(
                    "cmd", "activity_task", "set-display-windowing-mode", displayId.toString(), "5"
                )
                else -> continue
            }
            val result = privilegedAccess.execute(*args)
            val attempt = StrategyAttempt(strategy, result.succeeded, result.error.ifBlank { result.output })
            attempts += attempt
            OperationLog.i(context, "DesktopMode", "windowing display=$displayId strategy=$strategy success=${attempt.succeeded} detail=${attempt.detail}")
            if (result.succeeded) break
        }
        return attempts
    }

    fun restore() {
        sessionJournal.restoreSystemSettings()
        OperationLog.i(context, "DesktopMode", "restored settings count=${savedGlobals.size}")
        savedGlobals.clear()
        applied = false
    }
}
