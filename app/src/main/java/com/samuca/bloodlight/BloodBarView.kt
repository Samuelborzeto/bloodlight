package com.samuca.bloodlight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Barra de sangue com o vampiro como marcador.
 *
 * Camadas, de baixo para cima:
 * 1. tubo vazio  2. sangue  3. trilhos metálicos  4. gotas  5. pontas da barra
 * 6. brilho vermelho  7. personagem (3 estados + marcas de queimadura)  8. fogo e fumaça
 *
 * level: 0 = vampiro todo à esquerda (tela mais escura, vampiro protegido),
 *        1 = vampiro todo à direita (sem escurecimento, vampiro sofrendo).
 */
class BloodBarView(context: Context) : View(context) {

    var onLevelChanged: ((Float) -> Unit)? = null
    var onLevelCommitted: ((Float) -> Unit)? = null

    var level: Float = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val reduceMotion: Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    // ------------------------------------------------------------------ arte
    private fun load(id: Int): Bitmap {
        val o = BitmapFactory.Options()
        o.inScaled = false
        o.inPreferredConfig = Bitmap.Config.ARGB_8888
        return BitmapFactory.decodeResource(resources, id, o)
    }

    private val heads = arrayOf(load(R.drawable.head_0), load(R.drawable.head_1), load(R.drawable.head_2))
    private val capL = load(R.drawable.cap_left)
    private val capR = load(R.drawable.cap_right)

    // medidas da arte original (pixels do desenho)
    private val headW = heads[0].width.toFloat()
    private val headH = heads[0].height.toFloat()
    private val headAnchorX = 185f      // centro do escudo
    private val headAnchorY = 175f      // linha central da barra dentro do desenho
    private val capAnchorY = 123f

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
    private val unitGlow = RadialGradient(
        0f, 0f, 1f, intArrayOf(0xCCFF2A1A.toInt(), 0x00FF2A1A), null, Shader.TileMode.CLAMP
    )
    private val unitBurn = RadialGradient(
        0f, 0f, 1f, intArrayOf(0xFF1A0505.toInt(), 0x001A0505), null, Shader.TileMode.CLAMP
    )
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = unitGlow }
    private val burnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = unitBurn
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }

    // ------------------------------------------------------------------ gotas
    private val dripFrac = floatArrayOf(0.05f, 0.16f, 0.27f, 0.39f, 0.50f, 0.62f, 0.73f, 0.84f, 0.93f)
    private val dripMax = floatArrayOf(46f, 18f, 30f, 14f, 38f, 20f, 26f, 16f, 30f)   // dp
    private val dripLen = FloatArray(dripFrac.size)

    // ------------------------------------------------------------------ partículas
    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val life: Float, var age: Float, val size: Float, val smoke: Boolean
    )

    private val particles = ArrayList<Particle>()
    private var spawnAcc = 0f

    // ------------------------------------------------------------------ animação
    private var lastNs = 0L
    private var time = 0f
    private var flow = 0f
    private var kick = 0f
    private var extreme = 0           // -1 escuro, 1 claro, 0 meio

    // ------------------------------------------------------------------ toque
    private var dragging = false
    private var grabDx = 0f

    init {
        contentDescription = context.getString(R.string.bar_desc)
        isClickable = true
    }

    private fun markerX(): Float = xMin + level * (xMax - xMin)
    private fun burnAmount(): Float = (1f - (1f - level) * 2.2f).coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
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
        cy = h * 0.44f
        val capLW = capL.width * s
        val capRW = capR.width * s
        tubeL = barLeft + capLW - 8f * s
        tubeR = barRight - capRW + 8f * s
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
        glossShader = LinearGradient(
            0f, top, 0f, top + 22f * s,
            0x99FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        val railTop = top - 17f * s
        railTopShader = LinearGradient(
            0f, railTop, 0f, top + 1f * s,
            intArrayOf(0xFFB5B0AE.toInt(), 0xFF6A6567.toInt(), 0xFF2C2729.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        railBotShader = LinearGradient(
            0f, bottom - 1f * s, 0f, bottom + 15f * s,
            intArrayOf(0xFF2C2729.toInt(), 0xFF7A7577.toInt(), 0xFF3A3436.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    // ------------------------------------------------------------------ desenho
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

        drawTube(canvas, mx)
        drawRails(canvas)
        drawDrips(canvas, mx)
        drawCaps(canvas)
        drawGlow(canvas, mx, burn)
        drawHead(canvas, mx, burn)
        drawParticles(canvas)

        if (!reduceMotion) postInvalidateOnAnimation()
    }

    private fun drawTube(canvas: Canvas, mx: Float) {
        val top = cy - tubeHalf
        val bottom = cy + tubeHalf

        canvas.save()
        canvas.clipPath(tubePath)

        // tubo vazio
        fillPaint.shader = emptyShader
        canvas.drawRect(tubeL, top, tubeR, bottom, fillPaint)

        // sangue até o vampiro
        if (mx > tubeL) {
            fillPaint.shader = bloodShader
            canvas.drawRect(tubeL, top, mx, bottom, fillPaint)

            // veios de luz que escorrem devagar dentro do sangue
            linePaint.shader = null
            linePaint.strokeWidth = 3f * s
            for (k in 0..2) {
                tmpPath.rewind()
                val base = cy + (k - 1) * tubeHalf * 0.5f
                val amp = tubeHalf * (0.10f + 0.04f * k)
                val speed = 0.8f + 0.5f * k
                var x = tubeL
                tmpPath.moveTo(x, base + amp * sin(x * 0.012f / s * 0.4f + flow * speed + k))
                while (x < mx) {
                    x += 14f
                    tmpPath.lineTo(min(x, mx), base + amp * sin(x * 0.012f / s * 0.4f + flow * speed + k))
                }
                linePaint.color = if (k == 1) 0x33FF8A80 else 0x22FF6A60
                canvas.drawPath(tmpPath, linePaint)
            }

            // brilho superior
            fillPaint.shader = glossShader
            canvas.drawRect(tubeL, top, mx, top + 22f * s, fillPaint)
        }
        fillPaint.shader = null
        canvas.restore()
    }

    private fun drawRails(canvas: Canvas) {
        val capLW = capL.width * s
        val capRW = capR.width * s
        val left = barLeft + capLW * 0.7f
        val right = barRight - capRW * 0.7f
        val top = cy - tubeHalf
        val bottom = cy + tubeHalf

        fillPaint.shader = railTopShader
        rect.set(left, top - 17f * s, right, top + 1f * s)
        canvas.drawRoundRect(rect, 6f * s, 6f * s, fillPaint)

        fillPaint.shader = railBotShader
        rect.set(left, bottom - 1f * s, right, bottom + 15f * s)
        canvas.drawRoundRect(rect, 6f * s, 6f * s, fillPaint)
        fillPaint.shader = null

        // fio de luz nos trilhos
        linePaint.shader = null
        linePaint.strokeWidth = 1.2f * dp
        linePaint.color = 0x55FFFFFF
        canvas.drawLine(left + 8f * s, top - 15f * s, right - 8f * s, top - 15f * s, linePaint)
        canvas.drawLine(left + 8f * s, bottom + 2f * s, right - 8f * s, bottom + 2f * s, linePaint)
    }

    private fun drawDrips(canvas: Canvas, mx: Float) {
        val y0 = cy + tubeHalf + 12f * s
        for (i in dripFrac.indices) {
            val len = dripLen[i]
            if (len < 0.5f) continue
            val x = tubeL + dripFrac[i] * (tubeR - tubeL)
            val wobble = if (reduceMotion) 1f else 1f + 0.07f * sin(time * 1.3f + i * 1.7f)
            val l = len * wobble
            val r = (3.2f + dripMax[i] * 0.07f) * dp * 0.9f * (s / 0.8f).coerceIn(0.7f, 1.3f)

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
        rect.set(
            barLeft, cy - capAnchorY * s,
            barLeft + capL.width * s, cy - capAnchorY * s + capL.height * s
        )
        canvas.drawBitmap(capL, null, rect, bmpPaint)
        rect.set(
            barRight - capR.width * s, cy - capAnchorY * s,
            barRight, cy - capAnchorY * s + capR.height * s
        )
        canvas.drawBitmap(capR, null, rect, bmpPaint)
    }

    private fun drawGlow(canvas: Canvas, mx: Float, burn: Float) {
        if (burn <= 0.02f) return
        val pulse = if (reduceMotion) 1f else 0.88f + 0.12f * sin(time * 5f)
        glowPaint.alpha = (burn * 0.55f * pulse * 255f).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(mx, cy)
        canvas.scale(shieldHalf * 1.7f, shieldHalf * 1.7f)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restore()
    }

    private fun drawHead(canvas: Canvas, mx: Float, burn: Float) {
        val p = 1f - level
        val t = (p * 2f).coerceIn(0f, 1.999f)
        val idx = t.toInt()
        val frac = smooth((t - idx - 0.2f) / 0.6f)

        // reação nos extremos e tremor quando exposto à luz
        var scale = hs * (1f + 0.07f * sin(kick * PI.toFloat()))
        var offX = 0f
        var offY = 0f
        if (!reduceMotion) {
            offY = sin(time * 1.6f) * 1.5f * dp
            if (burn > 0.3f) offX = sin(time * 38f) * 1.1f * dp * burn
            if (extreme == 1) offX += sin(time * 60f) * 3f * dp * kick
        }

        canvas.save()
        canvas.translate(mx - headAnchorX * scale + offX, cy - headAnchorY * scale + offY)
        canvas.scale(scale, scale)

        val layer = canvas.saveLayer(0f, 0f, headW, headH, null)
        bmpPaint.alpha = 255
        canvas.drawBitmap(heads[idx], 0f, 0f, bmpPaint)
        if (frac > 0.01f) {
            bmpPaint.alpha = (frac * 255f).toInt()
            canvas.drawBitmap(heads[idx + 1], 0f, 0f, bmpPaint)
            bmpPaint.alpha = 255
        }
        if (burn > 0.03f) {
            val a = (burn * 0.75f * 255f).toInt().coerceIn(0, 255)
            burnPaint.alpha = a
            drawBurnSpot(canvas, 150f, 152f, 44f)
            drawBurnSpot(canvas, 256f, 156f, 40f)
            drawBurnSpot(canvas, 205f, 84f, 56f)
            burnPaint.alpha = (a * 0.7f).toInt()
            drawBurnSpot(canvas, 196f, 210f, 36f)
        }
        canvas.restoreToCount(layer)
        canvas.restore()
    }

    private fun drawBurnSpot(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(r, r)
        canvas.drawCircle(0f, 0f, 1f, burnPaint)
        canvas.restore()
    }

    private fun drawParticles(canvas: Canvas) {
        for (pt in particles) {
            val a = (pt.age / pt.life).coerceIn(0f, 1f)
            if (pt.smoke) {
                fillPaint.color = ((0x55 * (1f - a)).toInt() shl 24) or 0x777070
                canvas.drawCircle(pt.x, pt.y, pt.size * (1f + a * 1.6f), fillPaint)
            } else {
                val r: Int
                val g: Int
                val b: Int
                if (a < 0.5f) {
                    val k = a / 0.5f
                    r = 255
                    g = lerp(225f, 110f, k).toInt()
                    b = lerp(80f, 20f, k).toInt()
                } else {
                    val k = (a - 0.5f) / 0.5f
                    r = lerp(255f, 170f, k).toInt()
                    g = lerp(110f, 20f, k).toInt()
                    b = lerp(20f, 10f, k).toInt()
                }
                val alpha = (230f * (1f - a)).toInt().coerceIn(0, 255)
                fillPaint.color = (alpha shl 24) or (r shl 16) or (g shl 8) or b
                canvas.drawCircle(pt.x, pt.y, pt.size * (1f - 0.5f * a), fillPaint)
            }
        }
    }

    // ------------------------------------------------------------------ lógica de quadro
    private fun step(dt: Float) {
        val mx = markerX()

        // gotas: aparecem onde há sangue (fora da área do vampiro) e recolhem onde não há
        for (i in dripFrac.indices) {
            val x = tubeL + dripFrac[i] * (tubeR - tubeL)
            val visible = x < mx - shieldHalf * 0.55f && x > tubeL + 4f * s
            val target = if (visible) dripMax[i] * dp * (s / 0.8f).coerceIn(0.7f, 1.3f) else 0f
            dripLen[i] = if (reduceMotion) target else dripLen[i] + (target - dripLen[i]) * min(1f, dt * 3.2f)
        }

        if (reduceMotion) return

        flow += dt
        kick = max(0f, kick - dt * 3.5f)

        // fogo e fumaça sobem quanto mais exposto à luz
        val burn = burnAmount()
        if (burn > 0.02f) {
            spawnAcc += dt * 34f * burn
            while (spawnAcc >= 1f) {
                spawnAcc -= 1f
                spawnParticle(mx, burn)
            }
        }
        val it = particles.iterator()
        while (it.hasNext()) {
            val pt = it.next()
            pt.age += dt
            if (pt.age >= pt.life) {
                it.remove()
                continue
            }
            pt.x += pt.vx * dt
            pt.y += pt.vy * dt
        }
    }

    private fun spawnParticle(mx: Float, burn: Float) {
        if (particles.size > 70) return
        val topY = cy - 140f * hs
        val smoke = Random.nextFloat() < 0.35f
        particles.add(
            Particle(
                x = mx + (Random.nextFloat() - 0.5f) * shieldHalf * 1.3f,
                y = topY + Random.nextFloat() * 20f * s,
                vx = (Random.nextFloat() - 0.5f) * 22f * dp,
                vy = -(40f + Random.nextFloat() * 50f) * dp,
                life = 0.7f + Random.nextFloat() * 0.7f,
                age = 0f,
                size = (3f + Random.nextFloat() * 4f) * dp * (0.5f + burn * 0.8f),
                smoke = smoke
            )
        )
    }

    // ------------------------------------------------------------------ toque
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
        }
        extreme = e
        onLevelChanged?.invoke(nl)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                // arrasto relativo: o vampiro acompanha o dedo, sem pular
                grabDx = event.x - markerX()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) moveTo(event.x - grabDx)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onLevelCommitted?.invoke(level)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()
}
