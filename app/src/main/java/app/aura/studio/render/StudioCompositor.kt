package app.aura.studio.render

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import app.aura.core.studio.Framing
import app.aura.core.studio.StudioProject

/**
 * Compone el fondo final: foto encuadrada → reloj 3D → sujeto recortado por la máscara encima.
 * Así parte del reloj queda detrás de la persona u objeto principal, como en los fondos con
 * «efecto de profundidad».
 */
class StudioCompositor(private val painter: ClockPainter) {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val maskPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcIn = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }

    /** Dibuja la foto encuadrada; también lo usa el fondo animado para cachear la capa de fondo. */
    fun drawPhoto(canvas: Canvas, w: Int, h: Int, photo: Bitmap, project: StudioProject) {
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(photo, photoMatrix(w, h, photo, project), bitmapPaint)
    }

    /**
     * Capa del sujeto (foto recortada por la máscara) en un bitmap transparente del tamaño del lienzo,
     * o null si no corresponde. Se calcula una vez y se reutiliza mientras no cambien foto ni máscara.
     */
    fun subjectLayer(w: Int, h: Int, photo: Bitmap, mask: Bitmap?, project: StudioProject): Bitmap? {
        if (mask == null || !project.hasMask || !project.design.anyBehindSubject()) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val m = photoMatrix(w, h, photo, project)
        val maskMatrix = Matrix(m).apply { preScale(photo.width / mask.width.toFloat(), photo.height / mask.height.toFloat()) }
        val grow = project.mask.grow.coerceIn(-20, 20)
        val feather = (project.mask.feather + kotlin.math.abs(grow)) / 1000f * w
        maskPaint.maskFilter = if (feather >= 0.5f) BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL) else null
        // Expandir: el borde difuminado se vuelve opaco antes; contraer: solo queda opaco el interior.
        maskPaint.colorFilter = when {
            grow > 0 -> android.graphics.ColorMatrixColorFilter(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 3f, 0f))
            grow < 0 -> android.graphics.ColorMatrixColorFilter(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 2.5f, -380f))
            else -> null
        }
        maskPaint.color = Color.BLACK
        if (project.mask.inverted) {
            c.drawColor(Color.BLACK)
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        } else {
            maskPaint.xfermode = null
        }
        c.drawBitmap(mask, maskMatrix, maskPaint)
        c.drawBitmap(photo, m, srcIn)
        return out
    }

    fun render(
        out: Bitmap,
        photo: Bitmap,
        mask: Bitmap?,
        project: StudioProject,
        extras: ClockExtras,
        subject: Bitmap? = subjectLayer(out.width, out.height, photo, mask, project),
        /** Tinte de luz según la hora (ARGB, 0 = ninguno). */
        tint: Int = 0,
        /** Instante (s) de las partículas de Atmosphere; null = no dibujarlas (la vista previa las anima aparte). */
        particlesAt: Float? = 0f,
    ) {
        val c = Canvas(out)
        drawPhoto(c, out.width, out.height, photo, project)
        drawClock(c, out.width, out.height, project.design, extras, Backdrop(photo, photoMatrix(out.width, out.height, photo, project)), subject, tint = tint)
        particlesAt?.let { AtmospherePainter.drawEffect(c, out.width, out.height, project.atmosphere, it) }
    }

    /**
     * Reloj y sujeto en el orden correcto: lo que va detrás del sujeto, el sujeto y lo que va delante.
     * Cada dígito independiente puede ir delante o detrás por su cuenta.
     */
    fun drawClock(c: Canvas, w: Int, h: Int, design: app.aura.core.studio.ClockDesign, extras: ClockExtras, backdrop: Backdrop?, subject: Bitmap?, phase: Float = 0f, tint: Int = 0) {
        if (subject == null) {
            AtmospherePainter.drawTint(c, tint)
            painter.draw(c, w, h, design, extras, backdrop, phase = phase)
            return
        }
        painter.draw(c, w, h, design, extras, backdrop, ClockPainter.Pass.BEHIND, phase)
        c.drawBitmap(subject, 0f, 0f, null)
        // La luz de la hora tiñe la escena (foto, sujeto y números de atrás), no los números de adelante.
        AtmospherePainter.drawTint(c, tint)
        painter.draw(c, w, h, design, extras, backdrop, ClockPainter.Pass.FRONT, phase)
    }

    fun photoMatrix(w: Int, h: Int, photo: Bitmap, project: StudioProject): Matrix {
        val p = Framing.place(photo.width, photo.height, w, h, project.frame)
        return Matrix().apply {
            setScale(p.scale, p.scale)
            postTranslate(p.dx, p.dy)
        }
    }

    val clock: ClockPainter get() = painter
}
