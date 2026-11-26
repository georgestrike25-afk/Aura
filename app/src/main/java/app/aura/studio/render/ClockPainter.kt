package app.aura.studio.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import app.aura.core.studio.ClockDesign
import app.aura.core.studio.ClockInformationField
import app.aura.core.studio.informationStyle
import app.aura.core.studio.ClockLayout
import app.aura.core.studio.ClockMaterial
import app.aura.core.studio.DigitSlot
import app.aura.core.studio.DigitStyle
import app.aura.core.studio.ElementTexture
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Texto que acompaña a la hora (fecha y clima en caché), ya formateado por quien llama. */
data class ClockExtras(val hours: String, val minutes: String, val subtitle: String?, val information: List<Pair<ClockInformationField, String>> = emptyList())

/** Lo que hay detrás del reloj: imagen y su colocación en el lienzo (para el modo camaleón). */
class Backdrop(val bitmap: Bitmap, val matrix: android.graphics.Matrix)

/**
 * Pinta el reloj 3D paramétrico sobre un Canvas de software. Lo usan la vista previa, la exportación
 * y el fondo animado, así los tres muestran exactamente lo mismo.
 *
 * Capas (de atrás hacia adelante): sombra difusa → extrusión (profundidad) → brillo (neón) →
 * cara con el material → bisel (luz y sombra en el borde) → reflejo.
 */
class ClockPainter(private val fonts: (String) -> Typeface? = { null }) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private var stoneTexture: Bitmap? = null

    /** Qué partes dibujar respecto del sujeto de la foto (para poner dígitos delante o detrás). */
    enum class Pass { ALL, BEHIND, FRONT }

    /**
     * Dibuja el reloj. [phase] (0–1) desplaza las texturas Elements: el fondo animado la avanza para
     * que el agua, el fuego o la nebulosa se muevan.
     */
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        rawDesign: ClockDesign,
        extras: ClockExtras,
        backdrop: Backdrop? = null,
        pass: Pass = Pass.ALL,
        phase: Float = 0f,
    ) {
        val d = rawDesign.normalized()
        val lay = layout(d, extras, width, height)
        val textSize = lay.textSize
        val cx = d.centerX * width
        val cy = d.centerY * height
        val full = fullMatrix(d, lay, width, height)
        val camoTone = if (d.chameleon > 0 && backdrop != null) {
            averageBehind(backdrop, cx, cy, lay.box.width() * d.stretchX, lay.box.height() * d.stretchY)
        } else {
            null
        }

        // Luz: vector unitario hacia donde llega; la sombra y la extrusión van al lado contrario.
        val a = Math.toRadians(d.lightAngle.toDouble())
        val light = floatArrayOf(cos(a).toFloat(), sin(a).toFloat())

        val outer = canvas.save()
        canvas.concat(full)
        val layer = canvas.saveLayerAlpha(null, (d.opacity * 255 / 100f).roundToInt())
        // Primero lo que no es un dígito (dos puntos, a. m./p. m.), después los dígitos en su orden de capas.
        val order = d.artistic.drawOrder()
        val sorted = lay.runs.sortedBy { r -> r.slot?.let { order.indexOf(it) + 1 } ?: 0 }
        for (r in sorted) {
            if (r.style?.hidden == true) continue
            val behind = r.slot?.let { d.artistic.isBehind(it, d.behindSubject) } ?: d.behindSubject
            if (pass == Pass.BEHIND && !behind || pass == Pass.FRONT && behind) continue
            drawRun(canvas, r, d, textSize, light, full, backdrop, camoTone, phase)
        }
        canvas.restoreToCount(layer)
        canvas.restoreToCount(outer)

        if (pass != Pass.BEHIND) {
            for ((field, text) in extras.information) {
                val style = d.informationStyle(field)
                if (!style.visible) continue
                val face = style.fontId?.let(fonts) ?: Typeface.create("sans-serif", Typeface.NORMAL)
                paint.reset(width * style.size.coerceIn(0.015f, 0.10f), face, 0.01f)
                val available = width * 0.94f
                val measured = paint.measureText(text)
                if (measured > available) paint.textSize *= available / measured
                val half = paint.measureText(text) / 2f
                val x = (style.x.coerceIn(0f, 1f) * width).coerceIn(half, width - half)
                val baseline = (style.y.coerceIn(0f, 1f) * height).coerceIn(-paint.ascent(), height - paint.descent())
                val alpha = style.opacity.coerceIn(0, 100) * 255 / 100
                paint.color = withAlpha(style.color ?: d.color, alpha)
                paint.setShadowLayer(width * 0.004f, 0f, width * 0.002f, withAlpha(Color.BLACK, alpha / 2))
                canvas.drawText(text, x - half, baseline, paint)
            }
        }

        // Subtítulo (fecha · clima), sobrio y legible; acompaña la posición y el alto del reloj.
        val subtitleHere = pass == Pass.ALL || (pass == Pass.BEHIND) == d.behindSubject
        extras.subtitle?.takeIf { it.isNotBlank() && subtitleHere }?.let { sub ->
            paint.reset(width * 0.042f, Typeface.create("sans-serif", Typeface.NORMAL), 0.02f)
            val sw = paint.measureText(sub)
            val sy = (cy + (lay.lastBaseline - cy + textSize * 0.34f) * d.stretchY + width * 0.03f)
                .coerceAtMost(height - width * 0.03f)
            paint.color = withAlpha(Color.BLACK, 120)
            paint.maskFilter = BlurMaskFilter(width * 0.006f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawText(sub, d.centerX * width - sw / 2f, sy + width * 0.003f, paint)
            paint.maskFilter = null
            paint.color = withAlpha(d.color, (d.opacity * 2.4f).roundToInt().coerceIn(0, 240))
            canvas.drawText(sub, d.centerX * width - sw / 2f, sy, paint)
        }
    }

    /**
     * Un grupo de cifras completo: sombra → extrusión → brillo → cara con el material → bisel → reflejo.
     * Si es un dígito independiente, se dibuja con su propia transformación, color, fuente, opacidad y material.
     */
    private fun drawRun(
        canvas: Canvas, r: Run, base: ClockDesign, textSize: Float, light: FloatArray,
        full: android.graphics.Matrix, backdrop: Backdrop?, camoTone: Int?, phase: Float,
    ) {
        val st = r.style
        val d = if (st == null) base else base.copy(
            color = st.color ?: base.color,
            material = st.material ?: base.material,
            elementIntensity = if (st.material != null) st.intensity else base.elementIntensity,
        )
        val lx = light[0]
        val ly = light[1]
        val saved = canvas.save()
        r.matrix?.let { canvas.concat(it) }
        if (st != null && st.opacity < 100) canvas.saveLayerAlpha(null, (st.opacity * 255 / 100f).roundToInt())
        val baseline = r.baseline
        val px = r.x
        val element = d.material.isElement
        val pal = if (element) ElementTexture.palette(d.material) else null

        // 1. Sombra difusa
        if (d.shadow > 0) {
            val dist = textSize * (0.03f + 0.07f * d.shadow / 100f)
            paint.reset(textSize, r.face, d.letterSpacing)
            paint.color = Color.argb((90 + 120 * d.shadow / 100f).roundToInt().coerceAtMost(220), 0, 0, 0)
            paint.maskFilter = BlurMaskFilter(textSize * (0.02f + 0.06f * d.shadow / 100f), BlurMaskFilter.Blur.NORMAL)
            canvas.drawText(r.text, px - lx * dist, baseline - ly * dist, paint)
        }

        // 2. Extrusión: copias apiladas cada vez más oscuras en dirección opuesta a la luz.
        val depthPx = textSize * 0.09f * d.depth / 100f
        if (depthPx >= 1f) {
            val steps = depthPx.roundToInt().coerceIn(1, 48)
            // En modo camaleón el canto toma el tono medio del fondo, más oscuro.
            val sideBase = pal?.let { shade(it[1], 0.8f) } ?: sideColor(d)
            val side = camoTone?.let { mix(sideBase, shade(it, 0.6f), d.chameleon / 100f) } ?: sideBase
            paint.reset(textSize, r.face, d.letterSpacing)
            for (s in steps downTo 1) {
                val t = s / steps.toFloat()
                paint.color = shade(side, 1f - 0.45f * t)
                canvas.drawText(r.text, px - lx * depthPx * t, baseline - ly * depthPx * t, paint)
            }
        }

        // 3. Brillo (neón, Elements luminosos y cualquier material con «glow»)
        if (d.glow > 0) {
            val glowColor = when {
                pal != null -> pal[2]
                d.material == ClockMaterial.NEON -> d.accentColor
                else -> d.color
            }
            listOf(0.22f, 0.10f, 0.04f).forEach { k ->
                paint.reset(textSize, r.face, d.letterSpacing)
                paint.color = withAlpha(glowColor, (60 + 150 * d.glow / 100f).roundToInt().coerceAtMost(230))
                paint.maskFilter = BlurMaskFilter(textSize * k * (0.4f + d.glow / 100f), BlurMaskFilter.Blur.NORMAL)
                canvas.drawText(r.text, px, baseline, paint)
            }
        }

        // 4. Cara
        val top = baseline - textSize * 0.74f
        val bottom = baseline + textSize * 0.02f
        paint.reset(textSize, r.face, d.letterSpacing)
        if (element) {
            // Textura Elements con la intensidad elegida; el resto es el color del reloj.
            paint.shader = elementShader(d, textSize, phase)
            canvas.drawText(r.text, px, baseline, paint)
            if (d.elementIntensity < 100) {
                paint.reset(textSize, r.face, d.letterSpacing)
                paint.color = withAlpha(d.color, ((100 - d.elementIntensity) * 2.55f).roundToInt())
                canvas.drawText(r.text, px, baseline, paint)
            }
            elementOverlay(canvas, r, d, textSize, top, bottom)
        } else {
            applyFace(paint, d, top, bottom, px, r.width)
            canvas.drawText(r.text, px, baseline, paint)
        }
        if (d.chameleon > 0 && backdrop != null) {
            val m = android.graphics.Matrix(full)
            r.matrix?.let { m.preConcat(it) }
            paint.reset(textSize, r.face, d.letterSpacing)
            paint.shader = chameleonShader(backdrop, m, d)
            paint.alpha = (255 * d.chameleon / 100f).roundToInt()
            paint.colorFilter = liftFilter(d.chameleonLift)
            canvas.drawText(r.text, px, baseline, paint)
        }
        if (d.material == ClockMaterial.GLASS || d.material == ClockMaterial.GLACIUS) {
            paint.reset(textSize, r.face, d.letterSpacing)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, textSize * 0.012f)
            paint.color = withAlpha(Color.WHITE, 170)
            canvas.drawText(r.text, px, baseline, paint)
        }

        // 5. Bisel: borde iluminado hacia la luz y en sombra del lado opuesto.
        if (d.bevel > 0) {
            val w = max(1f, textSize * 0.018f * d.bevel / 100f)
            val off = w * 0.6f
            paint.reset(textSize, r.face, d.letterSpacing)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w
            paint.color = withAlpha(Color.WHITE, (60 + 140 * d.bevel / 100f).roundToInt())
            canvas.drawText(r.text, px + lx * off, baseline + ly * off, paint)
            paint.color = withAlpha(Color.BLACK, (40 + 110 * d.bevel / 100f).roundToInt())
            canvas.drawText(r.text, px - lx * off, baseline - ly * off, paint)
        }

        // 6. Reflejo: brillo especular en la mitad superior.
        if (d.reflection > 0) {
            paint.reset(textSize, r.face, d.letterSpacing)
            paint.shader = LinearGradient(
                0f, top, 0f, top + (bottom - top) * 0.55f,
                intArrayOf(withAlpha(Color.WHITE, (200 * d.reflection / 100f).roundToInt()), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawText(r.text, px, baseline, paint)
        }
        canvas.restoreToCount(saved)
    }

    /** Textura Elements repetida; la escala sigue al tamaño de los números y [phase] la desplaza. */
    private fun elementShader(d: ClockDesign, textSize: Float, phase: Float): Shader {
        val tile = ElementTextures.tile(d.material)
        val k = textSize * (0.35f + 1.3f * d.textureScale / 100f) / tile.width
        val drift = when (d.material) {
            ClockMaterial.IGNIS -> floatArrayOf(0f, -1f)   // el fuego sube
            ClockMaterial.VENTUS -> floatArrayOf(1f, 0f)   // el humo va de costado
            ClockMaterial.AQUA -> floatArrayOf(0.4f, 0.3f)
            else -> floatArrayOf(0.3f, 0.15f)
        }
        val m = android.graphics.Matrix().apply {
            setScale(k, k)
            postTranslate(drift[0] * phase * tile.width * k, drift[1] * phase * tile.width * k)
        }
        return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { setLocalMatrix(m) }
    }

    /** Detalles propios de cada elemento sobre la cara: luz interna, musgo al pie, escarcha, etc. */
    private fun elementOverlay(canvas: Canvas, r: Run, d: ClockDesign, textSize: Float, top: Float, bottom: Float) {
        val pal = ElementTexture.palette(d.material)
        val k = d.elementIntensity / 100f
        paint.reset(textSize, r.face, d.letterSpacing)
        paint.shader = when (d.material) {
            // Agua y hielo: más claros arriba (refracción), más profundos abajo.
            ClockMaterial.AQUA, ClockMaterial.GLACIUS -> LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(withAlpha(Color.WHITE, (110 * k).roundToInt()), Color.TRANSPARENT, withAlpha(pal[1], (120 * k).roundToInt())),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            // Fuego: base incandescente.
            ClockMaterial.IGNIS -> LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.TRANSPARENT, withAlpha(0xFFFFE08A.toInt(), (150 * k).roundToInt())),
                null, Shader.TileMode.CLAMP,
            )
            // Tierra: musgo que crece desde abajo.
            ClockMaterial.TERRA -> LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, withAlpha(pal[2], (170 * k).roundToInt())),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP,
            )
            // Luz: núcleo casi blanco.
            ClockMaterial.LUX -> LinearGradient(
                0f, top, 0f, bottom,
                intArrayOf(withAlpha(Color.WHITE, (160 * k).roundToInt()), withAlpha(pal[1], (60 * k).roundToInt())),
                null, Shader.TileMode.CLAMP,
            )
            else -> null
        } ?: return
        canvas.drawText(r.text, r.x, r.baseline, paint)
    }

    /** Transformación completa del reloj en pantalla: deformación ∘ (giro + estiramiento). */
    private fun fullMatrix(d: ClockDesign, lay: Layout, width: Int, height: Int): android.graphics.Matrix {
        val warp = warpMatrix(d, lay.transform, lay.box, width, height)
        return android.graphics.Matrix(lay.transform).apply { warp?.let { postConcat(it) } }
    }

    /**
     * Caja de cada dígito en pantalla (con su transformación propia y la del reloj), para tocarlo,
     * seleccionarlo y calcular guías magnéticas en el editor. Vacío si el reloj no es artístico.
     */
    fun digitBounds(width: Int, height: Int, rawDesign: ClockDesign, extras: ClockExtras): Map<DigitSlot, RectF> {
        val d = rawDesign.normalized()
        if (!d.artistic.enabled) return emptyMap()
        val lay = layout(d, extras, width, height)
        val full = fullMatrix(d, lay, width, height)
        return lay.runs.mapNotNull { r ->
            val slot = r.slot ?: return@mapNotNull null
            val box = RectF(r.x, r.baseline - lay.textSize * 0.74f, r.x + r.width, r.baseline + lay.textSize * 0.04f)
            val m = android.graphics.Matrix(full)
            r.matrix?.let { m.preConcat(it) }
            m.mapRect(box)
            slot to box
        }.toMap()
    }

    /**
     * Esquinas del reloj en pantalla (arriba-izq., arriba-der., abajo-der., abajo-izq.; pares x,y),
     * ya giradas, estiradas y deformadas: son los tiradores del modo «Deformar».
     */
    fun quad(width: Int, height: Int, rawDesign: ClockDesign, extras: ClockExtras): FloatArray {
        val d = rawDesign.normalized()
        val lay = layout(d, extras, width, height)
        val pts = boxCorners(lay.box)
        lay.transform.mapPoints(pts)
        if (d.warp.size == 8) for (i in 0 until 8) pts[i] += d.warp[i] * (if (i % 2 == 0) width else height)
        return pts
    }

    /**
     * Dónde va cada grupo de cifras (sin girar ni estirar). En horizontal: horas, «:» y minutos en
     * una línea; al separar desde el centro las horas van a la izquierda, los minutos a la derecha y
     * los dos puntos quedan siempre en el medio del hueco. En vertical: horas arriba y minutos abajo,
     * cada línea centrada; al separar se alejan hacia arriba y hacia abajo.
     */
    private fun layout(d: ClockDesign, extras: ClockExtras, width: Int, height: Int): Layout {
        val lay = layoutAt(d, d.size, extras, width, height)
        // Si el reloj no entra a lo ancho (p. ej. un diseño enorme con «p. m.»), se achica hasta entrar.
        if (d.spread > 0f) return lay
        val cx = d.centerX * width
        val available = (2f * minOf(cx, width - cx) - width * 0.04f).coerceAtLeast(width * 0.3f)
        val wide = lay.box.width() * d.stretchX
        return if (wide <= available) lay else layoutAt(d, d.size * available / wide, extras, width, height)
    }

    private fun layoutAt(d: ClockDesign, size: Float, extras: ClockExtras, width: Int, height: Int): Layout {
        val textSize = size * width
        val custom = d.customFont?.let(fonts)
        val family = custom ?: Typeface.create(d.font.androidFamily, Typeface.NORMAL)
        val hoursFace = when {
            !d.boldHours -> family
            custom != null -> Typeface.create(custom, Typeface.BOLD)
            else -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val cx = d.centerX * width
        val cy = d.centerY * height
        // Transformación del reloj: giro y estiramiento alrededor de su centro.
        val transform = android.graphics.Matrix().apply {
            postScale(d.stretchX, d.stretchY, cx, cy)
            postRotate(d.rotation, cx, cy)
        }
        fun measure(text: String, face: Typeface): Float {
            paint.reset(textSize, face, d.letterSpacing)
            return paint.measureText(text)
        }
        val hasColon = extras.minutes.startsWith(":") && d.showColon
        val minutes = extras.minutes.removePrefix(":")
        val hW = measure(extras.hours, hoursFace)
        val mW = measure(minutes, family)

        if (d.layout == ClockLayout.VERTICAL) {
            // Distancia entre las dos líneas de base; el centro del reloj queda entre ambas.
            val step = textSize * 0.86f
            val bH0 = cy - step / 2f + textSize * 0.36f
            val bM0 = cy + step / 2f + textSize * 0.36f
            var up = 0f
            var down = 0f
            if (d.spread > 0f) {
                val margin = width * 0.03f
                val half = (step / 2f + textSize * 0.44f) * d.stretchY
                up = d.spread * (cy - half - margin).coerceAtLeast(0f) / d.stretchY
                down = d.spread * (height - margin - (cy + half)).coerceAtLeast(0f) / d.stretchY
            }
            val bH = bH0 - up
            val bM = bM0 + down
            val runs = listOf(
                Run(extras.hours, hoursFace, cx - hW / 2f, bH, hW),
                Run(minutes, family, cx - mW / 2f, bM, mW),
            )
            val w = max(hW, mW)
            val box = RectF(cx - w / 2f, bH - textSize * 0.80f, cx + w / 2f, bM + textSize * 0.08f)
            return Layout(splitDigits(runs, d, textSize, width, height), box, textSize, transform, bM)
        }

        // Sin los dos puntos queda su hueco, así las cifras no se pegan.
        val sW = if (extras.minutes.startsWith(":")) measure(":", family) else 0f
        val total = hW + sW + mW
        val (shiftH, shiftM) = spreadShifts(d, cx, total, width)
        val startX = cx - total / 2f
        val baseline = cy + textSize * 0.36f
        val runs = buildList {
            add(Run(extras.hours, hoursFace, startX - shiftH, baseline, hW))
            // Los dos puntos, centrados en el hueco entre horas y minutos.
            if (hasColon) add(Run(":", family, startX + hW + (shiftM - shiftH) / 2f, baseline, sW))
            add(Run(minutes, family, startX + hW + sW + shiftM, baseline, mW))
        }
        val box = RectF(startX - shiftH, baseline - textSize * 0.80f, startX + total + shiftM, baseline + textSize * 0.08f)
        return Layout(splitDigits(runs, d, textSize, width, height), box, textSize, transform, baseline)
    }

    /**
     * Reloj artístico: separa horas y minutos en dígitos independientes (cada uno en su lugar fijo, así
     * conserva su estilo aunque cambie la hora) y les aplica su transformación, fuente y estilo.
     */
    private fun splitDigits(runs: List<Run>, d: ClockDesign, textSize: Float, width: Int, height: Int): List<Run> {
        if (!d.artistic.enabled) return runs
        val hoursRun = runs.first()
        val minutesRun = runs.last()
        val out = mutableListOf<Run>()
        fun explode(run: Run, slots: List<DigitSlot>) {
            var x = run.x
            var digitIndex = 0
            val digitCount = run.text.count { it.isDigit() }
            run.text.forEach { ch ->
                paint.reset(textSize, run.face, d.letterSpacing)
                val w = paint.measureText(ch.toString())
                val slot = if (ch.isDigit()) slots.getOrNull(slots.size - digitCount + digitIndex++) else null
                if (slot == null) {
                    if (!ch.isWhitespace()) out += Run(ch.toString(), run.face, x, run.baseline, w)
                } else {
                    val st = d.artistic.style(slot)
                    val face = st.fontId?.let(fonts) ?: run.face
                    paint.reset(textSize, face, d.letterSpacing)
                    val fw = paint.measureText(ch.toString())
                    val fx = x + (w - fw) / 2f
                    val pivotX = fx + fw / 2f
                    val pivotY = run.baseline - textSize * 0.36f
                    val m = android.graphics.Matrix().apply {
                        postSkew(-st.skewX * 0.6f, 0f, pivotX, pivotY)
                        postScale(st.scaleX, st.scaleY, pivotX, pivotY)
                        postRotate(st.rotation, pivotX, pivotY)
                        postTranslate(st.offsetX * width / d.stretchX, st.offsetY * height / d.stretchY)
                    }
                    out += Run(ch.toString(), face, fx, run.baseline, fw, slot, st, m)
                }
                x += w
            }
        }
        explode(hoursRun, listOf(DigitSlot.HOUR_TENS, DigitSlot.HOUR_UNITS))
        runs.drop(1).dropLast(1).forEach { out += it } // los dos puntos
        explode(minutesRun, listOf(DigitSlot.MINUTE_TENS, DigitSlot.MINUTE_UNITS))
        return out
    }

    /**
     * Separación desde el centro: al 100 % el borde exterior de las horas toca el margen izquierdo y
     * el de los minutos el derecho (teniendo en cuenta el estiramiento horizontal).
     */
    private fun spreadShifts(d: ClockDesign, cx: Float, total: Float, width: Int): Pair<Float, Float> {
        if (d.spread <= 0f) return 0f to 0f
        val margin = width * 0.03f
        val half = total * d.stretchX / 2f
        val left = (cx - half - margin).coerceAtLeast(0f) / d.stretchX
        val right = (width - margin - (cx + half)).coerceAtLeast(0f) / d.stretchX
        return d.spread * left to d.spread * right
    }

    /** Esquinas de una caja: arriba-izq., arriba-der., abajo-der., abajo-izq. */
    private fun boxCorners(b: RectF): FloatArray =
        floatArrayOf(b.left, b.top, b.right, b.top, b.right, b.bottom, b.left, b.bottom)

    /** Deformación por esquinas en coordenadas de pantalla (después del giro y el estiramiento). */
    private fun warpMatrix(d: ClockDesign, transform: android.graphics.Matrix, box: RectF, width: Int, height: Int): android.graphics.Matrix? {
        if (d.warp.size != 8) return null
        val src = boxCorners(box)
        transform.mapPoints(src)
        val dst = FloatArray(8) { i -> src[i] + d.warp[i] * (if (i % 2 == 0) width else height) }
        if (!isConvex(dst)) return null // esquinas cruzadas: deformación imposible, se ignora
        return android.graphics.Matrix().takeIf { it.setPolyToPoly(src, 0, dst, 0, 4) }
    }

    private fun isConvex(q: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = q[((i + 1) % 4) * 2] - q[i * 2]
            val ay = q[((i + 1) % 4) * 2 + 1] - q[i * 2 + 1]
            val bx = q[((i + 2) % 4) * 2] - q[((i + 1) % 4) * 2]
            val by = q[((i + 2) % 4) * 2 + 1] - q[((i + 1) % 4) * 2 + 1]
            val cross = ax * by - ay * bx
            val sgn = if (cross > 0) 1 else if (cross < 0) -1 else 0
            if (sgn == 0) return false
            if (sign == 0) sign = sgn else if (sgn != sign) return false
        }
        return true
    }

    /** Caja del reloj en el lienzo (girada y estirada), para tocarlo y moverlo en el editor. */
    fun bounds(width: Int, height: Int, d: ClockDesign, extras: ClockExtras): Rect {
        val lay = layout(d.normalized(), extras, width, height)
        val r = RectF(lay.box).apply { inset(-lay.textSize * 0.08f, -lay.textSize * 0.15f) }
        lay.transform.mapRect(r)
        return Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
    }

    /**
     * La foto que queda detrás de los números, alineada píxel a píxel aunque el reloj esté girado o
     * estirado: matriz local = transformación del reloj⁻¹ · colocación de la foto.
     */
    private fun chameleonShader(b: Backdrop, transform: android.graphics.Matrix, d: ClockDesign): Shader {
        val inverse = android.graphics.Matrix()
        transform.invert(inverse)
        val local = android.graphics.Matrix(b.matrix).apply { postConcat(inverse) }
        return BitmapShader(b.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(local) }
    }

    /** Aclarar/oscurecer el camuflaje: escala y desplazamiento de luminancia. */
    private fun liftFilter(lift: Int): android.graphics.ColorFilter? {
        if (lift == 0) return null
        val k = lift / 100f
        val scale = if (k > 0) 1f - 0.35f * k else 1f + k * 0.6f
        val offset = if (k > 0) 255f * 0.45f * k else 0f
        val m = floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
        return android.graphics.ColorMatrixColorFilter(m)
    }

    /** Color medio del fondo en la zona del reloj (8×8 muestras). */
    private fun averageBehind(b: Backdrop, cx: Float, cy: Float, w: Float, h: Float): Int {
        val sample = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val m = android.graphics.Matrix(b.matrix).apply {
            postTranslate(-(cx - w / 2f), -(cy - h / 2f))
            postScale(8f / max(1f, w), 8f / max(1f, h))
        }
        Canvas(sample).drawBitmap(b.bitmap, m, Paint(Paint.FILTER_BITMAP_FLAG))
        var r = 0
        var g = 0
        var bl = 0
        val px = IntArray(64)
        sample.getPixels(px, 0, 8, 0, 0, 8, 8)
        px.forEach { r += Color.red(it); g += Color.green(it); bl += Color.blue(it) }
        sample.recycle()
        return Color.rgb(r / 64, g / 64, bl / 64)
    }

    private fun applyFace(p: Paint, d: ClockDesign, top: Float, bottom: Float, left: Float, width: Float) {
        when (d.material) {
            ClockMaterial.MINIMAL -> p.color = d.color
            ClockMaterial.METAL -> {
                val tint = mix(Color.WHITE, d.accentColor, 0.25f)
                p.shader = LinearGradient(
                    0f, top, 0f, bottom,
                    intArrayOf(lighten(tint, 0.6f), tint, shade(tint, 0.45f), lighten(tint, 0.35f), shade(tint, 0.6f)),
                    floatArrayOf(0f, 0.35f, 0.52f, 0.7f, 1f), Shader.TileMode.CLAMP,
                )
            }
            ClockMaterial.GLASS -> {
                p.shader = LinearGradient(
                    0f, top, 0f, bottom,
                    intArrayOf(withAlpha(d.color, 150), withAlpha(d.color, 60), withAlpha(d.color, 110)),
                    null, Shader.TileMode.CLAMP,
                )
            }
            ClockMaterial.NEON -> p.color = mix(Color.WHITE, d.accentColor, 0.25f)
            ClockMaterial.STONE -> {
                p.shader = BitmapShader(stone(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                p.colorFilter = android.graphics.PorterDuffColorFilter(withAlpha(d.color, 90), android.graphics.PorterDuff.Mode.MULTIPLY)
            }
            else -> p.color = d.color
        }
    }

    private fun sideColor(d: ClockDesign): Int = when (d.material) {
        ClockMaterial.METAL -> shade(mix(Color.WHITE, d.accentColor, 0.3f), 0.55f)
        ClockMaterial.GLASS -> withAlpha(shade(d.color, 0.6f), 120)
        ClockMaterial.NEON -> shade(d.accentColor, 0.4f)
        ClockMaterial.STONE -> 0xFF4E4A46.toInt()
        ClockMaterial.MINIMAL -> shade(d.color, 0.55f)
        else -> shade(ElementTexture.palette(d.material)[1], 0.8f)
    }

    /** Textura de granito procedural (sin imágenes externas), generada una sola vez. */
    private fun stone(): Bitmap = stoneTexture ?: Bitmap.createBitmap(STONE_SIZE, STONE_SIZE, Bitmap.Config.ARGB_8888).also { bmp ->
        val rnd = Random(42)
        val px = IntArray(STONE_SIZE * STONE_SIZE) {
            val v = 150 + rnd.nextInt(70)
            val fleck = if (rnd.nextInt(40) == 0) -70 else 0
            val g = (v + fleck).coerceIn(0, 255)
            Color.rgb(g, (g * 0.97f).toInt(), (g * 0.92f).toInt())
        }
        bmp.setPixels(px, 0, STONE_SIZE, 0, 0, STONE_SIZE, STONE_SIZE)
        stoneTexture = bmp
    }

    /**
     * Un grupo de cifras ya colocado: texto, tipografía, x izquierda, línea de base y ancho. Si es un
     * dígito independiente, también su lugar, su estilo y su transformación propia.
     */
    private class Run(
        val text: String,
        val face: Typeface,
        val x: Float,
        val baseline: Float,
        val width: Float,
        val slot: DigitSlot? = null,
        val style: DigitStyle? = null,
        val matrix: android.graphics.Matrix? = null,
    )

    /** Disposición del reloj: grupos, caja que los contiene (para la deformación) y última línea de base. */
    private class Layout(val runs: List<Run>, val box: RectF, val textSize: Float, val transform: android.graphics.Matrix, val lastBaseline: Float)

    companion object {
        private const val STONE_SIZE = 128

        private fun Paint.reset(size: Float, face: Typeface, spacing: Float) {
            reset()
            isAntiAlias = true
            isSubpixelText = true
            textSize = size
            typeface = face
            letterSpacing = spacing
        }

        fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
        fun shade(c: Int, k: Float) = Color.argb(Color.alpha(c), (Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())
        fun lighten(c: Int, k: Float) = mix(c, Color.WHITE, k)
        fun mix(a: Int, b: Int, t: Float) = Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )
    }
}
