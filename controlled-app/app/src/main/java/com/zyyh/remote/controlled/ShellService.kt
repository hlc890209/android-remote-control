package com.zyyh.remote.controlled

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

class ShellService : Service() {

    override fun onBind(intent: Intent?): IBinder {
        return object : Binder() {
            @Throws(RemoteException::class)
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 1) {
                    data.enforceInterface(DESCRIPTOR)
                    val command = data.readString() ?: return false
                    val result = executeCommand(command)
                    reply?.writeNoException()
                    reply?.writeString(result)
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
    }

    private fun executeCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            buildString {
                appendLine("__EXIT:$exitCode")
                appendLine("__STDOUT:")
                append(stdout)
                if (stderr.isNotBlank()) {
                    appendLine("__STDERR:")
                    append(stderr)
                }
            }
        } catch (e: Exception) {
            "__EXIT:-1\n__STDERR:${e.message}"
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

        return ShellResult(exitCode, stdout, stderr)
    }

    companion object {
        private const val DESCRIPTOR = "com.zyyh.remote.controlled.ShellService"
    }
}
