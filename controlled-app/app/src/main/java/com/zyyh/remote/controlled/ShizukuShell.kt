package com.zyyh.remote.controlled

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

data class ShellResult(
    val exitCode: Int, val stdout: String, val stderr: String
)

class ShizukuShell(private val context: Context) {

    private var binderRef: IBinder? = null
    private var connRef: ServiceConnection? = null

    fun isRunning(): Boolean = try { Shizuku.pingBinder(); true } catch (_: Exception) { false }
    fun isPermissionGranted(): Boolean = try { Shizuku.checkSelfPermission() == 0 } catch (_: Exception) { false }

    suspend fun requestPermission() {
        if (Shizuku.getVersion() == -1) return
        if (!isPermissionGranted()) Shizuku.requestPermission(10086)
    }

    private suspend fun getBinder(): IBinder? = suspendCancellableCoroutine { cont ->
        if (binderRef != null) { cont.resume(binderRef); return@suspendCancellableCoroutine }

        val args = Shizuku.UserServiceArgs(
            ComponentName(context, ShellService::class.java)
        )
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderRef = service
                connRef = this
                cont.resume(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                binderRef = null
            }
        }
        try { Shizuku.bindUserService(args, conn) } catch (e: Exception) { cont.resume(null) }
    }

    suspend fun execute(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            val binder = getBinder() ?: return@withContext fallbackExec(command)
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("com.zyyh.remote.controlled.ShellService")
                data.writeString(command)
                binder.transact(1, data, reply, 0)
                reply.readException()
                parseResult(reply.readString() ?: "")
            } finally { data.recycle(); reply.recycle() }
        } catch (e: Exception) { fallbackExec(command) }
    }

    private fun fallbackExec(command: String): ShellResult = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        ShellResult(p.waitFor(), out, err)
    } catch (e: Exception) { ShellResult(-1, "", e.message ?: "") }

    private fun parseResult(raw: String): ShellResult {
        val lines = raw.split("\n")
        val exitCode = lines.find { it.startsWith("__EXIT:") }
            ?.removePrefix("__EXIT:")?.trim()?.toIntOrNull() ?: -1
        val sOut = lines.indexOfFirst { it == "__STDOUT:" }
        val sErr = lines.indexOfFirst { it == "__STDERR:" }
        val stdout = if (sOut >= 0) {
            val end = if (sErr > sOut) sErr else lines.size
            lines.subList(sOut + 1, end).joinToString("\n").trim()
        } else ""
        val stderr = if (sErr >= 0)
            lines.subList(sErr + 1, lines.size).joinToString("\n").trim()
        else ""
        return ShellResult(exitCode, stdout, stderr)
    }

    suspend fun tap(x: Int, y: Int) = execute("input tap $x $y")
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, d: Int = 300) = execute("input swipe $x1 $y1 $x2 $y2 $d")
    suspend fun text(text: String) = execute("input text \"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    suspend fun keyEvent(k: Int) = execute("input keyevent $k")
    suspend fun keyEvent(n: String) = execute("input keyevent $n")
    suspend fun getScreenSize() = execute("wm size")
    suspend fun dumpUi(): ShellResult {
        execute("uiautomator dump /data/local/tmp/zyyh_uidump.xml 2>/dev/null")
        return execute("cat /data/local/tmp/zyyh_uidump.xml")
    }
    suspend fun shellRaw(cmd: String) = execute(cmd)
}
