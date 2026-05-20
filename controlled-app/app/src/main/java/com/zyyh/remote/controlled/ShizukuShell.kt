package com.zyyh.remote.controlled

import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

class ShizukuShell {

    companion object {
        private const val TAG = "ShizukuShell"
        private var initialized = false
    }

    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        return Shizuku.getVersion() != -1
    }

    suspend fun requestPermission() {
        if (Shizuku.getVersion() == -1) return
        if (!Shizuku.isPermissionGranted()) {
            Shizuku.requestPermission(10086)
        }
    }

    suspend fun execute(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Executing: $command")
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Log.d(TAG, "Exit: $exitCode")
            ShellResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Log.e(TAG, "Execute error", e)
            ShellResult(-1, "", e.message ?: "Unknown error")
        }
    }

    suspend fun tap(x: Int, y: Int): ShellResult {
        return execute("input tap $x $y")
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): ShellResult {
        return execute("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    suspend fun text(text: String): ShellResult {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("\$", "\\\$")
            .replace("`", "\\`")
        return execute("input text \"$escaped\"")
    }

    suspend fun keyEvent(keyCode: Int): ShellResult {
        return execute("input keyevent $keyCode")
    }

    suspend fun keyEvent(name: String): ShellResult {
        return execute("input keyevent $name")
    }

    suspend fun getScreenSize(): ShellResult {
        return execute("wm size")
    }

    suspend fun dumpUi(): ShellResult {
        val dumpResult = execute("uiautomator dump /data/local/tmp/zyyh_uidump.xml 2>/dev/null")
        Thread.sleep(500)
        return execute("cat /data/local/tmp/zyyh_uidump.xml")
    }

    suspend fun shellRaw(command: String): ShellResult {
        return execute(command)
    }
}
