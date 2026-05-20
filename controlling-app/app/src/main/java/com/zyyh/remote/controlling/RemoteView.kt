package com.zyyh.remote.controlling

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.json.JSONArray
import org.json.JSONObject

data class UiNode(
    val bounds: Rect,
    val text: String,
    val contentDesc: String,
    val resourceId: String,
    val className: String,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    val depth: Int,
    val children: MutableList<UiNode> = mutableListOf()
)

data class ScreenInfo(
    val width: Int,
    val height: Int,
    val nodes: List<UiNode>
)

class RemoteView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var screenInfo: ScreenInfo? = null
    var onElementClicked: ((UiNode, Float, Float) -> Unit)? = null
    var onUserTapAt: ((Float, Float) -> Unit)? = null

    private val paintBounds = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val paintBoundsClickable = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val paintBoundsSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334CAF50")
        style = Paint.Style.FILL
    }

    private val paintFillClickable = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#332196F3")
        style = Paint.Style.FILL
    }

    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 28f
    }

    private val paintTextSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 20f
    }

    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private var selectedNode: UiNode? = null
    private var scaleX = 1f
    private var scaleY = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var deviceWidth = 1080
    private var deviceHeight = 2340

    fun setScreenInfo(info: ScreenInfo) {
        screenInfo = info
        deviceWidth = info.width
        deviceHeight = info.height
        calculateScale()
        selectedNode = null
        invalidate()
    }

    private fun calculateScale() {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val deviceRatio = deviceWidth.toFloat() / deviceHeight.toFloat()
        val viewRatio = viewW / viewH

        if (deviceRatio > viewRatio) {
            scaleX = viewW / deviceWidth
            scaleY = scaleX
            offsetX = 0f
            offsetY = (viewH - deviceHeight * scaleY) / 2f
        } else {
            scaleY = viewH / deviceHeight
            scaleX = scaleY
            offsetX = (viewW - deviceWidth * scaleX) / 2f
            offsetY = 0f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateScale()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

        // Draw device outline
        canvas.drawRect(offsetX, offsetY,
            offsetX + deviceWidth * scaleX,
            offsetY + deviceHeight * scaleY, paintBorder)

        val info = screenInfo ?: return

        drawNodes(canvas, info.nodes)
    }

    private fun drawNodes(canvas: Canvas, nodes: List<UiNode>) {
        for (node in nodes) {
            drawNode(canvas, node)
        }
    }

    private fun drawNode(canvas: Canvas, node: UiNode) {
        val left = offsetX + node.bounds.left * scaleX
        val top = offsetY + node.bounds.top * scaleY
        val right = offsetX + node.bounds.right * scaleX
        val bottom = offsetY + node.bounds.bottom * scaleY

        if (right - left < 10 || bottom - top < 10) {
            drawChildren(canvas, node)
            return
        }

        // Draw background
        val fillPaint = if (node.clickable) paintFillClickable else paintFill
        canvas.drawRect(left, top, right, bottom, fillPaint)

        // Draw border
        val borderPaint = when {
            node == selectedNode -> paintBoundsSelected
            node.clickable -> paintBoundsClickable
            else -> paintBounds
        }
        canvas.drawRect(left, top, right, bottom, borderPaint)

        // Draw text
        val displayText = when {
            node.text.isNotEmpty() -> node.text
            node.contentDesc.isNotEmpty() -> "[${node.contentDesc}]"
            node.className.contains("Button") -> "Button"
            node.className.contains("Image") -> "Image"
            else -> ""
        }

        if (displayText.isNotEmpty()) {
            val (paint, maxWidth) = if (node.clickable) paintText else paintTextSmall
            val textW = paint.measureText(displayText)
            val availW = right - left - 8
            val textToDraw = if (textW > availW && availW > 0) {
                val charWidth = textW / displayText.length
                val maxChars = ((availW / charWidth).toInt() - 2).coerceAtLeast(3)
                displayText.take(maxChars) + ".."
            } else displayText

            val textX = left + 4
            val textY = top + paint.textSize + 4
            canvas.drawText(textToDraw, textX, textY.coerceAtMost(bottom - 4), paint)
        }

        drawChildren(canvas, node)
    }

    private fun drawChildren(canvas: Canvas, node: UiNode) {
        for (child in node.children) {
            drawNode(canvas, child)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val tx = (event.x - offsetX) / scaleX
            val ty = (event.y - offsetY) / scaleY

            if (tx < 0 || tx > deviceWidth || ty < 0 || ty > deviceHeight) return true

            val info = screenInfo ?: return true
            val touched = findTouchedNode(info.nodes, tx.toInt(), ty.toInt())

            if (touched != null && touched.clickable) {
                selectedNode = touched
                invalidate()
                onElementClicked?.invoke(touched, event.x, event.y)
            } else {
                selectedNode = null
                invalidate()
                onUserTapAt?.invoke(tx, ty)
            }
        }
        return true
    }

    private fun findTouchedNode(nodes: List<UiNode>, x: Int, y: Int): UiNode? {
        for (node in nodes.reversed()) {
            if (node.bounds.contains(x, y)) {
                val child = findTouchedNode(node.children, x, y)
                return child ?: node
            }
        }
        return null
    }

    fun getSelectedNode(): UiNode? = selectedNode
}
