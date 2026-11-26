package app.aura.core.studio

import kotlin.math.abs

object AutoDesign {
    data class Proposal(val title: String, val design: ClockDesign)

    fun propose(pixels: IntArray, width: Int, height: Int, subject: IntArray?, round: Int, reference: ClockDesign? = null): List<Proposal> {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(subject == null || subject.size == pixels.size)
        if (reference != null) {
            val style = reference.copy(
                informationMode = ClockInformationMode.PURE, showDate = false, showWeather = false,
                behindSubject = reference.behindSubject && subject != null,
            )
            val shift = listOf(-0.045f, 0.045f, -0.075f, 0.075f)[Math.floorMod(round, 4)]
            return listOf(
                Proposal("Tu estilo", style),
                Proposal("Encuadre alternativo", style.copy(centerY = (style.centerY + shift).coerceIn(0.12f, 0.88f))),
                Proposal("Cifras más altas", style.copy(stretchY = (style.stretchY * 1.12f).coerceAtMost(ClockDesign.MAX_STRETCH),
                    stretchX = (style.stretchX * 0.92f).coerceAtLeast(ClockDesign.MIN_STRETCH))),
            )
        }
        val candidates = candidates(pixels, width, height, subject, separate = false)
        val preferred = candidates.take(7)
        val primary = preferred[Math.floorMod(round, preferred.size)]
        val separated = candidates.filter { abs(it.y - primary.y) >= 0.12f }
        val secondary = separated[Math.floorMod(round, separated.size)]
        val depth = if (subject != null) candidates(pixels, width, height, subject, separate = true).take(5)
            .let { it[Math.floorMod(round, it.size)] } else primary
        return listOf(
            build("Metal suave", primary, pixels, width, height, ClockMaterial.METAL, false),
            build("Vidrio", secondary, pixels, width, height, ClockMaterial.GLASS, false),
            build("Profundidad".takeIf { subject != null } ?: "Cristal", depth, pixels, width, height, ClockMaterial.GLASS, subject != null),
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
        title: String, candidate: Candidate, pixels: IntArray, width: Int, height: Int,
        material: ClockMaterial?, split: Boolean,
    ): Proposal {
        val harmony = Fusion.harmonize(Fusion.analyze(pixels, width, height, candidate.region))
        val design = ClockDesign(
            customFont = if (material == ClockMaterial.METAL) "clock_bebas" else "clock_oswald",
            boldHours = false,
            color = harmony.color,
            accentColor = harmony.accentColor,
            lightAngle = harmony.lightAngle,
            centerX = candidate.x,
            centerY = candidate.y,
            size = if (split) 0.23f else 0.29f,
            stretchX = 0.77f,
            stretchY = 1.68f,
            opacity = if (material == ClockMaterial.GLASS) 84 else 96,
            spread = if (split) 0.54f else 0f,
            showColon = false,
            behindSubject = split,
            informationMode = ClockInformationMode.PURE,
            showDate = false,
            showWeather = false,
            artistic = ArtisticClock(enabled = true),
        ).withMaterial(material ?: harmony.material)
        return Proposal(title, design.copy(shadow = maxOf(design.shadow, 42)))
    }
}
