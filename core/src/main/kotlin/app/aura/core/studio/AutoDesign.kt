package app.aura.core.studio

import kotlin.math.abs

object AutoDesign {
    data class Proposal(val title: String, val design: ClockDesign)

    fun propose(pixels: IntArray, width: Int, height: Int, subject: IntArray?, round: Int): List<Proposal> {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(subject == null || subject.size == pixels.size)
        val candidates = (0..16).map { index ->
            val y = 0.18f + index * 0.04f
            val region = floatArrayOf(0.10f, y - 0.08f, 0.90f, y + 0.08f)
            var occupied = 0f
            var detail = 0f
            var count = 0
            val split = subject != null
            for (row in (region[1] * height).toInt() until (region[3] * height).toInt()) {
                for (col in (region[0] * width).toInt().coerceAtLeast(1) until (region[2] * width).toInt()) {
                    if (split && col.toFloat() / width in 0.39f..0.61f) continue
                    val i = row * width + col
                    occupied += ((subject?.get(i) ?: 0) ushr 24) / 255f
                    detail += abs(Fusion.luminance(pixels[i]) - Fusion.luminance(pixels[i - 1]))
                    count++
                }
            }
            Candidate(y, region, (occupied * 3f + detail) / count.coerceAtLeast(1) + abs(y - 0.36f) * 0.12f)
        }.sortedBy { it.score }
        val preferred = candidates.take(7)
        val primary = preferred[Math.floorMod(round, preferred.size)]
        val separated = candidates.filter { abs(it.y - primary.y) >= 0.12f }
        val secondary = separated[Math.floorMod(round, separated.size)]
        return listOf(
            build("Editorial", primary, pixels, width, height, null, false),
            build("Monumental", secondary, pixels, width, height, ClockMaterial.MINIMAL, false),
            build("Profundidad".takeIf { subject != null } ?: "Luz", primary, pixels, width, height, ClockMaterial.METAL, subject != null),
        )
    }

    private data class Candidate(val y: Float, val region: FloatArray, val score: Float)

    private fun build(
        title: String, candidate: Candidate, pixels: IntArray, width: Int, height: Int,
        material: ClockMaterial?, split: Boolean,
    ): Proposal {
        val harmony = Fusion.harmonize(Fusion.analyze(pixels, width, height, candidate.region))
        val design = ClockDesign(
            customFont = if (material == ClockMaterial.MINIMAL) "clock_anton" else "clock_bebas",
            color = harmony.color,
            accentColor = harmony.accentColor,
            lightAngle = harmony.lightAngle,
            centerY = candidate.y,
            size = if (split) 0.23f else 0.29f,
            stretchX = 0.82f,
            stretchY = 1.32f,
            spread = if (split) 0.54f else 0f,
            showColon = !split,
            behindSubject = split,
            informationMode = ClockInformationMode.ESSENTIAL,
            artistic = ArtisticClock(enabled = true),
        ).withMaterial(material ?: harmony.material)
        return Proposal(title, design.copy(shadow = maxOf(design.shadow, 42)))
    }
}
