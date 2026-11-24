package app.aura.studio.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import app.aura.core.studio.Atmosphere
import app.aura.core.studio.AtmosphereEffect
import app.aura.core.studio.AtmosphereParticles
import app.aura.core.studio.TimeLight
import app.aura.core.weather.WeatherSnapshot
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Dibuja Aura Atmosphere sobre un Canvas: partículas (lluvia, nieve, viento, polvo luminoso, cenizas),
 * bandas de niebla y el tinte de luz según la hora. Sin estado: [t] (segundos) define el fotograma.
 */
object AtmospherePainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Tinte de luz para [now]; usa la salida y puesta del sol del clima guardado si las hay. */
    fun tintFor(a: Atmosphere, now: LocalDateTime, snapshot: WeatherSnapshot?): Int {
        if (!a.timeLight) return 0
        val zone = ZoneId.systemDefault()
        fun minuteOf(epoch: Long?) = epoch?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalTime().let { t -> t.hour * 60 + t.minute } }
        val today = snapshot?.data?.daily?.firstOrNull { it.date == now.toLocalDate().toString() }
        return TimeLight.tint(now.hour * 60 + now.minute, minuteOf(today?.sunrise), minuteOf(today?.sunset), a.timeLightStrength)
    }

    fun drawTint(c: Canvas, tint: Int) {
        if (tint ushr 24 == 0) return
        c.drawColor(tint)
    }

    fun drawEffect(c: Canvas, w: Int, h: Int, a: Atmosphere, t: Float) {
        if (!a.active) return
        if (a.effect == AtmosphereEffect.FOG) return drawFog(c, w, h, a, t)
        val k = w / 1080f
        AtmosphereParticles.frame(a, t, w, h).forEach { p ->
            paint.reset()
            paint.isAntiAlias = true
            when (a.effect) {
                AtmosphereEffect.RAIN, AtmosphereEffect.WIND -> {
                    paint.color = Color.argb((p.alpha * 255).roundToInt(), 220, 234, 248)
                    paint.strokeWidth = p.size
                    paint.strokeCap = Paint.Cap.ROUND
                    val rad = Math.toRadians(if (a.effect == AtmosphereEffect.RAIN) 82.0 else -4.0)
                    val dx = (kotlin.math.cos(rad) * p.length).toFloat()
                    val dy = (kotlin.math.sin(rad) * p.length).toFloat()
                    c.drawLine(p.x, p.y, p.x + dx, p.y + dy, paint)
                }
                AtmosphereEffect.SNOW -> {
                    paint.color = Color.argb((p.alpha * 255).roundToInt(), 255, 255, 255)
                    c.drawCircle(p.x, p.y, p.size, paint)
                }
                AtmosphereEffect.DUST -> {
                    // Mota luminosa con halo suave.
                    paint.shader = RadialGradient(
                        p.x, p.y, p.size * 3f,
                        intArrayOf(Color.argb((p.alpha * 255).roundToInt(), 255, 236, 190), Color.TRANSPARENT),
                        null, Shader.TileMode.CLAMP,
                    )
                    c.drawCircle(p.x, p.y, p.size * 3f, paint)
                }
                AtmosphereEffect.ASH -> {
                    val ember = (p.x * 7 + p.y).toInt() % 9 == 0
                    paint.color = if (ember) Color.argb((p.alpha * 255).roundToInt(), 255, 150, 70) else Color.argb((p.alpha * 255).roundToInt(), 70, 66, 64)
                    c.save()
                    c.rotate(p.angle, p.x, p.y)
                    c.drawRect(p.x - p.length / 2, p.y - p.size / 2, p.x + p.length / 2, p.y + p.size / 2, paint)
                    c.restore()
                }
                else -> Unit
            }
        }
        paint.shader = null
    }

    /** Bandas de niebla que se desplazan despacio. */
    private fun drawFog(c: Canvas, w: Int, h: Int, a: Atmosphere, t: Float) {
        val alpha = (40 + a.intensity * 1.2f).roundToInt().coerceAtMost(170)
        for (i in 0 until 4) {
            val y = h * (0.35f + i * 0.17f)
            val shift = sin(t * 0.12f + i) * w * 0.12f
            paint.reset()
            paint.shader = LinearGradient(
                shift - w * 0.3f, 0f, shift + w * 1.3f, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(alpha, 235, 240, 244), Color.TRANSPARENT), null, Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, y, w.toFloat(), y + h * 0.14f, paint)
        }
        paint.shader = null
    }
}
