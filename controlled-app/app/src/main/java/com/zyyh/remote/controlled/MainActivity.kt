package com.zyyh.remote.controlled

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

class MainActivity : AppCompatActivity() {

    private lateinit var tvShizuku: TextView
    private lateinit var tvConn: TextView
    private lateinit var tvRoom: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button

    private val shell = ShizukuShell()
    private var wsClient: WebSocketClient? = null
    private var currentRoomId: String = ""

    private var config = Config(
        relayHost = "your-server.com",
        relayPort = 8080,
        authToken = "zyyh_remote_test_2024",
        roomId = "room_001"
    )

    data class Config(
        val relayHost: String,
        val relayPort: Int,
        val authToken: String,
        val roomId: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvShizuku = findViewById(R.id.tvShizukuStatus)
        tvConn = findViewById(R.id.tvConnectionStatus)
        tvRoom = findViewById(R.id.tvRoomInfo)
        tvLog = findViewById(R.id.tvLog)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        btnConnect.setOnClickListener { showConfigDialog() }
        btnDisconnect.setOnClickListener { disconnect() }

        checkShizuku()
    }

    private fun checkShizuku() {
        lifecycleScope.launch {
            if (shell.isRunning()) {
                tvShizuku.text = "Shizuku: 运行中"
                tvShizuku.setTextColor(0xFF4CAF50.toInt())
                if (!shell.isPermissionGranted()) {
                    shell.requestPermission()
                }
            } else {
                tvShizuku.text = "Shizuku: 未运行"
                tvShizuku.setTextColor(0xFFF44336.toInt())
            }
        }
    }

    private fun showConfigDialog() {
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
            var idCounter = View.generateViewId()

            fun addLabel(text: String) = TextView(context).apply {
                this.text = text
                textSize = 14f
                setTextColor(0xFF666666.toInt())
                setPadding(0, 12, 0, 4)
            }

            fun addEdit(initial: String, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT) =
                EditText(context).apply {
                    setId(idCounter++)
                    setText(initial)
                    this.hint = hint
                    this.inputType = inputType
                    setPadding(8, 8, 8, 8)
                    setBackgroundResource(android.R.drawable.editbox_background)
                }

            addView(addLabel("中继服务器地址:"))
            val etHost = addEdit(config.relayHost, "例如: 123.123.123.123")
            addView(etHost)

            addView(addLabel("端口:"))
            val etPort = addEdit(config.relayPort.toString(), "8080", InputType.TYPE_CLASS_NUMBER)
            addView(etPort)

            addView(addLabel("房间ID:"))
            val etRoom = addEdit(config.roomId, "与控制端相同的房间ID")
            addView(etRoom)

            addView(addLabel("Token:"))
            val etToken = addEdit(config.authToken, "认证令牌")
            addView(etToken)
        }

        AlertDialog.Builder(this)
            .setTitle("连接配置")
            .setView(inputLayout)
            .setPositiveButton("连接") { _, _ ->
                val host = inputLayout.getChildAt(1).let { (it as EditText).text.toString() }
                val port = inputLayout.getChildAt(3).let { (it as EditText).text.toString() }
                val room = inputLayout.getChildAt(5).let { (it as EditText).text.toString() }
                val token = inputLayout.getChildAt(7).let { (it as EditText).text.toString() }
                config = Config(host, port.toIntOrNull() ?: 8080, token, room)
                connectToRelay()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun connectToRelay() {
        lifecycleScope.launch {
            try {
                val uri = URI("ws://${config.relayHost}:${config.relayPort}")
                currentRoomId = config.roomId

                wsClient = object : WebSocketClient(uri) {
                    override fun onOpen(handshake: ServerHandshake) {
                        appendLog("已连接到中继服务器")
                        runOnUiThread {
                            tvConn.text = "中继: 已连接"
                            tvConn.setTextColor(0xFF4CAF50.toInt())
                        }
                        send(JSONObject().apply {
                            put("type", "auth")
                            put("token", config.authToken)
                        }.toString())
                    }

                    override fun onMessage(message: String) {
                        handleRelayMessage(message)
                    }

                    override fun onClose(code: Int, reason: String, remote: Boolean) {
                        appendLog("连接关闭: $reason")
                        runOnUiThread {
                            tvConn.text = "中继: 已断开"
                            tvConn.setTextColor(0xFFF44336.toInt())
                            btnConnect.isEnabled = true
                            btnDisconnect.isEnabled = false
                        }
                    }

                    override fun onError(ex: Exception) {
                        appendLog("连接错误: ${ex.message}")
                    }
                }

                wsClient?.connect()
                appendLog("正在连接 ${config.relayHost}:${config.relayPort}...")
                btnConnect.isEnabled = false
                btnDisconnect.isEnabled = true
                tvRoom.text = "房间ID: ${config.roomId}"
            } catch (e: Exception) {
                appendLog("连接失败: ${e.message}")
                Toast.makeText(this@MainActivity, "连接失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleRelayMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "auth_ok" -> {
                    appendLog("认证成功，正在注册...")
                    wsClient?.send(JSONObject().apply {
                        put("type", "register")
                        put("role", "controlled")
                        put("roomId", currentRoomId)
                    }.toString())
                }
                "auth_error" -> appendLog("认证失败: ${json.optString("message")}")
                "registered" -> {
                    appendLog("已注册到房间: ${json.optString("roomId")}")
                    runOnUiThread { tvRoom.text = "房间ID: ${json.optString("roomId")} (已注册)" }
                }
                "peer_connected" -> {
                    appendLog("控制端已连接！")
                    Toast.makeText(this, "控制端已连接！", Toast.LENGTH_LONG).show()
                }
                "peer_disconnected" -> {
                    appendLog("控制端已断开")
                    Toast.makeText(this, "控制端已断开", Toast.LENGTH_SHORT).show()
                }
                "command" -> handleCommand(json)
            }
        } catch (e: Exception) {
            appendLog("消息解析错误: ${e.message}")
        }
    }

    private fun handleCommand(json: JSONObject) = lifecycleScope.launch {
        val cmdId = json.optString("cmdId")
        val action = json.optString("action")
        appendLog("收到命令: $action")

        val result = when (action) {
            "dump_ui" -> shell.dumpUi()
            "tap" -> {
                val x = json.optInt("x", 0)
                val y = json.optInt("y", 0)
                shell.tap(x, y)
            }
            "swipe" -> {
                val x1 = json.optInt("x1", 0)
                val y1 = json.optInt("y1", 0)
                val x2 = json.optInt("x2", 0)
                val y2 = json.optInt("y2", 0)
                val dur = json.optInt("duration", 300)
                shell.swipe(x1, y1, x2, y2, dur)
            }
            "text" -> shell.text(json.optString("text", ""))
            "keyevent" -> {
                val key: Any? = json.opt("key")
                if (key is Int) shell.keyEvent(key)
                else shell.keyEvent(key?.toString() ?: "")
            }
            "shell" -> shell.shellRaw(json.optString("command", ""))
            "get_screen_size" -> shell.getScreenSize()
            else -> ShellResult(-1, "", "Unknown action: $action")
        }

        val response = JSONObject().apply {
            put("type", "command_result")
            put("cmdId", cmdId)
            put("action", action)
            put("exitCode", result.exitCode)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
        }
        wsClient?.send(response.toString())
        appendLog("命令 $action 完成 (exit: ${result.exitCode})")
    }

    private fun disconnect() {
        wsClient?.close()
        wsClient = null
        btnConnect.isEnabled = true
        btnDisconnect.isEnabled = false
        tvConn.text = "中继: 未连接"
        tvConn.setTextColor(0xFF9E9E9E.toInt())
        tvRoom.text = "房间ID: 无"
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            tvLog.append("[$ts] $msg\n")
            val sv = tvLog.parent as? android.widget.ScrollView
            sv?.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
