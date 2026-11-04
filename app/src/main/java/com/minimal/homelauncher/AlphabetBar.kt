package com.minimal.homelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Side bar with # A-Z. Touch/drag on it jumps the app list to that letter. */
class AlphabetBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onLetter: ((Char) -> Unit)? = null

    private val letters = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 170
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cell = height / letters.length.toFloat()
        val maxSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics
        )
        paint.textSize = min(cell * 0.8f, maxSize)
        val offset = (paint.ascent() + paint.descent()) / 2f
        for (i in letters.indices) {
            canvas.drawText(letters[i].toString(), width / 2f, cell * i + cell / 2f - offset, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val idx = (event.y / height * letters.length).toInt()
                    .coerceIn(0, letters.length - 1)
                onLetter?.invoke(letters[idx])
            }
        }
        return true
    }
}
