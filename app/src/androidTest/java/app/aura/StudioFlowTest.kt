package app.aura

import android.app.Application
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.service.wallpaper.WallpaperService
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.aura.core.studio.AutoDesign
import app.aura.core.studio.ClockText
import app.aura.core.studio.ClockMaterial
import app.aura.studio.render.ClockPainter
import app.aura.studio.render.StudioFonts
import app.aura.core.studio.MaskStroke
import app.aura.core.studio.StudioProject
import app.aura.studio.mask.MaskRenderer
import app.aura.studio.mask.SubjectSegmenter
import app.aura.studio.render.ClockExtras
import app.aura.studio.render.StudioCompositor
import app.aura.studio.wallpaper.WallpaperApplier
import app.aura.studio.ui.StudioViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Flujo completo de Aura Studio en un dispositivo: importar foto → componer reloj 3D → máscara
 * (manual y automática) → exportar → aplicar fondo estático → selector de fondo animado.
 */
@RunWith(AndroidJUnit4::class)
class StudioFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val out = File(context.filesDir, "aura-shots").apply { mkdirs() }
    private val screen = WallpaperApplier.screenSize(context)

    /** Foto sintética: atardecer y una silueta oscura en el centro (el «sujeto»). */
    private fun syntheticPhoto(): Uri {
        val w = 1200
        val h = 2400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFFF6B37A.toInt(), 0xFFE9806C.toInt(), 0xFF3B3357.toInt()), null, Shader.TileMode.CLAMP) })
        val subject = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E0B18.toInt() }
        c.drawCircle(w / 2f, 640f, 150f, subject)
        c.drawRoundRect(w / 2f - 300f, 450f, w / 2f + 300f, h.toFloat() + 100f, 220f, 220f, subject)
        val f = File(context.cacheDir, "studio_test_photo.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return Uri.fromFile(f)
    }

    private fun save(name: String, bmp: Bitmap) {
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val extras = ClockExtras("09", ":50", "Jueves, 29 de octubre · 22° Córdoba")

    @Test
    fun importComposeMaskAndExport() = runBlocking {
        val repo = context.aura.studio
        val created = repo.create(syntheticPhoto(), "Prueba")
        val photo = repo.photo(created.id)
        assertNotNull(photo)
        photo!!
        assertTrue("la foto de trabajo se reduce", maxOf(photo.width, photo.height) <= 3200)

        // Reloj centrado sobre la cabeza del sujeto para comprobar la superposición.
        var project = created.copy(design = created.design.copy(centerY = 0.27f, size = 0.32f))
        val compositor = StudioCompositor(app.aura.studio.render.ClockPainter(app.aura.studio.render.StudioFonts.resolver(context)))

        // Máscara manual: un trazo grueso sobre la silueta (coordenadas normalizadas de la foto).
        val strokes = listOf(
            MaskStroke(listOf(0.5f, 0.24f, 0.5f, 0.32f, 0.5f, 0.6f, 0.5f, 0.95f), 0.11f, erase = false),
        )
        val (mw, mh) = MaskRenderer.workingSize(photo.width, photo.height)
        val mask = MaskRenderer.render(null, strokes, mw, mh)
        assertTrue(MaskRenderer.coverage(mask) > 0.05f)

        val withoutMask = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        compositor.render(withoutMask, photo, null, project, extras)
        save("studio_without_mask", withoutMask)

        project = project.copy(strokes = strokes, hasMask = true)
        val withMask = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        compositor.render(withMask, photo, mask, project, extras)
        save("studio_with_mask", withMask)

        // Franja central de la banda del reloj, sobre la silueta: sin máscara se ve el reloj (claro);
        // con máscara la tapa el sujeto (oscuro).
        val before = stripLuminance(withoutMask)
        val after = stripLuminance(withMask)
        println("AURA_DEPTH before=$before after=$after")
        assertTrue("el sujeto debería tapar el reloj: antes=$before después=$after", after < before && after < 0.2)

        // Cada material produce una imagen distinta.
        val renders = ClockMaterial.entries.map { m ->
            // Sin máscara, para documentar cada acabado completo.
            val p = project.copy(design = project.design.withMaterial(m), hasMask = false)
            Bitmap.createBitmap(screen.width / 2, screen.height / 2, Bitmap.Config.ARGB_8888).also {
                compositor.render(it, photo, mask, p, extras)
                save("studio_${m.name.lowercase()}", it)
            }
        }
        assertNotEquals(renders[0].getPixel(renders[0].width / 2 - 40, (renders[0].height * 0.27f).toInt() + 10), 0)
        renders.drop(1).forEach { it.recycle() }

        // Persistencia: guardar, recargar, duplicar.
        repo.saveBaseMask(project.id, mask)
        repo.save(project, renders.first())
        renders.first().recycle()
        val reloaded = repo.load(project.id)
        assertEquals(project.strokes, reloaded?.strokes)
        assertNotNull(repo.baseMask(project.id))
        val copy = repo.duplicate(project.id)
        assertNotNull(copy)
        assertNotEquals(project.id, copy!!.id)

        // Exportar a Galería y aplicar como fondo estático (API oficial).
        assertTrue(WallpaperApplier.saveToGallery(context, withMask, "Aura-test"))
        WallpaperApplier.applyStatic(context, withMask, WallpaperApplier.Target.HOME)
        repo.setActive(project.id)
        assertEquals(project.id, repo.activeIdBlocking())
        listOf(withoutMask, withMask, mask, photo).forEach { it.recycle() }
    }

    @Test
    fun chameleonBlendsWithThePhotoAndNewFontsStretchAndRotation() = runBlocking {
        val repo = context.aura.studio
        val p = repo.create(syntheticPhoto(), "Camaleón")
        val photo = repo.photo(p.id)!!
        val compositor = StudioCompositor(app.aura.studio.render.ClockPainter(app.aura.studio.render.StudioFonts.resolver(context)))
        val w = screen.width / 2
        val h = screen.height / 2

        // Con camuflaje total y sin efectos, el reloj debe desaparecer en la foto (alineación exacta),
        // aunque esté girado y estirado.
        val invisible = p.copy(
            design = p.design.withMaterial(ClockMaterial.MINIMAL).copy(
                shadow = 0, chameleon = 100, chameleonLift = 0, showDate = false, showWeather = false,
                rotation = 12f, stretchX = 1.4f, stretchY = 0.8f, customFont = "clock_bebas",
            ),
        )
        val camo = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        compositor.render(camo, photo, null, invisible, extras)
        val plain = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        compositor.drawPhoto(Canvas(plain), w, h, photo, invisible)
        var diff = 0.0
        var n = 0
        for (y in 0 until h step 4) for (x in 0 until w step 4) {
            val a = camo.getPixel(x, y)
            val b = plain.getPixel(x, y)
            diff += abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
            n++
        }
        println("AURA_CHAMELEON diff=${diff / n}")
        camo.recycle()
        plain.recycle()
        assertTrue("el camuflaje no coincide con la foto: ${diff / n}", diff / n < 3.0)

        // Ejemplos visibles: camaleón con relieve, fuentes incluidas, ancho/alto y giro.
        val samples = mapOf(
            "studio_chameleon" to p.design.withMaterial(ClockMaterial.METAL).copy(chameleon = 90, chameleonLift = 30, customFont = "clock_anton"),
            "studio_font_orbitron" to p.design.withMaterial(ClockMaterial.NEON).copy(customFont = "clock_orbitron", accentColor = 0xFF7FD6FF.toInt()),
            "studio_font_bebas_tall" to p.design.withMaterial(ClockMaterial.GLASS).copy(customFont = "clock_bebas", stretchY = 1.8f, stretchX = 0.9f),
            "studio_font_playfair_rotated" to p.design.withMaterial(ClockMaterial.STONE).copy(customFont = "clock_playfair", rotation = -8f),
            "studio_warp_perspective" to p.design.withMaterial(ClockMaterial.GLASS).copy(customFont = "clock_anton", warp = app.aura.core.studio.ClockDesign.perspective(1, 0.02f)),
            "studio_spread" to p.design.withMaterial(ClockMaterial.METAL).copy(customFont = "clock_bebas", spread = 1f),
        )
        samples.forEach { (name, design) ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            compositor.render(bmp, photo, null, p.copy(design = design), extras)
            save(name, bmp)
            bmp.recycle()
        }
        photo.recycle()
    }

    @Test
    fun spreadKeepsTheColonInTheMiddleAndVerticalStacksHoursOverMinutes() {
        val painter = app.aura.studio.render.ClockPainter(app.aura.studio.render.StudioFonts.resolver(context))
        val w = 1080
        val h = 2340
        val base = app.aura.core.studio.ClockDesign(centerX = 0.5f, centerY = 0.3f, size = 0.22f).withMaterial(ClockMaterial.MINIMAL).copy(shadow = 0)

        // Separado al 100 %: las cifras van a los bordes y en el centro queda solo «:».
        val spread = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(spread), w, h, base.copy(spread = 1f), extras.copy(subtitle = null))
        save("studio_spread_colon", spread)
        val band = inkColumns(spread, (h * 0.2f).toInt(), (h * 0.4f).toInt())
        val center = band.subList((w * 0.45f).toInt(), (w * 0.55f).toInt())
        val colonCenter = band.indices.filter { it in (w * 0.35f).toInt()..(w * 0.65f).toInt() && band[it] }.average()
        println("AURA_COLON center=$colonCenter")
        assertTrue("los dos puntos deberían quedar en el medio", center.any { it } && abs(colonCenter - w / 2.0) < w * 0.03)
        assertTrue("las horas tocan el borde izquierdo", band.subList(0, (w * 0.1f).toInt()).any { it })
        assertTrue("los minutos tocan el borde derecho", band.subList((w * 0.9f).toInt(), w).any { it })

        // Vertical: horas arriba y minutos abajo, sin dos puntos, cada línea más angosta que «09:50».
        val horizontal = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(horizontal), w, h, base, extras.copy(subtitle = null))
        val vertical = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(vertical), w, h, base.copy(layout = app.aura.core.studio.ClockLayout.VERTICAL), extras.copy(subtitle = null))
        save("studio_vertical", vertical)
        val hRows = inkRows(horizontal)
        val vRows = inkRows(vertical)
        assertTrue("el reloj vertical es más alto", vRows.second - vRows.first > (hRows.second - hRows.first) * 1.7)
        val vWidth = inkColumns(vertical, vRows.first, vRows.second).count { it }
        val hWidth = inkColumns(horizontal, hRows.first, hRows.second).count { it }
        assertTrue("el reloj vertical es más angosto", vWidth < hWidth * 0.75)
        listOf(spread, horizontal, vertical).forEach { it.recycle() }
    }

    @Test
    fun everyBundledFontDrawsTheClockAndTheColonCanBeHidden() {
        val fonts = app.aura.studio.render.StudioFonts
        val painter = app.aura.studio.render.ClockPainter(fonts.resolver(context))
        val w = 1080
        val h = 1200
        val base = app.aura.core.studio.ClockDesign(centerY = 0.5f, size = 0.2f).withMaterial(ClockMaterial.MINIMAL).copy(shadow = 0)
        // Muestrario: todas las fuentes, en dos columnas, en una sola imagen.
        val rows = fonts.all.size
        val sheet = Bitmap.createBitmap(w, (rows + 1) / 2 * 120, Bitmap.Config.ARGB_8888)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 20f }
        val sc = Canvas(sheet).apply { drawColor(0xFF101D2B.toInt()) }
        fonts.all.forEachIndexed { i, f ->
            assertNotNull("no carga ${f.id}", fonts.resolver(context)(f.id))
            val tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            painter.draw(Canvas(tile), w, h, base.copy(customFont = f.id), extras.copy(subtitle = null))
            assertTrue("${f.id} no dibuja nada", inkColumns(tile, 400, 800).count { it } > 50)
            val x = (i % 2) * w / 2
            val y = (i / 2) * 120
            sc.drawBitmap(tile, android.graphics.Rect(0, 480, w, 720), android.graphics.Rect(x, y, x + w / 2, y + 120), null)
            sc.drawText(f.label, x + 10f, y + 22f, label)
            tile.recycle()
        }
        save("studio_fonts_all", sheet)
        sheet.recycle()

        // Sin dos puntos: el centro del reloj queda vacío y las cifras no se mueven.
        val with = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(with), w, h, base, extras.copy(subtitle = null))
        val without = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(without), w, h, base.copy(showColon = false), extras.copy(subtitle = null))
        save("studio_no_colon", without)
        val a = inkColumns(with, 450, 750)
        val b = inkColumns(without, 450, 750)
        assertTrue("sin «:» debería haber menos tinta", b.count { it } < a.count { it })
        assertEquals("las cifras no se mueven", a.indexOfFirst { it }, b.indexOfFirst { it })
        assertEquals(a.indexOfLast { it }, b.indexOfLast { it })
        listOf(with, without).forEach { it.recycle() }
    }

    @Test
    fun artisticDigitsElementsAtmosphereAndDepth() = runBlocking {
        val repo = context.aura.studio
        val created = repo.create(syntheticPhoto(), "Artístico")
        val photo = repo.photo(created.id)!!
        val compositor = StudioCompositor(app.aura.studio.render.ClockPainter(app.aura.studio.render.StudioFonts.resolver(context)))
        val w = screen.width / 2
        val h = screen.height / 2
        fun render(p: StudioProject, mask: Bitmap? = null): Bitmap =
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { compositor.render(it, photo, mask, p, extras.copy(subtitle = null)) }
        fun diff(a: Bitmap, b: Bitmap): Int {
            var n = 0
            for (y in 0 until a.height step 4) for (x in 0 until a.width step 4) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
            return n
        }
        val base = created.copy(design = created.design.copy(centerY = 0.3f, size = 0.3f))

        // Dígitos independientes: mover y girar solo un dígito cambia la imagen y conserva el resto.
        val art = app.aura.core.studio.ArtisticClock(enabled = true)
        val plain = render(base.copy(design = base.design.copy(artistic = art)))
        val moved = render(base.copy(design = base.design.copy(artistic = art.edit(app.aura.core.studio.DigitSlot.MINUTE_UNITS) {
            it.copy(offsetY = 0.15f, rotation = 25f, scaleX = 1.4f, color = 0xFFD8BA8B.toInt(), fontId = "clock_italiana")
        })))
        save("studio_digit_moved", moved)
        assertTrue("el dígito movido debe cambiar la imagen", diff(plain, moved) > 50)

        // Aura Elements: cada material se ve distinto del metal.
        val metal = render(base.copy(design = base.design.withMaterial(ClockMaterial.METAL)))
        ClockMaterial.entries.filter { it.isElement }.forEach { m ->
            val r = render(base.copy(design = base.design.withMaterial(m)))
            save("studio_element_${m.name.lowercase()}", r)
            assertTrue("$m igual al metal", diff(metal, r) > 50)
            r.recycle()
        }

        // Atmosphere: la lluvia y la luz de la noche quedan en la exportación.
        val rain = render(base.copy(atmosphere = app.aura.core.studio.Atmosphere(app.aura.core.studio.AtmosphereEffect.RAIN, 90)))
        save("studio_atmosphere_rain", rain)
        assertTrue("la lluvia debe verse", diff(metal, rain) > 50)

        // Profundidad por dígito: con máscara, un dígito adelante y el resto detrás del sujeto.
        val strokes = listOf(MaskStroke(listOf(0.5f, 0.2f, 0.5f, 0.6f), 0.12f, erase = false))
        val (mw, mh) = MaskRenderer.workingSize(photo.width, photo.height)
        val mask = MaskRenderer.render(null, strokes, mw, mh)
        val masked = base.copy(strokes = strokes, hasMask = true, design = base.design.copy(behindSubject = true, artistic = art))
        val allBehind = render(masked, mask)
        val oneFront = render(masked.copy(design = masked.design.copy(artistic = art.edit(app.aura.core.studio.DigitSlot.HOUR_UNITS) {
            it.copy(depth = app.aura.core.studio.DigitDepth.IN_FRONT_OF_SUBJECT)
        })), mask)
        save("studio_depth_one_front", oneFront)
        assertTrue("un dígito adelante debe verse distinto", diff(allBehind, oneFront) > 10)
        listOf(plain, moved, metal, rain, allBehind, oneFront, mask, photo).forEach { it.recycle() }
    }

    @Test
    fun assistedMaskInStudioFollowsPhotoEdgesAndSurvivesUndoRedoAndSave() = runBlocking {
        val width = 320
        val height = 640
        val photo = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val backgroundColor = Color.rgb(81, 105, 113)
        val subjectColor = Color.rgb(91, 100, 122)
        Canvas(photo).apply {
            drawColor(backgroundColor)
            drawOval(90f, 140f, 230f, 370f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = subjectColor })
        }
        val file = File(context.cacheDir, "studio_edge_selection.png")
        file.outputStream().use { photo.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val repo = context.aura.studio
        val project = repo.create(Uri.fromFile(file), "Selección inteligente").copy(autoMask = true)
        repo.save(project)
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(context.applicationContext as Application)
            ViewModelProvider.create(store, factory)[StudioViewModel::class].also { it.open(project.id) }
        }
        try {
            withTimeout(20_000) { while (model.history.value?.current?.id != project.id) delay(20) }
            withContext(Dispatchers.Main) {
                model.refineStroke(MaskStroke(listOf(0.5f, 0.37f, 0.5f, 0.43f), 0.018f, erase = false))
            }
            withTimeout(20_000) { while (model.history.value?.current?.strokes.isNullOrEmpty()) delay(20) }
            val refined = model.history.value!!.current
            assertNotNull("la pincelada debe producir una región refinada", refined.strokes.single().selection)
            val mask = MaskRenderer.render(null, refined.strokes, width, height)
            assertTrue("llega al borde interior", Color.alpha(mask.getPixel(160, 150)) > 200)
            assertTrue("deja el fondo fuera", Color.alpha(mask.getPixel(60, 230)) < 20)
            val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(overlay).apply {
                drawBitmap(photo, 0f, 0f, null)
                drawBitmap(mask, 0f, 0f, Paint().apply { color = Color.argb(150, 229, 128, 107) })
            }
            save("studio_smart_mask", overlay)
            mask.recycle()
            overlay.recycle()
            withContext(Dispatchers.Main) {
                model.undo()
                assertTrue(model.current!!.strokes.isEmpty())
                model.redo()
                assertEquals(refined.strokes, model.current!!.strokes)
            }
            repo.save(refined)
            assertEquals(refined.strokes, repo.load(project.id)?.strokes)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            photo.recycle()
        }
    }

    /** Columnas con tinta (alfa visible) entre las filas indicadas. */
    @Test
    fun autoDesignDigitsStayOnCanvasAndDoNotOverlapForEveryMinute() {
        val pixels = IntArray(72 * 156) { 0xFF253044.toInt() }
        val mask = IntArray(pixels.size) { i -> if (i % 72 in 30..42 && i / 72 >= 47) Color.BLACK else 0 }
        val painter = ClockPainter(StudioFonts.resolver(context))
        for (proposal in (0 until 5).flatMap { AutoDesign.propose(pixels, 72, 156, mask, it) }) {
            val d = proposal.design
            for (is24 in listOf(true, false)) {
                for (minute in 0 until 24 * 60) {
                    val (hours, minutes) = ClockText.parts(minute / 60, minute % 60, is24)
                    val extra = ClockExtras(hours, if (is24) minutes else "$minutes pm", null)
                    val rects = painter.digitBounds(1440, 3120, d, extra).values.sortedBy { it.left }
                    val label = "${proposal.title} $hours$minutes ($is24)"
                    assertEquals(label, hours.length + 2, rects.size)
                    assertTrue(label, rects.all { it.left >= 0 && it.top >= 0 && it.right <= 1440 && it.bottom <= 3120 })
                    assertTrue(label, rects.indices.all { index ->
                        val a = rects[index]
                        rects.drop(index + 1).none { b ->
                            minOf(a.right, b.right) - maxOf(a.left, b.left) > 0.5f &&
                                minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > 0.5f
                        }
                    })
                }
            }
        }
    }

    private fun inkColumns(b: Bitmap, top: Int, bottom: Int): List<Boolean> = List(b.width) { x ->
        (top until bottom step 3).any { y -> Color.alpha(b.getPixel(x, y)) > 128 }
    }

    /** Primera y última fila con tinta en la mitad superior (el reloj, sin el subtítulo). */
    private fun inkRows(b: Bitmap): Pair<Int, Int> {
        val rows = (0 until b.height / 2).filter { y -> (0 until b.width step 3).any { x -> Color.alpha(b.getPixel(x, y)) > 128 } }
        return rows.first() to rows.last()
    }

    @Test
    fun automaticSegmentationRunsLocallyOrReportsUnavailable() = runBlocking {
        val repo = context.aura.studio
        val p = repo.create(syntheticPhoto(), "Auto")
        val photo = repo.photo(p.id)!!
        val result = SubjectSegmenter().segment(photo, 0.5f)
        // En el emulador puede faltar el módulo de Play Services; la app debe degradar a modo manual.
        println("AURA_SEGMENTATION $result")
        when (result) {
            is SubjectSegmenter.Result.Mask -> {
                save("studio_auto_mask", result.alpha.copy(Bitmap.Config.ARGB_8888, false))
                assertTrue(result.coverage in 0.01f..0.95f)
            }
            is SubjectSegmenter.Result.Unavailable -> assertTrue(result.reason.isNotBlank())
        }
    }

    @Test
    fun liveWallpaperServiceIsDeclaredAndChooserOpens() {
        val services = context.packageManager.queryIntentServices(
            Intent(WallpaperService.SERVICE_INTERFACE).setPackage(context.packageName), PackageManager.GET_META_DATA,
        )
        assertEquals(1, services.size)
        runBlocking {
            val repo = context.aura.studio
            val p = repo.create(syntheticPhoto(), "Animado")
            repo.save(p.copy(design = p.design.withMaterial(ClockMaterial.NEON)))
            repo.setActive(p.id)
        }
        // El selector del sistema muestra una vista previa en vivo del fondo animado de Aura.
        val intent = WallpaperApplier.liveWallpaperIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle()
        Thread.sleep(5000)
        device.takeScreenshot(File(out, "studio_live_preview.png"))
        device.pressBack()
        assertTrue(WallpaperManager.getInstance(context).isWallpaperSupported)
    }

    private fun stripLuminance(b: Bitmap): Double {
        var sum = 0.0
        var n = 0
        val x0 = b.width / 2 - b.width / 6
        val x1 = b.width / 2 + b.width / 6
        for (y in (b.height * 0.22f).toInt() until (b.height * 0.31f).toInt() step 3) {
            for (x in x0 until x1 step 3) { sum += luminance(b.getPixel(x, y)); n++ }
        }
        return sum / n
    }

    private fun luminance(c: Int) = (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0

    /** Propuestas de AutoDesign sobre una escena tipo bosque con un helecho delante: capturas para revisar. */
    @Test
    fun autoDesignProposalsOnForestScene() = runBlocking {
        val w = 1080
        val h = 2340
        val photo = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(photo)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF1F4A50.toInt(), 0xFF173A36.toInt(), 0xFF0C1E1A.toInt()), null, Shader.TileMode.CLAMP) })
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553FD0C0; maskFilter = android.graphics.BlurMaskFilter(160f, android.graphics.BlurMaskFilter.Blur.NORMAL) }
        c.drawCircle(w * 0.55f, h * 0.45f, 260f, glow)
        val fern = android.graphics.Path().apply {
            moveTo(w * 0.47f, h.toFloat()); cubicTo(w * 0.40f, h * 0.7f, w * 0.62f, h * 0.55f, w * 0.50f, h * 0.36f)
            cubicTo(w * 0.44f, h * 0.30f, w * 0.58f, h * 0.28f, w * 0.56f, h * 0.33f)
            cubicTo(w * 0.66f, h * 0.5f, w * 0.50f, h * 0.7f, w * 0.56f, h.toFloat()); close()
        }
        c.drawPath(fern, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5FAE6A.toInt() })
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawPath(fern, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        val small = Bitmap.createScaledBitmap(photo, 72, 156, true)
        val smallMask = Bitmap.createScaledBitmap(mask, 72, 156, true)
        val pixels = IntArray(72 * 156).also { small.getPixels(it, 0, 72, 0, 0, 72, 156) }
        val subject = IntArray(72 * 156).also { smallMask.getPixels(it, 0, 72, 0, 0, 72, 156) }
        val compositor = StudioCompositor(ClockPainter(StudioFonts.resolver(context)))
        val project = context.aura.studio.create(syntheticPhoto(), "AutoDesign")
        val alpha = mask.extractAlpha()
        for (round in 0 until 3) {
            AutoDesign.propose(pixels, 72, 156, subject, round).forEachIndexed { i, p ->
                println("AURA_AUTODESIGN r$round ${p.title} ${p.design.customFont} ${p.design.material} #${Integer.toHexString(p.design.color)} #${Integer.toHexString(p.design.accentColor)} cam=${p.design.chameleon}")
                val out = Bitmap.createBitmap(w / 2, h / 2, Bitmap.Config.ARGB_8888)
                compositor.render(out, photo, alpha, project.copy(design = p.design, hasMask = true), ClockExtras("15", ":37", null))
                save("autodesign_r${round}_${i}_${p.title.lowercase()}", out)
                out.recycle()
            }
        }
    }
}
