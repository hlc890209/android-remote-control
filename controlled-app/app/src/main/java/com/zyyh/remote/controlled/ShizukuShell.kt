package com.zyyh.remote.controlled

import android.content.ComponentName
import android.content.Context
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

class ShizukuShell(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuShell"
        private const val DESCRIPTOR = "com.zyyh.remote.controlled.ShellService"
        private const val TRANSACT_EXEC = 1
    }

    private var serviceBinder: IBinder? = null
    private var serviceConnection: Shizuku.UserServiceConnection? = null

    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == 0
        } catch (e: Exception) {
            false
        }
    }

    suspend fun requestPermission() {
        if (Shizuku.getVersion() == -1) return
        if (!isPermissionGranted()) {
            Shizuku.requestPermission(10086)
        }
    }

    private suspend fun ensureService(): IBinder = suspendCancellableCoroutine { cont ->
        if (serviceBinder != null) {
            cont.resume(serviceBinder!!)
            return@suspendCancellableCoroutine
        }

        val args = Shizuku.UserServiceArgs(
            ComponentName(context, ShellService::class.java)
        ).apply {
            processName = "com.zyyh.remote.controlled:shell"
        }

        val conn = object : Shizuku.UserServiceConnection {
            override fun onServiceConnected(component: ComponentName?, binder: IBinder?) {
                serviceBinder = binder
                serviceConnection = this
                if (binder != null) cont.resume(binder)
                else cont.resume(createFallbackBinder())
            }

            override fun onServiceDisconnected(component: ComponentName?) {
                serviceBinder = null
            }
        }

        try {
            Shizuku.bindUserService(args, conn)
        } catch (e: Exception) {
            cont.resume(createFallbackBinder())
        }
    }

    private fun createFallbackBinder(): IBinder {
        // Use direct runtime exec as fallback (may not have shell permissions)
        return object : IBinder {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == TRANSACT_EXEC) {
                    data.enforceInterface(DESCRIPTOR)
                    val command = data.readString() ?: return false
                    val result = try {
                        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                        val out = p.inputStream.bufferedReader().readText()
                        val err = p.errorStream.bufferedReader().readText()
                        val exit = p.waitFor()
                        "__EXIT:$exit\n__STDOUT:\n$out\n__STDERR:\n$err"
                    } catch (e: Exception) {
                        "__EXIT:-1\n__STDERR:${e.message}"
                    }
                    reply?.writeNoException()
                    reply?.writeString(result)
                    return true
                }
                return false
            }

            override fun queryLocalInterface(descriptor: String): IBinder? = null
            override fun getInterfaceDescriptor(): String? = DESCRIPTOR
            override fun pingBinder(): Boolean = false
            override fun isBinderAlive(): Boolean = false
            override fun dump(fd: java.io.FileDescriptor?, args: Array<String>?) {}
            override fun dumpAsync(fd: java.io.FileDescriptor?, args: Array<String>?) {}
            override fun linkToDeath(recipient: IBinder.DeathRecipient?, flags: Int) {}
            override fun unlinkToDeath(recipient: IBinder.DeathRecipient?, flags: Int): Boolean = false
        }
    }

    suspend fun execute(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            val binder = ensureService()

            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(command)
                binder.transact(TRANSACT_EXEC, data, reply, 0)
                reply.readException()
                val raw = reply.readString() ?: ""
                parseResult(raw)
            } finally {
                data.recycle()
                reply.recycle()
            }
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "Unknown error")
        }
    }

    private fun parseResult(raw: String): ShellResult {
        val lines = raw.split("\n")
        val exitLine = lines.find { it.startsWith("__EXIT:") }
        val exitCode = exitLine?.removePrefix("__EXIT:")?.trim()?.toIntOrNull() ?: -1

        val stdoutStart = lines.indexOfFirst { it == "__STDOUT:" }
        val stderrStart = lines.indexOfFirst { it == "__STDERR:" }

        val stdout = if (stdoutStart >= 0) {
            val end = if (stderrStart > stdoutStart) stderrStart else lines.size
            lines.subList(stdoutStart + 1, end).joinToString("\n").trim()
        } else ""

        val stderr = if (stderrStart >= 0) {
            lines.subList(stderrStart + 1, lines.size).joinToString("\n").trim()
        } else ""

        ShellResult(exitCode, stdout, stderr)
    }

    suspend fun tap(x: Int, y: Int): ShellResult = execute("input tap $x $y")

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

    suspend fun keyEvent(keyCode: Int): ShellResult = execute("input keyevent $keyCode")
    suspend fun keyEvent(name: String): ShellResult = execute("input keyevent $name")
    suspend fun getScreenSize(): ShellResult = execute("wm size")

    suspend fun dumpUi(): ShellResult {
        execute("uiautomator dump /data/local/tmp/zyyh_uidump.xml 2>/dev/null")
        return execute("cat /data/local/tmp/zyyh_uidump.xml")
    }

    suspend fun shellRaw(command: String): ShellResult = execute(command)
}
