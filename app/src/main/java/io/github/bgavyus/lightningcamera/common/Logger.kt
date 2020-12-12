package io.github.bgavyus.lightningcamera.common

import android.util.Log
import io.github.bgavyus.lightningcamera.BuildConfig

object Logger {
    private val tagRegex = Regex("""(\w+)(?:$|\$)""")

    fun debug(message: String) = log(Log.DEBUG, message)
    fun info(message: String) = log(Log.INFO, message)
    fun warn(message: String) = log(Log.WARN, message)
    fun error(message: String, throwable: Throwable) = log(Log.ERROR, concat(message, throwable))

    private fun log(priority: Int, message: String) {
        if (BuildConfig.DEBUG) {
            Log.println(priority, tag(), message)
        }
    }

    private fun tag(): String {
        val element = Throwable().stackTrace[3]
        val result = tagRegex.find(element.className) ?: throw RuntimeException()
        return result.groupValues[1]
    }

    private fun concat(message: String, throwable: Throwable) =
        "$message\n${Log.getStackTraceString(throwable)}"
}
