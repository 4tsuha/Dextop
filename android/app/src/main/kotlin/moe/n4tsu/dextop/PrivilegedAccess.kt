package moe.n4tsu.dextop

import android.os.IBinder
import android.util.Log
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

internal class PrivilegedAccess(private val tag: String) {
    fun isAvailable(): Boolean = runCatching {
        Shizuku.getBinder()?.isBinderAlive == true
    }.getOrDefault(false)

    fun service(name: String, interfaceName: String): Any {
        check(isAvailable()) { NativeStrings.text("nativeShizukuUnavailable") }
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService(name))
        return Class.forName("$interfaceName\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }

    fun execute(vararg arguments: String): CommandResult = runCatching {
        val binder = Shizuku.getBinder()
            ?: error(NativeStrings.text("nativeShizukuUnavailable"))
        val process = IShizukuService.Stub.asInterface(binder)
            .newProcess(arguments, null, null)
        val output = android.os.ParcelFileDescriptor.AutoCloseInputStream(process.inputStream)
            .bufferedReader().use { it.readText() }
        val error = android.os.ParcelFileDescriptor.AutoCloseInputStream(process.errorStream)
            .bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        CommandResult(exitCode, output.trim(), error.trim())
    }.onFailure {
        Log.e(tag, "privileged command failed: ${arguments.joinToString(" ")}", it)
    }.getOrElse { CommandResult(-1, "", it.message.orEmpty()) }

    data class CommandResult(val exitCode: Int, val output: String, val error: String) {
        val succeeded: Boolean get() = exitCode == 0
    }
}
