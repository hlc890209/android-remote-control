package com.zyyh.remote.controlling

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var remoteView: RemoteView
    private lateinit var tvStatus: TextView
    private lateinit var btnSettings: Button
    private lateinit var btnDump: Button
    private lateinit var btnBack: Button
    private lateinit var btnHome: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutElementInfo: LinearLayout
    private lateinit var tvElementInfo: TextView
    private lateinit var btnTap: Button
    private lateinit var btnLongClick: Button

    private var wsClient: WebSocketClient? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pendingCmds = mutableMapOf<String, CompletableDeferred<JSONObject>>()

    private var config = Config(
        relayHost = "your-server.com",
        relayPort = 8080,
        authToken = "zyyh_remote_test_2024",
        roomId = "room_001"
    )

    private data class Config(
        var relayHost: String,
        var relayPort: Int,
        var authToken: String,
        var roomId: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        remoteView = findViewById(R.id.remoteView)
        tvStatus = findViewById(R.id.tvStatus)
        btnSettings = findViewById(R.id.btnSettings)
        btnDump = findViewById(R.id.btnDump)
        btnBack = findViewById(R.id.btnBack)
        btnHome = findViewById(R.id.btnHome)
        progressBar = findViewById(R.id.progressBar)
        layoutElementInfo = findViewById(R.id.layoutElementInfo)
        tvElementInfo = findViewById(R.id.tvElementInfo)
        btnTap = findViewById(R.id.btnTap)
        btnLongClick = findViewById(R.id.btnLongClick)

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnDump.setOnClickListener { refreshUiDump() }
        btnBack.setOnClickListener { sendCommand("keyevent", mapOf("key" to "KEYCODE_BACK")) }
        btnHome.setOnClickListener { sendCommand("keyevent", mapOf("key" to "KEYCODE_HOME")) }
        btnTap.setOnClickListener { tapSelectedElement(false) }
        btnLongClick.setOnClickListener { tapSelectedElement(true) }

        remoteView.onElementClicked = { node, _, _ ->
            showElementInfo(node)
        }

        remoteView.onUserTapAt = { x, y ->
            hideElementInfo()
            sendCommand("tap", mapOf("x" to x.roundToInt(), "y" to y.roundToInt()))
        }

        // Auto-connect if last config exists
        showSettingsDialog()
    }

    private fun showSettingsDialog() {
        val inputLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)

            fun addLabel(text: String) = TextView(context).apply {
                this.text = text
                textSize = 14f
                setTextColor(0xFF666666.toInt())
                setPadding(0, 12, 0, 4)
            }

            fun addEdit(initial: String, hint: String, inputType: Int = InputType.TYPE_CLASS_TEXT) =
                EditText(context).apply {
                    setText(initial)
                    this.hint = hint
                    this.inputType = inputType
                    setPadding(8, 8, 8, 8)
                    setBackgroundResource(android.R.drawable.editbox_background)
                }

            addView(addLabel("中继服务器地址:"))
            val etHost = addEdit(config.relayHost, "例如: 192.168.1.100 或 your-domain.com")
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
            .setTitle("连接设置")
            .setView(inputLayout)
            .setPositiveButton("连接") { _, _ ->
                val host = inputLayout.getChildAt(1).let { (it as EditText).text.toString() }
                val port = inputLayout.getChildAt(3).let { (it as EditText).text.toString() }
                val room = inputLayout.getChildAt(5).let { (it as EditText).text.toString() }
                val token = inputLayout.getChildAt(7).let { (it as EditText).text.toString() }

                config = Config(host, port.toIntOrNull() ?: 8080, token, room)
                connect()
            }
            .setNegativeButton("取消") { _, _ ->
                if (wsClient == null) {
                    Toast.makeText(this, "请配置服务器连接", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun connect() {
        disconnect()

        scope.launch {
            try {
                val uri = URI("ws://${config.relayHost}:${config.relayPort}")
                tvStatus.text = "状态: 正在连接..."
                tvStatus.setBackgroundColor(0xFFFFF9C4.toInt())

                wsClient = object : WebSocketClient(uri) {
                    override fun onOpen(handshake: ServerHandshake) {
                        scope.launch {
                            tvStatus.text = "状态: 已连接，正在认证..."
                            tvStatus.setBackgroundColor(0xFFFFF9C4.toInt())
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
                        scope.launch {
                            tvStatus.text = "状态: 已断开 ($reason)"
                            tvStatus.setBackgroundColor(0xFFFFCDD2.toInt())
                        }
                    }

                    override fun onError(ex: Exception) {
                        scope.launch {
                            tvStatus.text = "状态: 错误 - ${ex.message}"
                            tvStatus.setBackgroundColor(0xFFFFCDD2.toInt())
                        }
                    }
                }

                wsClient?.connectBlocking()
            } catch (e: Exception) {
                tvStatus.text = "状态: 连接失败 - ${e.message}"
                tvStatus.setBackgroundColor(0xFFFFCDD2.toInt())
                Toast.makeText(this, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun disconnect() {
        wsClient?.close()
        wsClient = null
        for (deferred in pendingCmds.values) {
            deferred.completeExceptionally(Exception("Disconnected"))
        }
        pendingCmds.clear()
    }

    private fun handleRelayMessage(msg: String) {
        try {
            val json = JSONObject(msg)
            when (json.optString("type")) {
                "auth_ok" -> {
                    scope.launch {
                        tvStatus.text = "状态: 认证成功，注册房间..."
                        tvStatus.setBackgroundColor(0xFFFFF9C4.toInt())
                    }
                    wsClient?.send(JSONObject().apply {
                        put("type", "register")
                        put("role", "controller")
                        put("roomId", config.roomId)
                    }.toString())
                }
                "registered" -> {
                    scope.launch {
                        tvStatus.text = "状态: 已注册房间 ${config.roomId}，等待被控端..."
                        tvStatus.setBackgroundColor(0xFFFFF9C4.toInt())
                    }
                }
                "peer_connected" -> {
                    scope.launch {
                        tvStatus.text = "状态: 双方已连接 ✓"
                        tvStatus.setBackgroundColor(0xFFC8E6C9.toInt())
                        Toast.makeText(this@MainActivity, "被控端已连接！", Toast.LENGTH_LONG).show()
                    }
                }
                "peer_disconnected" -> {
                    scope.launch {
                        tvStatus.text = "状态: 被控端已断开"
                        tvStatus.setBackgroundColor(0xFFFFCDD2.toInt())
                        Toast.makeText(this@MainActivity, "被控端已断开", Toast.LENGTH_SHORT).show()
                    }
                }
                "command_result" -> {
                    val cmdId = json.optString("cmdId", "")
                    val deferred = pendingCmds.remove(cmdId)
                    if (deferred != null) {
                        deferred.complete(json)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun sendCommandAwait(action: String, params: Map<String, Any> = mapOf()): JSONObject? {
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

        wsClient?.send(cmd.toString())

        return withTimeoutOrNull(10000) { deferred.await() }
    }

    private fun sendCommand(action: String, params: Map<String, Any> = mapOf()) {
        scope.launch {
            val result = sendCommandAwait(action, params)
            if (result == null) {
                Toast.makeText(this@MainActivity, "命令超时或失败", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this@MainActivity, "UI解析失败，XML格式异常", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                val stderr = result?.optString("stderr", "")
                tvStatus.text = "状态: UI获取失败"
                Toast.makeText(this@MainActivity, "UI获取失败: $stderr", Toast.LENGTH_LONG).show()
            }

            progressBar.visibility = View.GONE
        }
    }

    private fun parseUiXml(xml: String): ScreenInfo? {
        try {
            // Extract <hierarchy ... rotation="N"> ... </hierarchy>
            val hierarchyStart = xml.indexOf("<hierarchy")
            val hierarchyEnd = xml.indexOf("</hierarchy>")
            if (hierarchyStart < 0 || hierarchyEnd < 0) return null

            val hierarchyStr = xml.substring(hierarchyStart, hierarchyEnd + "</hierarchy>".length)

            // Get device size from bounds of root node
            val rootBoundsMatch = Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""").find(hierarchyStr)
            val (screenW, screenH) = if (rootBoundsMatch != null) {
                val (_, left, top, right, bottom) = rootBoundsMatch.groupValues
                right.toInt() to bottom.toInt()
            } else 1080 to 2340

            val rootNode = parseXmlNode(hierarchyStr, 0)
            if (rootNode == null || rootNode.bounds.isEmpty()) return null

            // Filter to only clickable/visible nodes for display
            val allNodes = flattenNodes(rootNode).filter { node ->
                node.enabled &&
                node.bounds.width() > 10 &&
                node.bounds.height() > 10 &&
                (node.clickable || node.text.isNotEmpty() || node.contentDesc.isNotEmpty())
            }

            return ScreenInfo(width = screenW, height = screenH, nodes = allNodes)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun parseXmlNode(xml: String, startIndex: Int): UiNode? {
        val nodeStart = xml.indexOf("<node", startIndex)
        if (nodeStart < 0) return null

        val nodeEnd = findClosingTag(xml, nodeStart)
        if (nodeEnd < 0) return null

        val nodeContent = xml.substring(nodeStart, nodeEnd)

        // Parse attributes
        fun attr(name: String): String {
            val regex = Regex("""$name="([^"]*)"""")
            return regex.find(nodeContent)?.groupValues?.getOrElse(1) { "" } ?: ""
        }

        fun attrBool(name: String): Boolean = attr(name) == "true"

        fun attrBounds(name: String): Rect {
            val m = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(attr(name))
            return if (m != null) {
                val (_, l, t, r, b) = m.groupValues
                Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
            } else Rect(0, 0, 0, 0)
        }

        val bounds = attrBounds("bounds")
        if (bounds.isEmpty()) return null

        val node = UiNode(
            bounds = bounds,
            text = attr("text"),
            contentDesc = attr("content-desc"),
            resourceId = attr("resource-id"),
            className = attr("class"),
            clickable = attrBool("clickable"),
            longClickable = attrBool("long-clickable"),
            scrollable = attrBool("scrollable"),
            checkable = attrBool("checkable"),
            checked = attrBool("checked"),
            enabled = attrBool("enabled"),
            password = attrBool("password"),
            depth = 0
        )

        // Parse children
        var childStart = nodeContent.indexOf("<node", 1)
        while (childStart > 0) {
            val childEnd = findClosingTag(nodeContent, childStart)
            if (childEnd < 0) break

            val child = parseXmlNode(nodeContent, childStart)
            if (child != null) {
                child.depth = node.depth + 1
                node.children.add(child)
            }

            childStart = nodeContent.indexOf("<node", childEnd)
        }

        return node
    }

    private fun findClosingTag(xml: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < xml.length) {
            if (xml[i] == '<') {
                if (i + 1 < xml.length && xml[i + 1] == '/') {
                    depth--
                    if (depth < 0) {
                        val end = xml.indexOf(">", i)
                        return if (end > 0) end + 1 else start + 1
                    }
                } else if (i + 1 < xml.length && xml[i + 1] != '!') {
                    // Self-closing tag
                    val closePos = xml.indexOf(">", i)
                    if (closePos > 0 && xml[closePos - 1] == '/') {
                        // self-closing, don't change depth
                    } else {
                        depth++
                    }
                }
            }
            i++
        }
        return start + 1
    }

    private fun flattenNodes(node: UiNode): List<UiNode> {
        val result = mutableListOf<UiNode>()
        result.add(node)
        for (child in node.children) {
            result.addAll(flattenNodes(child))
        }
        return result
    }

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

    private fun hideElementInfo() {
        layoutElementInfo.visibility = View.GONE
    }

    private fun tapSelectedElement(longPress: Boolean) {
        val node = remoteView.getSelectedNode() ?: return
        val cx = node.bounds.centerX()
        val cy = node.bounds.centerY()

        if (longPress) {
            sendCommand("swipe", mapOf(
                "x1" to cx, "y1" to cy,
                "x2" to cx, "y2" to cy,
                "duration" to 1000
            ))
        } else {
            sendCommand("tap", mapOf("x" to cx, "y" to cy))
        }

        hideElementInfo()
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }
}
