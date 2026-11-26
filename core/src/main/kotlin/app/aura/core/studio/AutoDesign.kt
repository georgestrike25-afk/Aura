package app.aura.core.studio

import app.aura.core.time.HourFormat
import kotlin.math.abs

object AutoDesign {
    data class Proposal(val title: String, val design: ClockDesign)

    fun propose(pixels: IntArray, width: Int, height: Int, subject: IntArray?, round: Int, reference: ClockDesign? = null): List<Proposal> {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(subject == null || subject.size == pixels.size)
        val positions = candidates(pixels, width, height, subject, separate = false)
        val sides = candidates(pixels, width, height, subject, separate = true)
        val top = positions.filter { it.x == 0.5f && it.y <= 0.34f }
        val stacked = positions.filter { it.x != 0.5f && it.y in 0.34f..0.74f }.take(8)
        val materials = listOf(ClockMaterial.GLASS, ClockMaterial.METAL, ClockMaterial.MINIMAL, ClockMaterial.STONE)
        val materialOffset = materials.indexOf(reference?.material).coerceAtLeast(0)
        val first = if (round == 0 && reference != null) reference.material
            else materials[Math.floorMod(round + materialOffset, materials.size)]
        val alternatives = materials.filterNot { it == first }
        val second = alternatives[Math.floorMod(round, alternatives.size)]
        val third = alternatives[Math.floorMod(round + 1, alternatives.size)]
        val condensedFont = reference?.customFont ?: "clock_bebas"
        return listOf(
            build(top[Math.floorMod(round, top.size)], pixels, width, height, first).copy(
                customFont = condensedFont,
                size = 0.23f,
                stretchX = 0.80f,
                stretchY = 1.42f,
                hourFormat = reference?.hourFormat ?: HourFormat.SYSTEM,
            ).let { Proposal("En el cielo", it) },
            build(sides[Math.floorMod(round, sides.size.coerceAtMost(7))], pixels, width, height, second).copy(
                customFont = "clock_oswald",
                size = 0.23f,
                stretchX = 0.78f,
                stretchY = 1.86f,
                spread = 0.69f,
                behindSubject = subject != null,
            ).let { Proposal("Horas a los lados", it) },
            build(stacked[Math.floorMod(round, stacked.size)], pixels, width, height, third).copy(
                customFont = "clock_fjallaone",
                layout = ClockLayout.VERTICAL,
                size = 0.19f,
                stretchX = 0.89f,
                stretchY = 1.48f,
                spread = 0.20f,
            ).let { Proposal("En dos líneas", it) },
        )
    }

    private fun candidates(
        pixels: IntArray, width: Int, height: Int, subject: IntArray?, separate: Boolean,
    ): List<Candidate> {
        val light = FloatArray(pixels.size) { Fusion.luminance(pixels[it]) }
        val columns = if (separate) listOf(0.5f) else listOf(0.5f, 0.42f, 0.58f)
        return columns.flatMap { x ->
            (0..16).map { index ->
                val y = 0.18f + index * 0.04f
                val halfWidth = if (separate) 0.40f else 0.31f
                val region = floatArrayOf(x - halfWidth, y - 0.08f, x + halfWidth, y + 0.08f)
                var occupied = 0f
                var detail = 0f
                var count = 0
                var center = 0f
                var centerCount = 0
                for (row in (region[1] * height).toInt() until (region[3] * height).toInt()) {
                    for (col in (region[0] * width).toInt().coerceAtLeast(1) until (region[2] * width).toInt()) {
                        val i = row * width + col
                        val alpha = ((subject?.get(i) ?: 0) ushr 24) / 255f
                        if (separate && abs(col.toFloat() / width - x) <= 0.13f) {
                            center += alpha
                            centerCount++
                        } else {
                            occupied += alpha
                            detail += abs(light[i] - light[i - 1])
                            count++
                        }
                    }
                }
                val score = (occupied * 3f + detail) / count.coerceAtLeast(1) +
                    abs(y - 0.36f) * 0.12f + abs(x - 0.5f) * 0.24f -
                    center / centerCount.coerceAtLeast(1) * 0.40f
                Candidate(x, y, region, score)
            }
        }.sortedBy { it.score }
    }

    private data class Candidate(val x: Float, val y: Float, val region: FloatArray, val score: Float)

    private fun build(
        candidate: Candidate, pixels: IntArray, width: Int, height: Int,
        material: ClockMaterial,
    ): ClockDesign {
        val harmony = Fusion.harmonize(Fusion.analyze(pixels, width, height, candidate.region))
        return ClockDesign(
            boldHours = false,
            color = harmony.color,
            accentColor = harmony.accentColor,
            lightAngle = harmony.lightAngle,
            centerX = candidate.x,
            centerY = candidate.y,
            opacity = if (material == ClockMaterial.GLASS) 84 else 96,
            showColon = false,
            behindSubject = false,
            informationMode = ClockInformationMode.PURE,
            showDate = false,
            showWeather = false,
            artistic = ArtisticClock(enabled = true),
        ).withMaterial(material).let { it.copy(shadow = maxOf(it.shadow, 42)) }
    }
}
