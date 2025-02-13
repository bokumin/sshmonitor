package net.bokumin45.sshmonitor

import android.content.Context
import android.graphics.Canvas
import android.text.TextPaint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import android.os.Handler
import android.os.Looper

class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatTextView(context, attrs, defStyle) {

    private var textWidth = 0f
    private var xPos = 0f
    private val pixelStep = 2f  // 1回の移動ピクセル数
    private val updateInterval = 50L  // 更新間隔（ミリ秒）
    private val handler = Handler(Looper.getMainLooper())
    private var isScrolling = false

    init {
        paintFlags = paintFlags or TextPaint.ANTI_ALIAS_FLAG
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setupScrolling()
    }

    private fun setupScrolling() {
        textWidth = paint.measureText(text.toString())
        xPos = width.toFloat()
        startScrolling()
    }

    private val scrollRunnable = object : Runnable {
        override fun run() {
            if (isScrolling) {
                xPos -= pixelStep

                if (xPos < -textWidth) {
                    xPos = width.toFloat()
                }

                invalidate()
                handler.postDelayed(this, updateInterval)
            }
        }
    }

    private fun startScrolling() {
        if (!isScrolling) {
            isScrolling = true
            handler.post(scrollRunnable)
        }
    }

    private fun stopScrolling() {
        isScrolling = false
        handler.removeCallbacks(scrollRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        val textHeight = paint.descent() - paint.ascent()
        val textOffset = textHeight / 2 - paint.descent()
        val yPos = height / 2f + textOffset

        canvas.drawText(text.toString(), xPos, yPos, paint)

        if (xPos < 0) {
            canvas.drawText(text.toString(), xPos + textWidth + width, yPos, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startScrolling()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopScrolling()
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        if (width > 0) {
            setupScrolling()
        }
    }
}