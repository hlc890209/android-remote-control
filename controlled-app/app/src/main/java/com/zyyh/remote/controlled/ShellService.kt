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

    companion object {
        private const val DESCRIPTOR = "com.zyyh.remote.controlled.ShellService"
    }
}
