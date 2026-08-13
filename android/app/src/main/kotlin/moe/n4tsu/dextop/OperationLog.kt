package moe.n4tsu.dextop

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small rolling log that survives process restarts and is included in reports. */
internal object OperationLog {
    private const val MAX_BYTES = 1_000_000L
    private const val FILE_NAME = "dextop-operation.log"
    private val lock = Any()

    fun i(context: Context, component: String, message: String) = write(context, "I", component, message)
    fun w(context: Context, component: String, message: String, error: Throwable? = null) =
        write(context, "W", component, message + (error?.let { " | ${it.stackTraceToString()}" } ?: ""))
    fun e(context: Context, component: String, message: String, error: Throwable? = null) =
        write(context, "E", component, message + (error?.let { " | ${it.stackTraceToString()}" } ?: ""))

    private fun write(context: Context, level: String, component: String, message: String) {
        val line = "${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())} $level/$component $message\n"
        when (level) { "E" -> Log.e(component, message); "W" -> Log.w(component, message); else -> Log.i(component, message) }
        synchronized(lock) {
            val file = File(context.filesDir, FILE_NAME)
            if (file.length() > MAX_BYTES) {
                val tail = file.readText().takeLast((MAX_BYTES / 2).toInt())
                file.writeText("--- log rotated ---\n$tail")
            }
            file.appendText(line)
        }
    }

    fun read(context: Context): String = synchronized(lock) {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText().orEmpty()
    }

    fun clear(context: Context) = synchronized(lock) { File(context.filesDir, FILE_NAME).delete() }
}
