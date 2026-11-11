package com.scenacam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** Previzualizare cu lățimea ecranului și înălțimea dată de raportul de aspect al fotografiei. */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    private var ratio = 4f / 3f

    fun setAspect(longSide: Int, shortSide: Int) {
        if (longSide > 0 && shortSide > 0) {
            ratio = longSide.toFloat() / shortSide
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * ratio).roundToInt())
    }
}

/**
 * Container care are exact forma previzualizării (lățimea ecranului, înălțimea după raportul de aspect),
 * astfel încât tot ce e așezat în el (cadrele fețelor, punctul de focalizare, indicatorul de zoom)
 * să se raporteze la imaginea vizibilă, nu la tot ecranul.
 */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private var ratio = 4f / 3f

    fun setAspect(longSide: Int, shortSide: Int) {
        if (longSide > 0 && shortSide > 0) {
            ratio = longSide.toFloat() / shortSide
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * ratio).roundToInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }
}

/** Desenează peste previzualizare fețele detectate și punctul de focalizare atins. */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFFFFD54F.toInt()
    }
    private val tapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFFFFFFFF.toInt()
    }

    private var faces: List<NormRect> = emptyList()
    private var tapX = -1f
    private var tapY = -1f

    /** Fețe în coordonatele previzualizării (0..1). */
    fun setFaces(list: List<NormRect>) {
        faces = list
        invalidate()
    }

    fun setTap(x: Float, y: Float) {
        tapX = x
        tapY = y
        invalidate()
    }

    fun clearTap() {
        tapX = -1f
        tapY = -1f
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = 10f * density
        for (f in faces) {
            canvas.drawRoundRect(f.left * w, f.top * h, f.right * w, f.bottom * h, radius, radius, facePaint)
        }
        if (tapX >= 0f && tapY >= 0f) {
            canvas.drawCircle(tapX * w, tapY * h, 34f * density, tapPaint)
            canvas.drawCircle(tapX * w, tapY * h, 3f * density, tapPaint)
        }
    }
}
