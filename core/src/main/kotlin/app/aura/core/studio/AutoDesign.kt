package app.aura.core.studio

import app.aura.core.time.HourFormat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Aura AutoDesign: mira la foto (paleta, luz, zonas tranquilas y, si hay máscara, el protagonista) y arma
 * tres relojes distintos, solo con la hora (nunca fecha ni clima).
 *
 * - **Estilos**: seis composiciones (Monumento, Apilado, Gigante, Editorial, Separado, Lateral); en cada
 *   tanda salen tres distintas al azar.
 * - **Tipografía**: cada estilo elige al azar entre las fuentes que le quedan bien (de las 61 de Aura), con
 *   tamaños calculados con sus medidas reales para que siempre entren.
 * - **Color**: sale de la paleta de la foto (tono, acento, monocromo o contraste suave), con contraste
 *   controlado contra la zona del reloj para que se lea.
 * - **Integración**: camaleón suave, el material que sugiere la foto (Elements) y la luz de la escena.
 *
 * «Generar alternativas» (otra vuelta) cambia estilos, fuentes, colores y acabados.
 */
object AutoDesign {
    data class Proposal(val title: String, val design: ClockDesign)

    /** Escenas con un elemento protagonista: la propuesta principal usa una fuente y un material a tono. */
    enum class Theme(val material: ClockMaterial) { FIRE(ClockMaterial.IGNIS), WATER(ClockMaterial.AQUA) }

    /** Fuente temática: incluida en Aura o importada por la persona (con sus medidas en em). */
    data class ThemedFont(val id: String, val advance: Float, val cap: Float, val theme: Theme)

    /** Incluidas en Aura (licencia OFL, se pueden vender con la app). */
    private val builtInThemed = listOf(
        "clock_rubikburned" to Theme.FIRE,
        "clock_rubikwetpaint" to Theme.WATER,
        "clock_rubikpuddles" to Theme.WATER,
    ).mapNotNull { (id, th) -> FontMetrics.digits[id]?.let { ThemedFont(id, it.first, it.second, th) } }

    /** Palabras en el nombre de una fuente importada que la asocian a fuego o agua. */
    fun themeOfName(name: String): Theme? {
        val n = name.lowercase()
        return when {
            listOf("burn", "fire", "flame", "fuego", "llama", "lava", "inferno", "blaze", "ember").any { it in n } -> Theme.FIRE
            listOf("water", "splash", "agua", "aqua", "ocean", "wave", "rain", "drip", "liquid", "mar").any { it in n } -> Theme.WATER
            else -> null
        }
    }

    /** ¿La foto es una escena de fuego o de agua? (por sus colores, igual que el material que sugiere Fusion). */
    fun themeOf(t: ImageTraits): Theme? {
        val material = Fusion.harmonize(t).material
        val vivid = t.palette.zip(t.weights).filter { it.second > 0.06f }.map { hsl(it.first) }
        val fiery = vivid.any { (it[0] < 45f || it[0] > 340f) && it[1] > 0.55f && it[2] in 0.3f..0.75f }
        return when {
            material == ClockMaterial.IGNIS || (fiery && t.warmth > 0.1f) -> Theme.FIRE
            material == ClockMaterial.AQUA || material == ClockMaterial.GLACIUS -> Theme.WATER
            else -> null
        }
    }

    enum class Style(val title: String) {
        MONUMENT("Monumento"), STACKED("Apilado"), GIANT("Gigante"),
        EDITORIAL("Editorial"), SEPARATED("Separado"), LATERAL("Lateral"),
    }

    // Fuentes que quedan bien en cada estilo (todas con medidas en FontMetrics).
    private val condensed = listOf(
        "clock_anton", "clock_bebas", "clock_fjallaone", "clock_oswald", "clock_staatliches", "clock_teko",
        "clock_russo", "clock_bodonimoda", "clock_dmserifdisplay", "clock_abril", "clock_cinzel",
    )
    private val giant = listOf(
        "clock_anton", "clock_bebas", "clock_oswald", "clock_teko", "clock_sixcaps", "clock_fjallaone",
        "clock_bodonimoda", "clock_staatliches", "clock_italiana", "clock_poiret",
    )
    private val stacked = listOf(
        "clock_anton", "clock_bebas", "clock_oswald", "clock_fjallaone", "clock_archivoblack", "clock_bowlbyone",
        "clock_russo", "clock_righteous", "clock_bungee", "clock_abril", "clock_alfaslabone", "clock_dmserifdisplay",
        "clock_playfair", "clock_cinzel", "clock_limelight", "clock_orbitron", "clock_audiowide", "clock_yesevaone",
    )
    private val editorial = listOf(
        "clock_playfair", "clock_bodonimoda", "clock_dmserifdisplay", "clock_italiana", "clock_marcellus",
        "clock_cinzel", "clock_yesevaone", "clock_abril", "clock_poiret", "clock_juliussansone", "clock_gruppo",
        "clock_quicksand", "clock_comfortaa", "clock_parisienne", "clock_allura", "clock_greatvibes",
        "clock_lobster", "clock_monoton", "clock_limelight",
    )
    private val wide = listOf(
        "clock_michroma", "clock_orbitron", "clock_zendots", "clock_audiowide", "clock_chakrapetch", "clock_sharetech",
        "clock_unicaone", "clock_nixieone", "clock_oswald", "clock_anton", "clock_bebas", "clock_cinzel",
        "clock_playfair", "clock_righteous", "clock_megrim",
    )

    private val THEMED_STYLES = listOf(Style.MONUMENT, Style.GIANT, Style.STACKED)

    /** Hueco entre horas y minutos (sin dos puntos) o el ancho de los dos puntos, en em. */
    private const val GAP = 0.25f

    fun propose(
        pixels: IntArray, width: Int, height: Int, subject: IntArray?, round: Int, reference: ClockDesign? = null,
        userFonts: List<ThemedFont> = emptyList(),
        /** Semilla de la sesión: cada vez que se abre AutoDesign, otras propuestas (0 = solo según la foto). */
        salt: Long = 0,
        /** Fuentes y estilos mostrados hace poco: se evitan para no repetir. */
        avoidFonts: Set<String> = emptySet(),
        recentStyles: Set<Style> = emptySet(),
    ): List<Proposal> {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(subject == null || subject.size == pixels.size)
        val ctx = Context(pixels, width, height, Scene.read(subject, width, height), reference?.hourFormat ?: HourFormat.SYSTEM)
        // Misma foto y misma vuelta → mismas propuestas; otra vuelta → otras.
        val rnd = Random(fingerprint(pixels) * 31L + round + salt * 7919L)
        // Al azar, pero primero los estilos que no se vieron hace poco.
        var styles = Style.entries.shuffled(rnd).sortedBy { it in recentStyles }.take(3)
        val usedFonts = avoidFonts.toMutableSet()
        val schemes = Scheme.entries.shuffled(rnd)
        // Escena de fuego o agua: la primera propuesta lleva la fuente temática (primero las de la persona) y su material.
        val theme = themeOf(ctx.traits)
        val themed = theme?.let { th -> (userFonts.filter { it.theme == th }.shuffled(rnd) + builtInThemed.filter { it.theme == th }.shuffled(rnd)).firstOrNull() }
        if (themed != null && styles.first() !in THEMED_STYLES) {
            val s0 = THEMED_STYLES.filter { it !in styles }.random(rnd)
            styles = listOf(s0) + styles.drop(1)
        }
        return styles.mapIndexed { i, style ->
            val forced = if (i == 0 && themed != null) themed else null
            val design = build(style, ctx, rnd, schemes[i % schemes.size], usedFonts, forced).copy(hourFormat = ctx.hourFormat)
            usedFonts += design.customFont.orEmpty()
            Proposal(style.title, design)
        }
    }

    // ------------------------------------------------------------------ estilos

    private class Context(
        val px: IntArray, val w: Int, val h: Int, val scene: Scene?, val hourFormat: HourFormat,
    ) {
        val aspect = h / w.toFloat()
        val traits = Fusion.analyze(px, w, h, floatArrayOf(0f, 0f, 1f, 1f))
    }

    private fun pick(pool: List<String>, rnd: Random, used: Set<String>): Pair<String, Pair<Float, Float>> {
        val options = pool.filter { it !in used && it in FontMetrics.digits }.ifEmpty { pool.filter { it in FontMetrics.digits } }
        val id = options.random(rnd)
        return id to FontMetrics.digits.getValue(id)
    }

    /** Tamaño (alto del em / ancho del lienzo) para que [digits] cifras ocupen [target] del ancho. */
    private fun fit(advance: Float, digits: Int, target: Float, stretchX: Float, max: Float = ClockDesign.MAX_SIZE): Float {
        // El avance es el promedio de 0–9: un «00» de una fuente ancha ocupa más. Margen del 12 %.
        val em = (digits * advance + if (digits == 4) GAP else 0f) * 1.12f
        return (target / (em * stretchX)).coerceIn(ClockDesign.MIN_SIZE, max)
    }

    private fun build(style: Style, c: Context, rnd: Random, scheme: Scheme, used: Set<String>, forced: ThemedFont? = null): ClockDesign {
        fun choose(pool: List<String>) = forced?.let { it.id to (it.advance to it.cap) } ?: pick(pool, rnd, used)
        val d = buildStyle(style, c, rnd, scheme, ::choose)
        // Con fuente temática, el material del elemento (Ignis o Aqua) bien presente.
        return if (forced == null) d else d.withMaterial(forced.theme.material).copy(
            elementIntensity = rnd.nextInt(60, 85), textureScale = rnd.nextInt(35, 65), chameleon = 0,
            color = d.color, accentColor = d.accentColor,
        )
    }

    private fun buildStyle(style: Style, c: Context, rnd: Random, scheme: Scheme, choose: (List<String>) -> Pair<String, Pair<Float, Float>>): ClockDesign {
        val scene = c.scene
        val behind = scene != null
        return when (style) {
            Style.MONUMENT -> {
                val (font, m) = choose(condensed)
                val stretchY = rnd.range(1.2f, 1.7f)
                val size = fit(m.first, 4, rnd.range(0.80f, 0.90f), 1f)
                val bandH = m.second * size * stretchY / c.aspect
                val y = if (scene != null) (scene.top - bandH * 0.18f).coerceIn(0.10f + bandH / 2, 0.5f)
                else freeRow(c, 0.5f, 0.44f, bandH, 0.10f + bandH / 2, 0.38f)
                finish(c, rnd, scheme, 0.5f, y, 0.44f, bandH, behind, tilt = true).copy(
                    customFont = font, size = size, stretchY = stretchY,
                )
            }
            Style.GIANT -> {
                val (font, m) = choose(giant)
                val stretchX = rnd.range(0.8f, 0.95f)
                val size = fit(m.first, 4, 0.92f, stretchX)
                val stretchY = (rnd.range(0.36f, 0.44f) * c.aspect / (m.second * size)).coerceIn(1.3f, ClockDesign.MAX_STRETCH)
                val bandH = m.second * size * stretchY / c.aspect
                val y = if (scene != null) scene.top.coerceIn(0.08f + bandH / 2, 0.6f)
                else freeRow(c, 0.5f, 0.46f, bandH, 0.08f + bandH / 2, 0.5f)
                finish(c, rnd, scheme, 0.5f, y, 0.46f, bandH, behind, preferMaterial = true).copy(
                    customFont = font, size = size, stretchX = stretchX, stretchY = stretchY,
                )
            }
            Style.STACKED -> {
                val (font, m) = choose(stacked)
                val side = scene?.freeSide ?: if (rnd.nextBoolean()) -1 else 1
                val x = 0.5f + side * rnd.range(0.12f, 0.2f)
                val size = fit(m.first, 2, rnd.range(0.44f, 0.54f), 1f, max = 0.46f)
                val bandH = (0.86f + m.second) * size / c.aspect
                val y = if (scene != null) (scene.top + bandH * 0.2f).coerceIn(0.12f + bandH / 2, 0.62f)
                else freeRow(c, x, 0.26f, bandH, 0.12f + bandH / 2, 0.5f)
                val d = finish(c, rnd, scheme, x, y, 0.26f, bandH, behind).copy(
                    customFont = font, layout = ClockLayout.VERTICAL, size = size,
                )
                // Un grupo con el acento de la foto (minutos u horas), o los dos iguales.
                when (rnd.nextInt(3)) {
                    0 -> d.copy(artistic = d.artistic.edit(DigitGroup.MINUTES.slots) { it.copy(color = d.accentColor) })
                    1 -> d.copy(artistic = d.artistic.edit(DigitGroup.HOURS.slots) { it.copy(color = d.accentColor) })
                    else -> d
                }
            }
            Style.EDITORIAL -> {
                val (font, m) = choose(editorial)
                val size = fit(m.first + 0.06f, 4, rnd.range(0.56f, 0.70f), 1f)
                val bandH = m.second * size / c.aspect
                val y = if (scene != null) (scene.top - bandH * 0.9f).coerceIn(0.10f + bandH / 2, 0.36f)
                else freeRow(c, 0.5f, 0.36f, bandH, 0.10f + bandH / 2, 0.32f)
                finish(c, rnd, scheme, 0.5f, y, 0.36f, bandH, behind = false, preferMaterial = false, tilt = true).copy(
                    customFont = font, size = size, letterSpacing = 0.06f, showColon = true,
                    shadow = 18,
                )
            }
            Style.SEPARATED -> {
                val (font, m) = choose(wide)
                val size = fit(m.first, 2, rnd.range(0.26f, 0.32f), 1f, max = 0.40f)
                val stretchY = rnd.range(1.0f, 1.5f)
                val bandH = m.second * size * stretchY / c.aspect
                val y = if (scene != null) (scene.top + bandH * 0.3f).coerceIn(0.12f + bandH / 2, 0.55f)
                else freeRow(c, 0.5f, 0.46f, bandH, 0.12f + bandH / 2, 0.45f)
                finish(c, rnd, scheme, 0.5f, y, 0.46f, bandH, behind).copy(
                    customFont = font, size = size, stretchY = stretchY, spread = rnd.range(0.55f, 0.8f),
                )
            }
            Style.LATERAL -> {
                val (font, m) = choose(condensed)
                val side = scene?.freeSide ?: if (rnd.nextBoolean()) -1 else 1
                val x = 0.5f + side * 0.27f
                val size = fit(m.first, 2, 0.30f, 1f, max = 0.34f)
                val stretchY = rnd.range(1.2f, 1.6f)
                val bandH = (0.86f + m.second) * size * stretchY / c.aspect
                val y = if (scene != null) (scene.top + bandH * 0.1f).coerceIn(0.10f + bandH / 2, 0.6f)
                else freeRow(c, x, 0.18f, bandH, 0.10f + bandH / 2, 0.55f)
                finish(c, rnd, scheme, x, y, 0.18f, bandH, behind).copy(
                    customFont = font, layout = ClockLayout.VERTICAL, size = size, stretchY = stretchY,
                )
            }
        }
    }

    /** Color, acabado e integración con la foto en la zona donde va el reloj. */
    private fun finish(
        c: Context, rnd: Random, scheme: Scheme, x: Float, y: Float, halfW: Float, bandH: Float,
        behind: Boolean, preferMaterial: Boolean = rnd.nextBoolean(), tilt: Boolean = false,
    ): ClockDesign {
        val region = floatArrayOf(
            (x - halfW).coerceIn(0f, 1f), (y - bandH / 2f).coerceIn(0f, 1f),
            (x + halfW).coerceIn(0f, 1f), (y + bandH / 2f).coerceIn(0f, 1f),
        )
        val local = Fusion.analyze(c.px, c.w, c.h, region)
        val harmony = Fusion.harmonize(local)
        val (main, accent) = colors(c.traits, local.regionBrightness, scheme, local)
        val material = when {
            preferMaterial && harmony.material.isElement -> harmony.material
            preferMaterial -> listOf(ClockMaterial.GLASS, ClockMaterial.METAL).random(rnd)
            else -> ClockMaterial.MINIMAL
        }
        var d = ClockDesign(
            boldHours = false,
            color = main,
            accentColor = accent,
            lightAngle = harmony.lightAngle,
            centerX = x,
            centerY = y,
            showColon = false,
            informationMode = ClockInformationMode.PURE,
            showDate = false,
            showWeather = false,
            behindSubject = behind,
            artistic = ArtisticClock(enabled = true),
        ).withMaterial(material)
        if (material.isElement) d = d.copy(elementIntensity = rnd.nextInt(40, 70), textureScale = rnd.nextInt(30, 70))
        // Integrado a la foto: camaleón (las cifras toman la textura y el color de lo que tienen detrás, con un
        // «lift» para que se sigan leyendo), algo de transparencia y sombras suaves. Nada de cifras «pegadas».
        if (!material.isElement && rnd.nextInt(5) < 3) {
            d = d.copy(chameleon = rnd.nextInt(35, 65), chameleonLift = if (local.regionBrightness < 0.5f) rnd.nextInt(30, 55) else -rnd.nextInt(30, 55))
        }
        // Escena nocturna: a veces un resplandor leve del color de acento.
        if (c.traits.brightness < 0.25f && material == ClockMaterial.MINIMAL && rnd.nextBoolean()) d = d.copy(glow = rnd.nextInt(10, 25))
        // Un leve giro en algunas propuestas horizontales, siguiendo la luz de la escena.
        // (Solo en estilos que no llegan a los bordes: un Gigante girado se saldría de la pantalla.)
        val angle = if (tilt && rnd.nextInt(4) == 0) (if (rnd.nextBoolean()) 1 else -1) * rnd.range(2.5f, 6f) else 0f
        return d.copy(
            shadow = rnd.nextInt(10, if (local.contrast > 0.22f) 32 else 24),
            opacity = rnd.nextInt(74, 93),
            rotation = angle,
        )
    }

    // ------------------------------------------------------------------ color

    enum class Scheme { TINT, ACCENT, MONO, SOFT_COMPLEMENT, TONAL }

    /**
     * (color principal, acento) tomados de la foto. El principal siempre con contraste ≥ 3:1 contra la zona
     * del reloj; el acento, el color más vivo de la imagen ajustado para que se distinga.
     */
    internal fun colors(t: ImageTraits, regionBrightness: Float, scheme: Scheme, local: ImageTraits? = null): Pair<Int, Int> {
        val light = regionBrightness < 0.55f
        val weighted = t.palette.zip(t.weights)
        val dominant = weighted.filter { it.second > 0.08f }.maxByOrNull { hsl(it.first)[1] * (0.5f + it.second) }?.first
            ?: t.palette.firstOrNull() ?: 0xFF808080.toInt()
        val vivid = t.palette.maxByOrNull { hsl(it)[1] * (0.3f + hsl(it)[2]) } ?: dominant
        val (h, s, _) = hsl(dominant).let { Triple(it[0], it[1], it[2]) }
        val vh = hsl(vivid)[0]
        // Ni blanco ni negro puros: un tono que pertenece a la foto.
        val mainL = if (light) 0.86f else 0.18f
        val main = when (scheme) {
            Scheme.TINT -> fromHsl(h, min(0.35f, s * 0.6f + 0.1f), mainL)
            Scheme.ACCENT -> fromHsl(h, 0.12f, if (light) 0.95f else 0.10f)
            Scheme.MONO -> fromHsl(vh, min(0.55f, hsl(vivid)[1]), if (light) 0.84f else 0.18f)
            Scheme.SOFT_COMPLEMENT -> fromHsl(h, 0.18f, mainL)
            // Tono sobre tono: el mismo color de la zona del reloj, más claro u oscuro.
            Scheme.TONAL -> {
                val zone = hsl(local?.palette?.firstOrNull() ?: dominant)
                fromHsl(zone[0], min(0.45f, zone[1] + 0.08f), (zone[2] + if (light) 0.38f else -0.38f).coerceIn(0.12f, 0.9f))
            }
        }
        val accent = when (scheme) {
            Scheme.TINT -> fromHsl(h, max(0.45f, s), if (light) 0.66f else 0.38f)
            Scheme.ACCENT -> fromHsl(vh, max(0.55f, hsl(vivid)[1]), if (light) 0.62f else 0.40f)
            Scheme.MONO -> fromHsl(vh, max(0.5f, hsl(vivid)[1]), if (light) 0.66f else 0.32f)
            Scheme.SOFT_COMPLEMENT -> fromHsl((h + 165f) % 360f, 0.42f, if (light) 0.70f else 0.35f)
            Scheme.TONAL -> fromHsl(vh, max(0.45f, hsl(vivid)[1]), if (light) 0.64f else 0.36f)
        }
        // Contraste suficiente para leer la hora, sin que resalte de más (antes 3:1).
        return legible(main, regionBrightness, minRatio = 2.2f) to legible(accent, regionBrightness, minRatio = 1.7f)
    }

    /** Aclara u oscurece [color] (sin cambiar su tono) hasta que se lea sobre la zona del reloj. */
    private fun legible(color: Int, regionBrightness: Float, minRatio: Float = 3f): Int {
        val hslC = hsl(color)
        var l = hslC[2]
        val up = regionBrightness < 0.55f
        repeat(20) {
            val c = fromHsl(hslC[0], hslC[1], l)
            if (Fusion.contrastRatio(Fusion.luminance(c), regionBrightness) >= minRatio) return c
            l = (l + if (up) 0.04f else -0.04f).coerceIn(0f, 1f)
        }
        return fromHsl(hslC[0], hslC[1], l)
    }

    internal fun hsl(c: Int): FloatArray {
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d < 1e-4f) return floatArrayOf(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (mx) {
            r -> ((g - b) / d).let { if (it < 0) it + 6f else it }
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } * 60f
        return floatArrayOf(h, s.coerceIn(0f, 1f), l)
    }

    internal fun fromHsl(h: Float, s: Float, l: Float): Int {
        val cc = (1f - abs(2f * l - 1f)) * s
        val x = cc * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - cc / 2f
        val (r, g, b) = when ((((h % 360f) + 360f) % 360f).toInt() / 60) {
            0 -> Triple(cc, x, 0f)
            1 -> Triple(x, cc, 0f)
            2 -> Triple(0f, cc, x)
            3 -> Triple(0f, x, cc)
            4 -> Triple(x, 0f, cc)
            else -> Triple(cc, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    // ------------------------------------------------------------------ escena

    private fun fingerprint(px: IntArray): Int {
        var acc = 17
        var i = 0
        while (i < px.size) { acc = acc * 31 + (px[i] and 0xF0F0F0); i += maxOf(1, px.size / 64) }
        return acc
    }

    private fun Random.range(a: Float, b: Float) = a + nextFloat() * (b - a)

    /** Lo que se sabe del sujeto: dónde empieza (arriba) y de qué lado queda más lugar libre. */
    private data class Scene(val top: Float, val freeSide: Int) {
        companion object {
            fun read(subject: IntArray?, w: Int, h: Int): Scene? {
                subject ?: return null
                fun a(x: Int, y: Int) = ((subject[y * w + x] ushr 24) and 0xFF) / 255f
                var total = 0f
                var left = 0f
                var right = 0f
                var top = -1
                for (y in 0 until h) {
                    var row = 0f
                    for (x in 0 until w) {
                        val v = a(x, y)
                        row += v
                        if (x < w / 2) left += v else right += v
                    }
                    total += row
                    if (top < 0 && row / w > 0.06f) top = y
                }
                if (total / (w * h) < 0.01f || top < 0) return null
                return Scene(top / h.toFloat(), if (left > right) 1 else -1)
            }
        }
    }

    /** Fila más tranquila de la foto (poco detalle) para la franja de alto [bandH], entre [minY] y [maxY]. */
    private fun freeRow(c: Context, x: Float, halfW: Float, bandH: Float, minY: Float, maxY: Float): Float {
        if (maxY <= minY) return minY
        val light = FloatArray(c.px.size) { Fusion.luminance(c.px[it]) }
        var best = minY
        var bestScore = Float.MAX_VALUE
        var y = minY
        while (y <= maxY + 1e-4f) {
            val y0 = ((y - bandH / 2f) * c.h).toInt().coerceIn(0, c.h - 1)
            val y1 = ((y + bandH / 2f) * c.h).toInt().coerceIn(y0 + 1, c.h)
            val x0 = ((x - halfW) * c.w).toInt().coerceIn(1, c.w - 1)
            val x1 = ((x + halfW) * c.w).toInt().coerceIn(x0 + 1, c.w)
            var detail = 0f
            var n = 0
            for (row in y0 until y1) for (col in x0 until x1) {
                val i = row * c.w + col
                detail += abs(light[i] - light[i - 1])
                n++
            }
            val score = detail / n.coerceAtLeast(1) + (y - minY) * 0.04f
            if (score < bestScore) { bestScore = score; best = y }
            y += 0.02f
        }
        return best
    }
}
