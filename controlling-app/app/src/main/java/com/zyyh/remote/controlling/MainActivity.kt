package com.zyyh.remote.controlling

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var remoteView: RemoteView
    private lateinit var tvStatus: TextView
    private lateinit var btnDump: Button
    private lateinit var btnBack: Button
    private lateinit var btnHome: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutElementInfo: LinearLayout
    private lateinit var tvElementInfo: TextView
    private lateinit var btnTap: Button
    private lateinit var btnLongClick: Button

    private var mqttClient: MqttAndroidClient? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val pendingCmds = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private var peerOnline = false
    private var currentRoomId = ""

    private var config = Config(
        broker = "broker.emqx.io",
        port = 1883,
        roomId = "test_001"
    )

    private data class Config(
        var broker: String,
        var port: Int,
        var roomId: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        remoteView = findViewById(R.id.remoteView)
        tvStatus = findViewById(R.id.tvStatus)
        btnDump = findViewById(R.id.btnDump)
        btnBack = findViewById(R.id.btnBack)
        btnHome = findViewById(R.id.btnHome)
        progressBar = findViewById(R.id.progressBar)
        layoutElementInfo = findViewById(R.id.layoutElementInfo)
        tvElementInfo = findViewById(R.id.tvElementInfo)
        btnTap = findViewById(R.id.btnTap)
        btnLongClick = findViewById(R.id.btnLongClick)

        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        btnDump.setOnClickListener { refreshUiDump() }
        btnBack.setOnClickListener { sendCommand("keyevent", mapOf("key" to "KEYCODE_BACK")) }
        btnHome.setOnClickListener { sendCommand("keyevent", mapOf("key" to "KEYCODE_HOME")) }
        btnTap.setOnClickListener { tapSelectedElement(false) }
        btnLongClick.setOnClickListener { tapSelectedElement(true) }

        remoteView.onElementClicked = { node, _, _ -> showElementInfo(node) }
        remoteView.onUserTapAt = { x, y ->
            hideElementInfo()
            sendCommand("tap", mapOf("x" to x.roundToInt(), "y" to y.roundToInt()))
        }

        showSettingsDialog()
    }

    private fun showSettingsDialog() {
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
            addView(label("房间ID（与被控端一致）:"))
            val etRoom = edit(config.roomId, "test_001")
            addView(etRoom)
        }

        AlertDialog.Builder(this)
            .setTitle("MQTT 连接设置")
            .setView(inputLayout)
            .setPositiveButton("连接") { _, _ ->
                val broker = inputLayout.getChildAt(1).let { (it as EditText).text.toString() }
                val port = inputLayout.getChildAt(3).let { (it as EditText).text.toString() }
                val room = inputLayout.getChildAt(5).let { (it as EditText).text.toString() }
                config = Config(broker, port.toIntOrNull() ?: 1883, room)
                connect()
            }
            .setNegativeButton("取消") { _, _ ->
                if (mqttClient == null) Toast.makeText(this, "请配置MQTT连接", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun connect() {
        disconnect()
        currentRoomId = config.roomId
        val serverUri = "tcp://${config.broker}:${config.port}"
        val clientId = "zyyh_controller_${config.roomId}_${UUID.randomUUID().toString().take(6)}"

        tvStatus.text = "状态: 正在连接..."
        tvStatus.setBackgroundColor(0xFFFFF9C4.toInt())

        try {
            mqttClient = MqttAndroidClient(this, serverUri, clientId)
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
            }

            mqttClient?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    scope.launch {
                        tvStatus.text = "状态: 已连接，等待被控端..."
                        tvStatus.setBackgroundColor(0xFFFFF9C4.toInt())
                    }
                    subscribe("zyyh/${config.roomId}/result")
                    subscribe("zyyh/${config.roomId}/status")

                    // 发布控制器在线
                    publish("zyyh/${config.roomId}/status",
                        """{"type":"status","status":"online"}""")
                }

                override fun connectionLost(cause: Throwable?) {
                    peerOnline = false
                    scope.launch {
                        tvStatus.text = "状态: 连接断开"
                        tvStatus.setBackgroundColor(0xFFFFCDD2.toInt())
                    }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val payload = String(message?.payload ?: return)
                    handleMessage(topic ?: "", payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {}
                override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable?) {
                    scope.launch {
                        tvStatus.text = "状态: 连接失败 - ${exception?.message}"
                        tvStatus.setBackgroundColor(0xFFFFCDD2.toInt())
                    }
                }
            })
        } catch (e: Exception) {
            tvStatus.text = "状态: 错误 - ${e.message}"
            tvStatus.setBackgroundColor(0xFFFFCDD2.toInt())
        }
    }

    private fun subscribe(topic: String) {
        try { mqttClient?.subscribe(topic, 1) } catch (_: Exception) {}
    }

    private fun publish(topic: String, payload: String) {
        try {
            val msg = MqttMessage(payload.toByteArray()).apply { qos = 1 }
            mqttClient?.publish(topic, msg)
        } catch (_: Exception) {}
    }

    private fun handleMessage(topic: String, payload: String) {
        try {
            val json = JSONObject(payload)
            when {
                topic.endsWith("/result") -> handleResult(json)
                topic.endsWith("/status") -> handleStatus(json)
            }
        } catch (_: Exception) {}
    }

    private fun handleResult(json: JSONObject) {
        val cmdId = json.optString("cmdId", "")
        val deferred = pendingCmds.remove(cmdId)
        deferred?.let { if (!it.isCompleted) it.complete(json) }
    }

    private fun handleStatus(json: JSONObject) {
        val status = json.optString("status")
        peerOnline = status == "online"
        scope.launch {
            if (status == "online") {
                tvStatus.text = "状态: 被控端在线 ✓"
                tvStatus.setBackgroundColor(0xFFC8E6C9.toInt())
                Toast.makeText(this@MainActivity, "被控端已连接！", Toast.LENGTH_SHORT).show()
            } else {
                tvStatus.text = "状态: 被控端离线"
                tvStatus.setBackgroundColor(0xFFFFCDD2.toInt())
            }
        }
    }

    private suspend fun sendCommandAwait(action: String, params: Map<String, Any> = mapOf()): JSONObject? {
        if (!peerOnline) {
            scope.launch { Toast.makeText(this@MainActivity, "被控端不在线", Toast.LENGTH_SHORT).show() }
            return null
        }

        val cmdId = UUID.randomUUID().toString().take(8)
        val deferred = CompletableDeferred<JSONObject>()
        pendingCmds[cmdId] = deferred

        val cmd = JSONObject().apply {
            put("type", "command")
            put("cmdId", cmdId)
            put("action", action)
            for ((k, v) in params) {
                when (v) {
                    is Int -> put(k, v)
                    is String -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }

        publish("zyyh/${config.roomId}/command", cmd.toString())
        return withTimeoutOrNull(10000) { deferred.await() }
    }

    private fun sendCommand(action: String, params: Map<String, Any> = mapOf()) {
        scope.launch {
            val result = sendCommandAwait(action, params)
            if (result == null && peerOnline) {
                Toast.makeText(this@MainActivity, "命令超时", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshUiDump() {
        scope.launch {
            progressBar.visibility = View.VISIBLE
            tvStatus.text = "状态: 正在获取UI布局..."

            val result = sendCommandAwait("dump_ui")
            if (result != null && result.optInt("exitCode") == 0) {
                val xml = result.optString("stdout", "")
                if (xml.isNotEmpty()) {
                    val screenInfo = parseUiXml(xml)
                    if (screenInfo != null) {
                        remoteView.setScreenInfo(screenInfo)
                        tvStatus.text = "状态: UI已刷新 (${screenInfo.width}x${screenInfo.height})"
                    } else {
                        tvStatus.text = "状态: UI解析失败"
                    }
                }
            } else {
                tvStatus.text = "状态: UI获取失败"
            }
            progressBar.visibility = View.GONE
        }
    }

    private fun parseUiXml(xml: String): ScreenInfo? {
        try {
            val hs = xml.indexOf("<hierarchy")
            val he = xml.indexOf("</hierarchy>")
            if (hs < 0 || he < 0) return null
            val hxml = xml.substring(hs, he + "</hierarchy>".length)

            val boundsMatch = Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""").find(hxml)
            val (screenW, screenH) = if (boundsMatch != null) {
                val g = boundsMatch.groupValues; g[3].toInt() to g[4].toInt()
            } else 1080 to 2340

            val root = parseNode(hxml, 0) ?: return null
            if (root.bounds.isEmpty()) return null

            val allNodes = flatten(root).filter {
                it.enabled && it.bounds.width() > 10 && it.bounds.height() > 10 &&
                (it.clickable || it.text.isNotEmpty() || it.contentDesc.isNotEmpty())
            }
            return ScreenInfo(screenW, screenH, allNodes)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun parseNode(xml: String, start: Int): UiNode? {
        val ns = xml.indexOf("<node", start); if (ns < 0) return null
        var depth = 0; var ne = ns
        while (ne < xml.length) {
            val c = xml[ne]
            if (c == '<') {
                if (ne + 1 < xml.length && xml[ne + 1] == '/') { depth--
                    if (depth < 0) { ne = xml.indexOf(">", ne) + 1; break } }
                else if (xml[ne + 1] != '!') {
                    val cl = xml.indexOf(">", ne)
                    if (cl > 0 && xml[cl - 1] == '/') { /* self-closing */ }
                    else depth++
                }
            }
            ne++
        }
        if (ne >= xml.length) return null

        val content = xml.substring(ns, ne)

        fun attr(n: String): String = Regex("""$n="([^"]*)"""").find(content)?.groupValues?.getOrElse(1) { "" } ?: ""
        fun abool(n: String): Boolean = attr(n) == "true"
        fun abounds(): Rect {
            val m = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(attr("bounds"))
            return if (m != null) Rect(m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                m.groupValues[3].toInt(), m.groupValues[4].toInt())
            else Rect(0, 0, 0, 0)
        }

        val bounds = abounds(); if (bounds.isEmpty()) return null
        val node = UiNode(bounds, attr("text"), attr("content-desc"), attr("resource-id"),
            attr("class"), abool("clickable"), abool("long-clickable"), abool("scrollable"),
            abool("checkable"), abool("checked"), abool("enabled"), abool("password"), 0)

        var cs = content.indexOf("<node", 1)
        while (cs > 0) {
            val child = parseNode(content, cs) ?: break
            child.depth = node.depth + 1; node.children.add(child)
            cs = content.indexOf("<node", cs + 1)
        }
        return node
    }

    private fun flatten(n: UiNode): List<UiNode> = listOf(n) + n.children.flatMap { flatten(it) }

    private fun showElementInfo(node: UiNode) {
        layoutElementInfo.visibility = View.VISIBLE
        tvElementInfo.text = buildString {
            append("Text: ${node.text}\n")
            append("ID: ${node.resourceId}\n")
            append("Class: ${node.className}\n")
            append("Bounds: [${node.bounds.left},${node.bounds.top}]-[${node.bounds.right},${node.bounds.bottom}]\n")
            if (node.contentDesc.isNotEmpty()) append("Desc: ${node.contentDesc}\n")
            append("Clickable: ${node.clickable} | LongClick: ${node.longClickable}")
        }
    }

    private fun hideElementInfo() { layoutElementInfo.visibility = View.GONE }

    private fun tapSelectedElement(longPress: Boolean) {
        val node = remoteView.getSelectedNode() ?: return
        val cx = node.bounds.centerX(); val cy = node.bounds.centerY()
        if (longPress) sendCommand("swipe", mapOf("x1" to cx, "y1" to cy, "x2" to cx, "y2" to cy, "duration" to 1000))
        else sendCommand("tap", mapOf("x" to cx, "y" to cy))
        hideElementInfo()
    }

    private fun disconnect() {
        try { mqttClient?.disconnect(); mqttClient?.close() } catch (_: Exception) {}
        mqttClient = null; peerOnline = false
        for (d in pendingCmds.values) d.completeExceptionally(Exception("Disconnected"))
        pendingCmds.clear()
    }

    override fun onDestroy() { disconnect(); scope.cancel(); super.onDestroy() }
}
