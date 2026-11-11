package com.samuca.bloodlight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Barra de sangue com o vampiro como marcador.
 *
 * level: 0 = vampiro à esquerda (tela mais escura, vampiro protegido),
 *        1 = vampiro à direita (sem escurecimento, vampiro sofrendo).
 */
class BloodBarView(context: Context) : View(context) {

    var onLevelChanged: ((Float) -> Unit)? = null
    var onLevelCommitted: ((Float) -> Unit)? = null
    var onOutsideTap: (() -> Unit)? = null

    var level: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val reduceMotion: Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // ------------------------------------------------------------------ arte
    private fun load(id: Int): Bitmap {
        val o = BitmapFactory.Options()
        o.inScaled = false
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        return BitmapFactory.decodeResource(resources, id, o)
    }

    private val heads = arrayOf(load(R.drawable.head_0), load(R.drawable.head_1), load(R.drawable.head_2))
    private val blinks = arrayOf(load(R.drawable.head_0_blink), load(R.drawable.head_1_blink), heads[2])
    private val capL = load(R.drawable.cap_left)
    private val capR = load(R.drawable.cap_right)

    // pontos de referência no desenho original (pixels da arte)
    private val headW = heads[0].width.toFloat()
    private val headH = heads[0].height.toFloat()
    private val headAnchorX = 185f
    private val headAnchorY = 175f
    private val capAnchorY = 123f
    private val eyes = floatArrayOf(176f, 121f, 236f, 125f)
    private val mouthX = 209f
    private val mouthY = 181f
    private val headGemX = 185f
    private val headGemY = 252f
    private val capLGemX = 78f
    private val capRGemX = 92f
    private val capGemY = 120f

    // ------------------------------------------------------------------ geometria
    private var s = 1f
    private var hs = 1f
    private var cy = 0f
    private var barLeft = 0f
    private var barRight = 0f
    private var tubeL = 0f
    private var tubeR = 0f
    private var tubeHalf = 0f
    private var shieldHalf = 0f
    private var xMin = 0f
    private var xMax = 1f

    private val tubePath = Path()
    private val tmpPath = Path()
    private val batPath = Path()
    private val rect = RectF()

    private var emptyShader: Shader? = null
    private var bloodShader: Shader? = null
    private var railTopShader: Shader? = null
    private var railBotShader: Shader? = null
    private var glossShader: Shader? = null

    // ------------------------------------------------------------------ tintas
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0xCCFF2A1A.toInt(), 0x00FF2A1A), null, Shader.TileMode.CLAMP)
    }
    private val eyeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(0xFFFFD0C0.toInt(), 0xCCFF2010.toInt(), 0x00FF0000),
            floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP
        )
    }
    private val burnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0xFF1A0505.toInt(), 0x001A0505), null, Shader.TileMode.CLAMP)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            -1f, 0f, 1f, 0f,
            intArrayOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP
        )
    }
    private val mistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0xFF3A0508.toInt(), 0x003A0508), null, Shader.TileMode.CLAMP)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0xFF000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
    }
    private val gemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(0f, 0f, 1f, intArrayOf(0xFFFF3020.toInt(), 0x00FF1010), null, Shader.TileMode.CLAMP)
    }

    // malha que deforma a cabeça: a gola fica presa na barra, rosto e cabelo se mexem
    private val meshVerts = FloatArray((MESH + 1) * (MESH + 1) * 2)
    private var poseCos = 1f
    private var poseSin = 0f
    private var poseTx = 0f
    private var poseTy = 0f
    private var poseScale = 1f
    private var hairAmp = 0f
    private var hairPush = 0f
    private var wxOut = 0f
    private var wyOut = 0f

    private var beatPhase = 0f
    private var beat = 0f
    private var fangTimer = 3f

    private val batFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val batStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    // ------------------------------------------------------------------ gotas presas na barra
    private val dripFrac = floatArrayOf(0.05f, 0.16f, 0.27f, 0.39f, 0.50f, 0.62f, 0.73f, 0.84f, 0.93f)
    private val dripMax = floatArrayOf(46f, 18f, 30f, 14f, 38f, 20f, 26f, 16f, 30f)   // dp
    private val dripLen = FloatArray(dripFrac.size)
    private val dripTimer = FloatArray(dripFrac.size) { 1.5f + Random.nextFloat() * 5f }

    // ------------------------------------------------------------------ partículas
    private class P(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val life: Float, val size: Float, val kind: Int, val phase: Float
    ) {
        var age = 0f
    }

    private val particles = ArrayList<P>()
    private val bubbles = ArrayList<P>()
    private val pending = ArrayList<P>()

    // ------------------------------------------------------------------ animação
    private var lastNs = 0L
    private var time = 0f
    private var flow = 0f
    private var kick = 0f
    private var extreme = 0
    private var vel = 0f
    private var lastMx = Float.NaN
    private var lean = 0f
    private var slosh = 0f
    private var shakeT = 0f

    private var blinkT = -1f
    private var nextBlink = 2.5f
    private var pendingBlink = false

    private var idleType = -1
    private var idleT = 0f
    private var idleDur = 0f
    private var nextIdle = 3f

    private var shineT = -1f
    private var nextShine = 3f
    private var glintT = -1f
    private var glintWhich = 0
    private var nextGlint = 1.5f
    private var gulpT = -1f

    private var fireAcc = 0f
    private var emberAcc = 0f
    private var bubbleAcc = 0f
    private var sipAcc = 0f

    // ------------------------------------------------------------------ toque
    private var dragging = false
    private var outside = false
    private var grabDx = 0f
    private var downX = 0f
    private var downY = 0f

    init {
        contentDescription = context.getString(R.string.bar_desc)
        isClickable = true
    }

    private fun markerX(): Float = xMin + level * (xMax - xMin)
    private fun burnAmount(): Float = (1f - (1f - level) * 2.2f).coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun rnd(a: Float, b: Float): Float = a + Random.nextFloat() * (b - a)
    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val pad = 18f * dp
        barLeft = pad
        barRight = w - pad
        s = (barRight - barLeft) / 1250f
        cy = h * 0.5f
        tubeL = barLeft + capL.width * s - 8f * s
        tubeR = barRight - capR.width * s + 8f * s
        tubeHalf = 50f * s
        hs = s * 1.05f
        shieldHalf = 152f * hs
        xMin = tubeL + shieldHalf * 0.8f
        xMax = tubeR - shieldHalf * 0.8f

        tubePath.rewind()
        rect.set(tubeL, cy - tubeHalf, tubeR, cy + tubeHalf)
        tubePath.addRoundRect(rect, tubeHalf, tubeHalf, Path.Direction.CW)

        val top = cy - tubeHalf
        val bottom = cy + tubeHalf
        emptyShader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xFF0C0204.toInt(), 0xFF2A0A0F.toInt(), 0xFF3A1016.toInt(), 0xFF1A0508.toInt(), 0xFF080203.toInt()),
            floatArrayOf(0f, 0.3f, 0.5f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        bloodShader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0xFF4A0000.toInt(), 0xFFB00000.toInt(), 0xFFE01010.toInt(), 0xFFA00000.toInt(), 0xFF3A0000.toInt()),
            floatArrayOf(0f, 0.25f, 0.5f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        glossShader = LinearGradient(0f, top, 0f, top + 22f * s, 0x99FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        railTopShader = LinearGradient(
            0f, top - 17f * s, 0f, top + 1f * s,
            intArrayOf(0xFFB5B0AE.toInt(), 0xFF6A6567.toInt(), 0xFF2C2729.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        railBotShader = LinearGradient(
            0f, bottom - 1f * s, 0f, bottom + 15f * s,
            intArrayOf(0xFF2C2729.toInt(), 0xFF7A7577.toInt(), 0xFF3A3436.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        lastMx = Float.NaN
    }

    // ================================================================== desenho
    override fun onDraw(canvas: Canvas) {
        if (s <= 0f || xMax <= xMin) return

        val now = System.nanoTime()
        var dt = if (lastNs == 0L) 0f else (now - lastNs) / 1_000_000_000f
        lastNs = now
        dt = min(dt, 0.05f)
        time += dt
        step(dt)

        val mx = markerX()
        val burn = burnAmount()

        drawMist(canvas)
        drawAura(canvas, mx, burn)
        drawTube(canvas, mx)
        drawRails(canvas)
        drawDrips(canvas)
        drawCaps(canvas)
        drawGlow(canvas, mx, burn)
        drawHeadShadow(canvas, mx)
        drawHead(canvas, mx, burn)
        drawParticles(canvas)

        if (!reduceMotion) postInvalidateOnAnimation()
    }

    private fun drawTube(canvas: Canvas, mx: Float) {
        val top = cy - tubeHalf
        val bottom = cy + tubeHalf
        canvas.save()
        canvas.clipPath(tubePath)

        fillPaint.shader = emptyShader
        canvas.drawRect(tubeL, top, tubeR, bottom, fillPaint)

        if (mx > tubeL) {
            fillPaint.shader = bloodShader
            canvas.drawRect(tubeL, top, mx, bottom, fillPaint)

            // correntes de luz no sangue; mexem mais quando o vampiro anda
            linePaint.shader = null
            linePaint.strokeWidth = 3f * s
            val waveBoost = 1f + slosh * 2.2f
            for (k in 0..2) {
                tmpPath.rewind()
                val base = cy + (k - 1) * tubeHalf * 0.5f
                val amp = tubeHalf * (0.10f + 0.04f * k) * waveBoost
                val speed = 0.8f + 0.5f * k
                var x = tubeL
                tmpPath.moveTo(x, base + amp * sin(x * 0.0048f / s + flow * speed + k))
                while (x < mx) {
                    x += 14f
                    val xx = min(x, mx)
                    tmpPath.lineTo(xx, base + amp * sin(xx * 0.0048f / s + flow * speed + k))
                }
                linePaint.color = if (k == 1) 0x33FF8A80 else 0x22FF6A60
                canvas.drawPath(tmpPath, linePaint)
            }

            // manchas escuras que correm devagar em direção ao vampiro
            fillPaint.shader = null
            for (i in 0..4) {
                val u = (i * 0.23f + flow * 0.03f * (1f + i * 0.2f)) % 1f
                val bx = tubeL + u * (mx - tubeL)
                val by = cy + sin(i * 2.1f + flow * 0.5f) * tubeHalf * 0.5f
                fillPaint.color = 0x48300004
                rect.set(bx - 42f * s, by - 13f * s, bx + 42f * s, by + 13f * s)
                canvas.drawOval(rect, fillPaint)
            }

            // pulso: uma onda clara atravessa o sangue a cada batida
            if (!reduceMotion) {
                val ph = beatPhase % 1f
                val px = tubeL + ph * (mx - tubeL)
                bandPaint.alpha = (70f * (1f - ph)).toInt()
                canvas.save()
                canvas.translate(px, 0f)
                canvas.scale(70f * s, 1f)
                canvas.drawRect(-1f, top, 1f, bottom, bandPaint)
                canvas.restore()
            }

            // bolhas
            linePaint.strokeWidth = 1f * dp
            for (b in bubbles) {
                val a = 1f - b.age / b.life
                linePaint.color = Color.argb((120 * a).toInt(), 255, 170, 160)
                canvas.drawCircle(b.x, b.y, b.size, linePaint)
                fillPaint.shader = null
                fillPaint.color = Color.argb((150 * a).toInt(), 255, 220, 215)
                canvas.drawCircle(b.x - b.size * 0.35f, b.y - b.size * 0.35f, b.size * 0.3f, fillPaint)
            }

            fillPaint.shader = glossShader
            canvas.drawRect(tubeL, top, mx, top + 22f * s, fillPaint)
        }
        fillPaint.shader = null

        // marcas gravadas na parte vazia
        linePaint.shader = null
        linePaint.strokeWidth = 2.5f * s
        for (k in 1..9) {
            val tx = tubeL + k / 10f * (tubeR - tubeL)
            if (tx < mx + shieldHalf * 0.5f) continue
            val a = if (reduceMotion) 110 else (95 + 60 * sin(time * 1.5f + k)).toInt()
            linePaint.color = Color.argb(a, 150, 24, 34)
            val hh = if (k == 5) 16f * s else 9f * s
            canvas.drawLine(tx, cy - hh, tx, cy + hh, linePaint)
        }

        // vidro: brilho fino em cima e um reflexo que passeia
        linePaint.color = 0x30FFFFFF
        linePaint.strokeWidth = 3f * s
        canvas.drawLine(tubeL + tubeHalf * 0.8f, top + 10f * s, tubeR - tubeHalf * 0.8f, top + 10f * s, linePaint)
        if (!reduceMotion) {
            val gx = lerp(tubeL + tubeHalf, tubeR - tubeHalf, 0.5f + 0.5f * sin(time * 0.25f))
            fillPaint.color = 0x38FFFFFF
            rect.set(gx - 46f * s, top + 14f * s, gx + 46f * s, top + 24f * s)
            canvas.drawOval(rect, fillPaint)
        }
        canvas.restore()
    }

    private fun drawMist(canvas: Canvas) {
        for (i in 0..5) {
            val u = if (reduceMotion) (i + 0.5f) / 6f else (i * 0.19f + time * 0.012f * (1 + i % 3)) % 1f
            val x = tubeL + u * (tubeR - tubeL)
            val y = cy + sin(time * 0.3f + i * 1.9f) * tubeHalf * 1.6f
            val edge = sin(PI.toFloat() * u)
            mistPaint.alpha = (70f * edge).toInt()
            drawUnit(canvas, mistPaint, x, y, (150f + 40f * (i % 3)) * s)
        }
    }

    /** Raios de luz atrás do vampiro no lado claro; sombra viva atrás dele no lado escuro. */
    private fun drawAura(canvas: Canvas, mx: Float, burn: Float) {
        val hy = cy - 45f * hs
        if (burn > 0.05f) {
            fillPaint.shader = null
            val r = shieldHalf * 2.7f
            for (i in 0 until 10) {
                val flick = if (reduceMotion) 0.8f else 0.6f + 0.4f * sin(time * 3f + i * 1.3f)
                fillPaint.color = Color.argb((burn * 46f * flick).toInt(), 255, 200, 120)
                val a = (i * 36f + (if (reduceMotion) 0f else time * 12f)) * PI.toFloat() / 180f
                val h = 0.085f
                tmpPath.rewind()
                tmpPath.moveTo(mx, hy)
                tmpPath.lineTo(mx + cos(a - h) * r, hy + sin(a - h) * r)
                tmpPath.lineTo(mx + cos(a + h) * r, hy + sin(a + h) * r)
                tmpPath.close()
                canvas.drawPath(tmpPath, fillPaint)
            }
        }
        val dark = smooth((1f - level - 0.55f) / 0.45f)
        if (dark > 0.02f) {
            val pulse = if (reduceMotion) 1f else 0.9f + 0.1f * sin(time * 1.4f)
            shadowPaint.alpha = (dark * 150f).toInt()
            drawUnit(canvas, shadowPaint, mx, hy, shieldHalf * 2f * pulse)
            linePaint.shader = null
            linePaint.strokeWidth = 3f * dp
            for (i in 0 until 5) {
                val rr = shieldHalf * (1.2f + 0.1f * sin(time * 1.1f + i * 2f))
                val start = (if (reduceMotion) 0f else time * 46f * (if (i % 2 == 0) 1f else -0.7f)) + i * 72f
                linePaint.color = Color.argb((dark * 170f).toInt(), 60, 8, 38)
                rect.set(mx - rr, hy - rr, mx + rr, hy + rr)
                canvas.drawArc(rect, start, 38f, false, linePaint)
            }
        }
    }

    private fun drawHeadShadow(canvas: Canvas, mx: Float) {
        shadowPaint.alpha = 120
        canvas.save()
        canvas.translate(mx + 6f * s, cy + 12f * s)
        canvas.scale(shieldHalf * 1.05f, tubeHalf * 1.7f)
        canvas.drawCircle(0f, 0f, 1f, shadowPaint)
        canvas.restore()
    }

    private fun drawRails(canvas: Canvas) {
        val left = barLeft + capL.width * s * 0.7f
        val right = barRight - capR.width * s * 0.7f
        val top = cy - tubeHalf
        val bottom = cy + tubeHalf

        fillPaint.shader = railTopShader
        rect.set(left, top - 17f * s, right, top + 1f * s)
        canvas.drawRoundRect(rect, 6f * s, 6f * s, fillPaint)
        if (shineT >= 0f) drawShine(canvas, left, right)

        fillPaint.shader = railBotShader
        rect.set(left, bottom - 1f * s, right, bottom + 15f * s)
        canvas.drawRoundRect(rect, 6f * s, 6f * s, fillPaint)
        if (shineT >= 0f) drawShine(canvas, left, right)
        fillPaint.shader = null

        linePaint.shader = null
        linePaint.strokeWidth = 1.2f * dp
        linePaint.color = 0x55FFFFFF
        canvas.drawLine(left + 8f * s, top - 15f * s, right - 8f * s, top - 15f * s, linePaint)
        canvas.drawLine(left + 8f * s, bottom + 2f * s, right - 8f * s, bottom + 2f * s, linePaint)

        // rebites
        for (k in 0 until 12) {
            val rx = left + (k + 0.5f) / 12f * (right - left)
            for (ry in floatArrayOf(top - 8f * s, bottom + 7f * s)) {
                fillPaint.color = 0xFF3A3537.toInt()
                canvas.drawCircle(rx, ry, 4.2f * s, fillPaint)
                fillPaint.color = 0xFFA9A4A5.toInt()
                canvas.drawCircle(rx - 0.8f * s, ry - 0.8f * s, 2.6f * s, fillPaint)
            }
        }
    }

    /** Reflexo que corre pelo metal (desenhado no retângulo atual em [rect]). */
    private fun drawShine(canvas: Canvas, left: Float, right: Float) {
        val sx = lerp(left, right, smooth(shineT))
        bandPaint.alpha = 176
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(sx, 0f)
        canvas.scale(90f * s, 1f)
        canvas.drawRect(-1f, rect.top, 1f, rect.bottom, bandPaint)
        canvas.restore()
    }

    private fun dripScale(): Float = (s / 0.8f).coerceIn(0.7f, 1.3f)

    private fun drawDrips(canvas: Canvas) {
        val y0 = cy + tubeHalf + 12f * s
        for (i in dripFrac.indices) {
            val len = dripLen[i]
            if (len < 0.5f) continue
            val x = tubeL + dripFrac[i] * (tubeR - tubeL)
            val wobble = if (reduceMotion) 1f else 1f + 0.07f * sin(time * 1.3f + i * 1.7f)
            val l = len * wobble
            val r = (3.2f + dripMax[i] * 0.07f) * dp * 0.9f * dripScale()

            linePaint.shader = null
            linePaint.color = 0xFFC00808.toInt()
            linePaint.strokeWidth = r * 1.1f
            canvas.drawLine(x, y0, x, y0 + l, linePaint)
            fillPaint.color = 0xFFD01010.toInt()
            canvas.drawCircle(x, y0 + l, r, fillPaint)
            fillPaint.color = 0x88FFC0C0.toInt()
            canvas.drawCircle(x - r * 0.3f, y0 + l - r * 0.3f, r * 0.28f, fillPaint)
        }
    }

    private fun drawCaps(canvas: Canvas) {
        val top = cy - capAnchorY * s
        rect.set(barLeft, top, barLeft + capL.width * s, top + capL.height * s)
        canvas.drawBitmap(capL, null, rect, bmpPaint)
        val rl = barRight - capR.width * s
        rect.set(rl, top, barRight, top + capR.height * s)
        canvas.drawBitmap(capR, null, rect, bmpPaint)

        gemPaint.alpha = (60f + 150f * beat).toInt().coerceIn(0, 255)
        val gr = (34f + 22f * beat) * s
        drawUnit(canvas, gemPaint, barLeft + capLGemX * s, top + capGemY * s, gr)
        drawUnit(canvas, gemPaint, rl + capRGemX * s, top + capGemY * s, gr)

        if (glintT >= 0f) {
            if (glintWhich == 0) drawGlint(canvas, barLeft + capLGemX * s, top + capGemY * s, 38f * s)
            if (glintWhich == 1) drawGlint(canvas, rl + capRGemX * s, top + capGemY * s, 38f * s)
        }
    }

    /** Brilho em forma de estrela sobre uma pedra vermelha. */
    private fun drawGlint(canvas: Canvas, x: Float, y: Float, r: Float) {
        val k = sin(PI.toFloat() * glintT.coerceIn(0f, 1f))
        if (k <= 0.01f) return
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(glintT * 70f)
        linePaint.shader = null
        linePaint.color = Color.argb((230 * k).toInt(), 255, 240, 240)
        linePaint.strokeWidth = r * 0.08f
        canvas.drawLine(-r * k, 0f, r * k, 0f, linePaint)
        canvas.drawLine(0f, -r * k, 0f, r * k, linePaint)
        linePaint.strokeWidth = r * 0.05f
        val d = r * 0.42f * k
        canvas.drawLine(-d, -d, d, d, linePaint)
        canvas.drawLine(-d, d, d, -d, linePaint)
        fillPaint.shader = null
        fillPaint.color = Color.argb((255 * k).toInt(), 255, 255, 255)
        canvas.drawCircle(0f, 0f, r * 0.13f * k, fillPaint)
        canvas.restore()
    }

    private fun drawUnit(canvas: Canvas, paint: Paint, x: Float, y: Float, r: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(r, r)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restore()
    }

    private fun drawGlow(canvas: Canvas, mx: Float, burn: Float) {
        if (burn <= 0.02f) return
        val pulse = if (reduceMotion) 1f else 0.88f + 0.12f * sin(time * 5f)
        glowPaint.alpha = (burn * 0.55f * pulse * 255f).toInt().coerceIn(0, 255)
        drawUnit(canvas, glowPaint, mx, cy, shieldHalf * 1.7f)
    }

    /** Desloca um ponto do desenho conforme a pose atual. Resultado em wxOut/wyOut. */
    private fun warp(x: Float, y: Float) {
        val wy = smooth((225f - y) / 70f)
        val lat = 1f - smooth((abs(x - 190f) - 78f) / 40f)
        val wx = lerp(1f, lat, smooth((y - 80f) / 22f))
        val w = wy * wx
        val px = x - 195f
        val py = y - 215f
        val rx = px * poseCos - py * poseSin
        val ry = px * poseSin + py * poseCos
        var nx = x + w * ((rx * poseScale - px) + poseTx)
        var ny = y + w * ((ry * poseScale - py) + poseTy)
        val hw = smooth((125f - y) / 95f) * wx
        if (hw > 0f) {
            nx += hw * (hairAmp * sin(time * 2.3f + x * 0.021f + y * 0.03f) + hairPush)
            ny += hw * hairAmp * 0.35f * sin(time * 3.1f + x * 0.035f)
        }
        wxOut = nx
        wyOut = ny
    }

    private fun drawHead(canvas: Canvas, mx: Float, burn: Float) {
        val p = 1f - level
        val t = (p * 2f).coerceIn(0f, 1.999f)
        val idx = t.toInt()
        val frac = smooth((t - idx - 0.2f) / 0.6f)

        val scale = hs * (1f + 0.07f * sin(kick * PI.toFloat()))
        var offX = 0f
        var rot = 0f
        poseTx = 0f
        poseTy = 0f
        poseScale = 1f
        hairAmp = 0f
        hairPush = 0f
        if (!reduceMotion) {
            rot = lean * 0.65f
            val breathSpeed = lerp(1.4f, 2.6f, level)
            poseScale = 1f + 0.014f * sin(time * breathSpeed) + 0.008f * beat
            poseTy = sin(time * 1.6f) * 2f
            if (gulpT >= 0f) poseTy -= 4f * sin(gulpT * 6f * PI.toFloat()) * (1f - gulpT)
            if (burn > 0.3f) poseTx += sin(time * 38f) * 1.4f * burn
            if (shakeT > 0f) poseTx += sin(time * 70f) * 5f * (shakeT / 0.7f)
            if (extreme == 1) offX = sin(time * 60f) * 3f * dp * kick
            if (idleType == IDLE_TILT) {
                val u = idleT / idleDur
                rot += sin(u * 2f * PI.toFloat()) * 6f * sin(u * PI.toFloat())
            }
            if (idleType == IDLE_LOOK) {
                val u = idleT / idleDur
                poseTx += sin(u * 2f * PI.toFloat()) * 9f * sin(u * PI.toFloat())
            }
            hairAmp = 3f + 3f * burn
            hairPush = -lean * 1.4f
        }
        val rad = rot.coerceIn(-8f, 8f) * PI.toFloat() / 180f
        poseCos = cos(rad)
        poseSin = sin(rad)

        var k = 0
        for (j in 0..MESH) {
            for (i in 0..MESH) {
                warp(i * headW / MESH, j * headH / MESH)
                meshVerts[k++] = wxOut
                meshVerts[k++] = wyOut
            }
        }

        canvas.save()
        canvas.translate(mx + offX, cy)
        canvas.scale(scale, scale)
        canvas.translate(-headAnchorX, -headAnchorY)

        val blinking = blinkT >= 0f
        val set = if (blinking) blinks else heads

        val layer = canvas.saveLayer(-40f, -40f, headW + 40f, headH + 40f, null)
        bmpPaint.alpha = 255
        canvas.drawBitmapMesh(set[idx], MESH, MESH, meshVerts, 0, null, 0, bmpPaint)
        if (frac > 0.01f) {
            bmpPaint.alpha = (frac * 255f).toInt()
            canvas.drawBitmapMesh(set[idx + 1], MESH, MESH, meshVerts, 0, null, 0, bmpPaint)
            bmpPaint.alpha = 255
        }
        if (burn > 0.03f) {
            val a = (burn * 0.75f * 255f).toInt().coerceIn(0, 255)
            burnPaint.alpha = a
            warp(150f, 152f); drawUnit(canvas, burnPaint, wxOut, wyOut, 44f)
            warp(256f, 156f); drawUnit(canvas, burnPaint, wxOut, wyOut, 40f)
            warp(205f, 84f); drawUnit(canvas, burnPaint, wxOut, wyOut, 56f)
            burnPaint.alpha = (a * 0.7f).toInt()
            warp(196f, 210f); drawUnit(canvas, burnPaint, wxOut, wyOut, 36f)
        }
        canvas.restoreToCount(layer)

        // pedra da gola pulsa com a batida
        gemPaint.alpha = (50f + 150f * beat).toInt().coerceIn(0, 255)
        drawUnit(canvas, gemPaint, headGemX, headGemY, 30f + 20f * beat)

        // olhos acesos quando exposto à luz, com rastro ao arrastar rápido
        if (!blinking) {
            val pulse = if (reduceMotion) 1f else 0.7f + 0.3f * sin(time * 6.5f)
            val eg = smooth((level - 0.4f) / 0.6f) * pulse
            if (eg > 0.02f) {
                val trail = (-vel / (900f * dp)).coerceIn(-1f, 1f) * 46f
                for (e in 0..1) {
                    warp(eyes[e * 2], eyes[e * 2 + 1])
                    if (abs(trail) > 4f) {
                        linePaint.shader = null
                        linePaint.strokeWidth = 7f
                        linePaint.color = Color.argb((eg * 110f).toInt(), 255, 40, 20)
                        canvas.drawLine(wxOut, wyOut, wxOut + trail, wyOut - abs(trail) * 0.12f, linePaint)
                    }
                    eyeGlowPaint.alpha = (eg * 200f).toInt().coerceIn(0, 255)
                    drawUnit(canvas, eyeGlowPaint, wxOut, wyOut, 22f)
                }
            }
        }
        if (glintT >= 0f && glintWhich == 2) drawGlint(canvas, headGemX, headGemY, 36f)
        canvas.restore()
    }

    private fun drawParticles(canvas: Canvas) {
        fillPaint.shader = null
        linePaint.shader = null
        for (pt in particles) {
            val a = (pt.age / pt.life).coerceIn(0f, 1f)
            when (pt.kind) {
                SMOKE -> {
                    fillPaint.color = Color.argb((85 * (1f - a)).toInt(), 0x77, 0x70, 0x70)
                    canvas.drawCircle(pt.x, pt.y, pt.size * (1f + a * 1.6f), fillPaint)
                }
                FIRE -> {
                    val r: Int
                    val g: Int
                    val b: Int
                    if (a < 0.5f) {
                        val k = a / 0.5f
                        r = 255; g = lerp(225f, 110f, k).toInt(); b = lerp(80f, 20f, k).toInt()
                    } else {
                        val k = (a - 0.5f) / 0.5f
                        r = lerp(255f, 170f, k).toInt(); g = lerp(110f, 20f, k).toInt(); b = lerp(20f, 10f, k).toInt()
                    }
                    fillPaint.color = Color.argb((230f * (1f - a)).toInt().coerceIn(0, 255), r, g, b)
                    canvas.drawCircle(pt.x, pt.y, pt.size * (1f - 0.5f * a), fillPaint)
                }
                EMBER -> {
                    val al = (210f * sin(PI.toFloat() * a)).toInt().coerceIn(0, 255)
                    fillPaint.color = Color.argb(al, 255, (90 + 80 * (1f - a)).toInt(), 40)
                    canvas.drawCircle(pt.x, pt.y, pt.size, fillPaint)
                    fillPaint.color = Color.argb(al / 4, 255, 60, 30)
                    canvas.drawCircle(pt.x, pt.y, pt.size * 2.6f, fillPaint)
                }
                DROP -> {
                    val stretch = 1f + min(1.2f, abs(pt.vy) / (900f * dp))
                    fillPaint.color = 0xFFD01010.toInt()
                    rect.set(pt.x - pt.size, pt.y - pt.size * stretch, pt.x + pt.size, pt.y + pt.size)
                    canvas.drawOval(rect, fillPaint)
                    fillPaint.color = 0x88FFC0C0.toInt()
                    canvas.drawCircle(pt.x - pt.size * 0.3f, pt.y - pt.size * 0.2f, pt.size * 0.28f, fillPaint)
                }
                SPLASH -> {
                    val rr = pt.size * (1f + a * 3f)
                    linePaint.color = Color.argb((200 * (1f - a)).toInt(), 210, 20, 20)
                    linePaint.strokeWidth = 1.5f * dp
                    rect.set(pt.x - rr, pt.y - rr * 0.35f, pt.x + rr, pt.y + rr * 0.35f)
                    canvas.drawOval(rect, linePaint)
                }
                SIP -> {
                    fillPaint.color = Color.argb((235 * (1f - a * 0.7f)).toInt(), 205, 12, 12)
                    canvas.drawCircle(pt.x, pt.y, pt.size * (1f - a * 0.5f), fillPaint)
                }
                BAT -> drawBat(canvas, pt, a)
                SPARK -> {
                    linePaint.color = Color.argb((255 * (1f - a)).toInt(), 255, (230 - 120 * a).toInt(), 140)
                    linePaint.strokeWidth = pt.size
                    canvas.drawLine(pt.x, pt.y, pt.x - pt.vx * 0.035f, pt.y - pt.vy * 0.035f, linePaint)
                }
                FANG -> {
                    fillPaint.color = Color.argb((255 * (1f - a * a)).toInt(), 200, 10, 14)
                    rect.set(pt.x - pt.size, pt.y - pt.size * 1.7f, pt.x + pt.size, pt.y + pt.size)
                    canvas.drawOval(rect, fillPaint)
                }
            }
        }
    }

    private fun drawBat(canvas: Canvas, b: P, a: Float) {
        val alpha = if (a > 0.7f) (1f - a) / 0.3f else min(1f, a / 0.08f)
        val sz = b.size
        val x = b.x
        val y = b.y
        val fy = sin(b.age * 22f + b.phase) * sz * 0.55f
        batPath.rewind()
        for (side in intArrayOf(-1, 1)) {
            val d = side.toFloat()
            batPath.moveTo(x + d * sz * 0.1f, y - sz * 0.05f)
            batPath.quadTo(x + d * sz * 0.55f, y - sz * 0.45f - fy * 0.3f, x + d * sz, y - sz * 0.15f - fy)
            batPath.lineTo(x + d * sz * 0.8f, y + sz * 0.12f - fy * 0.6f)
            batPath.lineTo(x + d * sz * 0.6f, y + sz * 0.02f - fy * 0.45f)
            batPath.lineTo(x + d * sz * 0.4f, y + sz * 0.18f - fy * 0.3f)
            batPath.lineTo(x + d * sz * 0.22f, y + sz * 0.06f - fy * 0.15f)
            batPath.lineTo(x + d * sz * 0.1f, y + sz * 0.15f)
            batPath.close()
            batPath.moveTo(x + d * sz * 0.11f, y - sz * 0.12f)
            batPath.lineTo(x + d * sz * 0.08f, y - sz * 0.34f)
            batPath.lineTo(x + d * sz * 0.01f, y - sz * 0.16f)
            batPath.close()
        }
        batPath.addOval(x - sz * 0.13f, y - sz * 0.2f, x + sz * 0.13f, y + sz * 0.25f, Path.Direction.CW)
        batFill.color = Color.argb((alpha * 255).toInt().coerceIn(0, 255), 22, 6, 9)
        canvas.drawPath(batPath, batFill)
        batStroke.color = Color.argb((alpha * 150).toInt().coerceIn(0, 255), 255, 40, 40)
        batStroke.strokeWidth = 0.8f * dp
        canvas.drawPath(batPath, batStroke)
        fillPaint.color = Color.argb((alpha * 255).toInt().coerceIn(0, 255), 255, 40, 30)
        canvas.drawCircle(x - sz * 0.05f, y - sz * 0.07f, sz * 0.035f, fillPaint)
        canvas.drawCircle(x + sz * 0.05f, y - sz * 0.07f, sz * 0.035f, fillPaint)
    }

    // ================================================================== lógica de quadro
    private fun step(dt: Float) {
        val mx = markerX()
        if (lastMx.isNaN()) lastMx = mx
        val v = if (dt > 0f) (mx - lastMx) / dt else 0f
        lastMx = mx
        vel += (v - vel) * min(1f, dt * 12f)

        // gotas presas: crescem onde há sangue, recolhem onde não há, e às vezes caem
        val y0 = cy + tubeHalf + 12f * s
        for (i in dripFrac.indices) {
            val x = tubeL + dripFrac[i] * (tubeR - tubeL)
            val visible = x < mx - shieldHalf * 0.55f && x > tubeL + 4f * s
            val target = if (visible) dripMax[i] * dp * dripScale() else 0f
            dripLen[i] = if (reduceMotion) target else dripLen[i] + (target - dripLen[i]) * min(1f, dt * 3.2f)
            if (!reduceMotion && visible && dripLen[i] > target * 0.8f) {
                dripTimer[i] -= dt
                if (dripTimer[i] <= 0f) {
                    val r = (3.2f + dripMax[i] * 0.07f) * dp * 0.9f * dripScale()
                    addParticle(P(x, y0 + dripLen[i], 0f, 0f, rnd(0.45f, 0.7f), r, DROP, 0f))
                    dripLen[i] *= 0.3f
                    dripTimer[i] = rnd(2.5f, 8f)
                }
            }
        }

        if (reduceMotion) return

        flow += dt * (1f + slosh * 3f)
        kick = max(0f, kick - dt * 3.5f)
        shakeT = max(0f, shakeT - dt)
        slosh = max(slosh - dt * 0.8f, min(1f, abs(vel) / (700f * dp)))
        val leanTarget = (vel / (900f * dp)).coerceIn(-1f, 1f) * 11f
        lean += (leanTarget - lean) * min(1f, dt * 8f)

        // piscar
        if (blinkT >= 0f) {
            blinkT += dt
            if (blinkT > BLINK_TIME) {
                blinkT = -1f
                if (pendingBlink) {
                    pendingBlink = false
                    nextBlink = 0.12f
                }
            }
        } else {
            nextBlink -= dt
            if (nextBlink <= 0f) {
                blinkT = 0f
                nextBlink = rnd(2.2f, 6.5f)
            }
        }

        // ações aleatórias quando parado
        if (idleType >= 0) {
            idleT += dt
            if (idleT >= idleDur) idleType = -1
        } else if (!dragging) {
            nextIdle -= dt
            if (nextIdle <= 0f) {
                startIdle()
                nextIdle = rnd(3.5f, 7.5f)
            }
        }

        if (shineT >= 0f) {
            shineT += dt / 1.3f
            if (shineT > 1f) shineT = -1f
        } else {
            nextShine -= dt
            if (nextShine <= 0f) {
                shineT = 0f
                nextShine = rnd(5f, 10f)
            }
        }
        if (glintT >= 0f) {
            glintT += dt / 0.7f
            if (glintT > 1f) glintT = -1f
        } else {
            nextGlint -= dt
            if (nextGlint <= 0f) {
                glintT = 0f
                glintWhich = Random.nextInt(3)
                nextGlint = rnd(1.8f, 5f)
            }
        }
        if (gulpT >= 0f) {
            gulpT += dt / 0.9f
            if (gulpT > 1f) gulpT = -1f
        }

        // batida do coração: mais rápida quanto mais luz
        beatPhase += dt * lerp(0.75f, 1.9f, level)
        val ph = beatPhase % 1f
        beat = max(max(0f, 1f - ph / 0.15f), 0.6f * max(0f, 1f - abs(ph - 0.3f) / 0.12f))

        // gota de sangue escorrendo da presa quando ele está alimentado
        if (level < 0.35f) {
            fangTimer -= dt
            if (fangTimer <= 0f) {
                fangTimer = rnd(2.2f, 5.5f)
                val side = if (Random.nextBoolean()) -11f else 11f
                addParticle(
                    P(
                        mx + (mouthX + side - headAnchorX) * hs, cy + (mouthY + 8f - headAnchorY) * hs,
                        0f, 0f, 0.55f, 2.3f * dp, FANG, 0f
                    )
                )
            }
        }

        val burn = burnAmount()
        if (burn > 0.02f) {
            fireAcc += dt * 34f * burn
            while (fireAcc >= 1f) {
                fireAcc -= 1f
                spawnFire(mx, burn, false)
            }
        }
        emberAcc += dt * (2.5f + 6f * burn)
        while (emberAcc >= 1f) {
            emberAcc -= 1f
            addParticle(
                P(
                    rnd(tubeL, tubeR), cy + tubeHalf + rnd(-10f, 25f) * dp,
                    rnd(-6f, 6f) * dp, -rnd(18f, 42f) * dp,
                    rnd(2f, 3.5f), rnd(1.2f, 2.5f) * dp, EMBER, rnd(0f, 6.28f)
                )
            )
        }
        val bloodEnd = mx - shieldHalf * 0.7f
        if (bloodEnd - tubeL > tubeHalf * 2f) {
            bubbleAcc += dt * (4f + slosh * 14f)
            while (bubbleAcc >= 1f) {
                bubbleAcc -= 1f
                if (bubbles.size < 40) {
                    bubbles.add(
                        P(
                            rnd(tubeL + tubeHalf, bloodEnd), cy + tubeHalf * 0.75f,
                            0f, -rnd(14f, 32f) * dp, 4f, rnd(1.2f, 3f) * dp, BUBBLE, rnd(0f, 6.28f)
                        )
                    )
                }
            }
        }
        // sangue subindo até a boca enquanto o vampiro bebe (indo para a esquerda)
        if (dragging && vel < -30f * dp) {
            sipAcc += dt * 45f
            while (sipAcc >= 1f) {
                sipAcc -= 1f
                spawnSip(mx)
            }
            if (gulpT < 0f) gulpT = 0f
        }

        val it = particles.iterator()
        while (it.hasNext()) {
            val pt = it.next()
            pt.age += dt
            if (pt.age >= pt.life) {
                if (pt.kind == DROP) pending.add(P(pt.x, pt.y, 0f, 0f, 0.5f, pt.size, SPLASH, 0f))
                it.remove()
                continue
            }
            when (pt.kind) {
                EMBER -> {
                    pt.x += (pt.vx + sin(time * 2f + pt.phase) * 10f * dp) * dt
                    pt.y += pt.vy * dt
                }
                DROP, FANG -> {
                    pt.vy += 1600f * dp * dt
                    pt.y += pt.vy * dt
                }
                SPARK -> {
                    pt.vy += 900f * dp * dt
                    pt.x += pt.vx * dt
                    pt.y += pt.vy * dt
                }
                SMOKE -> {
                    pt.x += (pt.vx + sin(pt.age * 5f + pt.phase) * 26f * dp) * dt
                    pt.y += pt.vy * dt
                }
                BAT -> {
                    pt.vy -= 30f * dp * dt
                    pt.x += pt.vx * dt
                    pt.y += (pt.vy + sin(pt.age * 6f + pt.phase) * 35f * dp) * dt
                }
                SPLASH -> {}
                else -> {
                    pt.x += pt.vx * dt
                    pt.y += pt.vy * dt
                }
            }
        }
        for (pt in pending) addParticle(pt)
        pending.clear()

        val top = cy - tubeHalf * 0.75f
        val bi = bubbles.iterator()
        while (bi.hasNext()) {
            val b = bi.next()
            b.age += dt
            b.x += sin(b.age * 5f + b.phase) * 6f * dp * dt
            b.y += b.vy * dt
            if (b.age >= b.life || b.y < top || b.x > bloodEnd) bi.remove()
        }
    }

    private fun addParticle(p: P) {
        if (particles.size < 240) particles.add(p)
    }

    private fun spawnFire(mx: Float, burn: Float, burst: Boolean) {
        val topY = cy - 140f * hs
        val smoke = Random.nextFloat() < 0.35f
        val speed = if (burst) 1.8f else 1f
        addParticle(
            P(
                mx + (Random.nextFloat() - 0.5f) * shieldHalf * 1.3f,
                topY + Random.nextFloat() * 20f * s,
                (Random.nextFloat() - 0.5f) * 22f * dp * speed * (if (burst) 3f else 1f),
                -(40f + Random.nextFloat() * 50f) * dp * speed,
                rnd(0.7f, 1.4f),
                (3f + Random.nextFloat() * 4f) * dp * (0.5f + burn * 0.8f) * (if (burst) 1.3f else 1f),
                if (smoke) SMOKE else FIRE, rnd(0f, 6.28f)
            )
        )
    }

    private fun spawnBats(n: Int) {
        val mx = markerX()
        for (i in 0 until n) {
            addParticle(
                P(
                    mx + rnd(-30f, 30f) * dp, cy - 60f * hs,
                    rnd(-280f, 280f) * dp, -rnd(110f, 250f) * dp,
                    rnd(1.8f, 2.7f), rnd(13f, 22f) * dp, BAT, rnd(0f, 6.28f)
                )
            )
        }
    }

    private fun spawnSparks(x: Float, dir: Float) {
        for (i in 0 until 16) {
            addParticle(
                P(
                    x, cy + rnd(-0.6f, 0.6f) * tubeHalf,
                    dir * rnd(120f, 420f) * dp, -rnd(60f, 380f) * dp,
                    rnd(0.35f, 0.7f), rnd(1.2f, 2.2f) * dp, SPARK, 0f
                )
            )
        }
    }

    private fun spawnSip(mx: Float) {
        val sx = mx - shieldHalf * rnd(0.55f, 0.85f)
        val sy = cy + rnd(-0.5f, 0.5f) * tubeHalf
        val tx = mx + (mouthX - headAnchorX) * hs
        val ty = cy + (mouthY - headAnchorY) * hs
        val life = 0.28f
        addParticle(P(sx, sy, (tx - sx) / life, (ty - sy) / life, life, rnd(2.5f, 4.5f) * dp, SIP, 0f))
    }

    private fun startIdle() {
        val options = ArrayList<Int>()
        options.add(IDLE_TILT)
        options.add(IDLE_LOOK)
        options.add(IDLE_BLINK2)
        if (level < 0.4f) {
            options.add(IDLE_BATS); options.add(IDLE_BATS)
        }
        if (level < 0.6f) options.add(IDLE_GULP)
        if (level > 0.7f) {
            options.add(IDLE_ROAR); options.add(IDLE_ROAR)
        }
        idleType = options[Random.nextInt(options.size)]
        idleT = 0f
        idleDur = when (idleType) {
            IDLE_TILT -> 1.3f
            IDLE_LOOK -> 1.6f
            IDLE_ROAR -> 0.9f
            else -> 0.3f
        }
        when (idleType) {
            IDLE_BLINK2 -> {
                blinkT = 0f
                pendingBlink = true
            }
            IDLE_BATS -> spawnBats(3 + Random.nextInt(3))
            IDLE_GULP -> gulpT = 0f
            IDLE_ROAR -> {
                shakeT = 0.7f
                repeat(18) { spawnFire(markerX(), 1f, true) }
            }
        }
    }

    // ================================================================== toque
    private fun moveTo(x: Float) {
        val nl = ((x - xMin) / (xMax - xMin)).coerceIn(0f, 1f)
        if (nl == level) return
        level = nl
        val e = when {
            nl >= 0.995f -> 1
            nl <= 0.005f -> -1
            else -> 0
        }
        if (e != 0 && e != extreme) {
            kick = 1f
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (!reduceMotion) {
                if (e == -1) {
                    spawnBats(4 + Random.nextInt(3))
                    gulpT = 0f
                    spawnSparks(tubeL + tubeHalf, 1f)
                } else {
                    spawnSparks(tubeR - tubeHalf, -1f)
                    shakeT = 0.7f
                    repeat(22) { spawnFire(markerX(), 1f, true) }
                }
            }
        }
        extreme = e
        onLevelChanged?.invoke(nl)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                if (abs(event.y - cy) > 200f * hs) {
                    outside = true
                    return true
                }
                outside = false
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                idleType = -1
                grabDx = event.x - markerX()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) moveTo(event.x - grabDx)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    onLevelCommitted?.invoke(level)
                } else if (outside && abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop) {
                    performClick()
                    onOutsideTap?.invoke()
                }
                outside = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) onLevelCommitted?.invoke(level)
                dragging = false
                outside = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    companion object {
        private const val FIRE = 0
        private const val SMOKE = 1
        private const val EMBER = 2
        private const val DROP = 3
        private const val SPLASH = 4
        private const val SIP = 5
        private const val BAT = 6
        private const val BUBBLE = 7
        private const val SPARK = 8
        private const val FANG = 9
        private const val MESH = 10

        private const val IDLE_TILT = 0
        private const val IDLE_LOOK = 1
        private const val IDLE_BLINK2 = 2
        private const val IDLE_BATS = 3
        private const val IDLE_GULP = 4
        private const val IDLE_ROAR = 5

        private const val BLINK_TIME = 0.14f
    }
}
