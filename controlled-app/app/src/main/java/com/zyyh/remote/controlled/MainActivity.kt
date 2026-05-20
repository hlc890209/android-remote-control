package com.zyyh.remote.controlled

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var tvMqtt: TextView
    private lateinit var tvRoom: TextView
    private lateinit var tvPeer: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button

    private lateinit var shell: ShizukuShell
    private var mqttClient: MqttAndroidClient? = null
    private var currentRoomId = ""
    private var isConnected = false

    private var config = Config(
        broker = "broker.emqx.io",
        port = 1883,
        roomId = "test_001"
    )

    data class Config(val broker: String, val port: Int, val roomId: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        shell = ShizukuShell(this)

        tvMqtt = findViewById(R.id.tvMqtt)
        tvRoom = findViewById(R.id.tvRoom)
        tvPeer = findViewById(R.id.tvPeer)
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
                appendLog("Shizuku: 运行中")
                if (!shell.isPermissionGranted()) {
                    shell.requestPermission()
                }
            } else {
                appendLog("Shizuku: 未运行 - 请先激活Shizuku")
            }
        }
    }

    private fun showConfigDialog() {
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)

            fun label(text: String) = TextView(context).apply {
                this.text = text; textSize = 14f
                setTextColor(0xFF666666.toInt())
                setPadding(0, 12, 0, 4)
            }

            fun edit(initial: String, hint: String, type: Int = InputType.TYPE_CLASS_TEXT) =
                EditText(context).apply {
                    setText(initial); this.hint = hint; inputType = type
                    setPadding(8, 8, 8, 8)
                    setBackgroundResource(android.R.drawable.editbox_background)
                }

            addView(label("MQTT Broker 地址:"))
            val etBroker = edit(config.broker, "broker.emqx.io")
            addView(etBroker)
            addView(label("端口:"))
            val etPort = edit(config.port.toString(), "1883", InputType.TYPE_CLASS_NUMBER)
            addView(etPort)
            addView(label("房间ID（两端一致）:"))
            val etRoom = edit(config.roomId, "test_001")
            addView(etRoom)
        }

        AlertDialog.Builder(this)
            .setTitle("MQTT 连接配置")
            .setView(inputLayout)
            .setPositiveButton("连接") { _, _ ->
                val broker = inputLayout.getChildAt(1).let { (it as EditText).text.toString() }
                val port = inputLayout.getChildAt(3).let { (it as EditText).text.toString() }
                val room = inputLayout.getChildAt(5).let { (it as EditText).text.toString() }
                config = Config(broker, port.toIntOrNull() ?: 1883, room)
                connect()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun connect() {
        currentRoomId = config.roomId
        val serverUri = "tcp://${config.broker}:${config.port}"
        val clientId = "zyyh_controlled_${config.roomId}_${UUID.randomUUID().toString().take(6)}"

        appendLog("正在连接 $serverUri ...")
        tvMqtt.text = "MQTT: 连接中..."

        try {
            mqttClient = MqttAndroidClient(this, serverUri, clientId)
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                setWill(
                    "zyyh/${config.roomId}/status",
                    """{"type":"status","status":"offline"}""".toByteArray(),
                    1, false
                )
            }

            mqttClient?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    isConnected = true
                    runOnUiThread {
                        tvMqtt.text = "MQTT: 已连接"
                        tvRoom.text = "房间: ${config.roomId}"
                        btnConnect.isEnabled = false
                        btnDisconnect.isEnabled = true
                    }
                    appendLog(if (reconnect) "已重连" else "已连接")
                    subscribe("zyyh/${config.roomId}/command")
                    publish("zyyh/${config.roomId}/status",
                        """{"type":"status","status":"online"}""")
                }

                override fun connectionLost(cause: Throwable?) {
                    isConnected = false
                    appendLog("连接断开: ${cause?.message}")
                    runOnUiThread { tvMqtt.text = "MQTT: 已断开" }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = String(message?.payload ?: return)
                    handleMessage(payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {}
                override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable?) {
                    appendLog("连接失败: ${exception?.message}")
                    runOnUiThread { tvMqtt.text = "MQTT: 连接失败" }
                }
            })
        } catch (e: Exception) {
            appendLog("MQTT 错误: ${e.message}")
        }
    }

    private fun subscribe(topic: String) {
        try {
            mqttClient?.subscribe(topic, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    appendLog("已订阅: $topic")
                }
                override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable?) {
                    appendLog("订阅失败: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            appendLog("订阅错误: ${e.message}")
        }
    }

    private fun publish(topic: String, payload: String) {
        try {
            val msg = MqttMessage(payload.toByteArray()).apply { qos = 1 }
            mqttClient?.publish(topic, msg)
        } catch (e: Exception) {
            appendLog("发布失败: ${e.message}")
        }
    }

    private fun handleMessage(payload: String) {
        try {
            val json = JSONObject(payload)
            when (json.optString("type")) {
                "command" -> handleCommand(json)
                "status" -> {
                    val status = json.optString("status")
                    runOnUiThread {
                        tvPeer.text = if (status == "online") "控制端: 在线 ✓" else "控制端: 离线"
                    }
                }
            }
        } catch (e: Exception) {
            appendLog("消息解析错误: ${e.message}")
        }
    }

    private fun handleCommand(json: JSONObject) = lifecycleScope.launch {
        val cmdId = json.optString("cmdId")
        val action = json.optString("action")
        appendLog("收到命令: $action")

        val result = withAction(action, json)

        val response = JSONObject().apply {
            put("type", "command_result")
            put("cmdId", cmdId)
            put("action", action)
            put("exitCode", result.exitCode)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
        }
        publish("zyyh/${config.roomId}/result", response.toString())
        appendLog("命令 $action 完成 (exit: ${result.exitCode})")
    }

    private suspend fun withAction(action: String, json: JSONObject): ShellResult {
        return when (action) {
            "dump_ui" -> shell.dumpUi()
            "tap" -> shell.tap(json.optInt("x", 0), json.optInt("y", 0))
            "swipe" -> {
                val x1 = json.optInt("x1", 0); val y1 = json.optInt("y1", 0)
                val x2 = json.optInt("x2", 0); val y2 = json.optInt("y2", 0)
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
    }

    private fun disconnect() {
        try { mqttClient?.disconnect(); mqttClient?.close() } catch (_: Exception) {}
        mqttClient = null; isConnected = false
        btnConnect.isEnabled = true; btnDisconnect.isEnabled = false
        tvMqtt.text = "MQTT: 未连接"; tvRoom.text = "房间: 无"
        tvPeer.text = "控制端: 离线"
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            tvLog.append("[$ts] $msg\n")
            (tvLog.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() { disconnect(); super.onDestroy() }
}
