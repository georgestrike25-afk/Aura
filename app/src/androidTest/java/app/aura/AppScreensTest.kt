package app.aura

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.aura.core.settings.LocationMode
import app.aura.core.weather.Coordinates
import app.aura.core.weather.Place
import app.aura.ui.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Abre la app real y captura cada pantalla principal para revisión visual. */
@RunWith(AndroidJUnit4::class)
class AppScreensTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val out = File(instrumentation.targetContext.filesDir, "aura-shots").apply { mkdirs() }

    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(1200)
        device.takeScreenshot(File(out, "app_$name.png"))
    }

    /** En emuladores recién arrancados puede aparecer «X no responde»: se descarta con «Esperar». */
    private fun dismissSystemDialogs() {
        device.findObject(By.text(java.util.regex.Pattern.compile("(?i)wait|esperar")))?.click()
    }

    private fun tap(text: String) {
        dismissSystemDialogs()
        val node = device.wait(Until.findObject(By.text(text)), 15_000)
        assertNotNull("No se encontró «$text»", node)
        node.click()
    }

    @org.junit.Before
    fun withRealPlace() = kotlinx.coroutines.runBlocking {
        val c = instrumentation.targetContext.aura
        val madrid = Place(
            "om:3117735", "Madrid", "Madrid", "España", Coordinates(40.4165, -3.7026), "Europe/Madrid",
        )
        c.settings.update { it.copy(locationMode = LocationMode.MANUAL, manualPlace = madrid, savedPlaces = listOf(madrid)) }
        c.weather.refresh(manual = true)
        Unit
    }

    @Test
    fun captureMainScreens() {
        ActivityScenario.launch(MainActivity::class.java).use {
            dismissSystemDialogs()
            assertNotNull("Aura debe abrir en Clima", device.wait(Until.findObject(By.desc("Actualizar")), 30_000))
            shot("forecast")
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 30); shot("forecast_scrolled")
            tap("Widgets"); shot("widgets")
            tap("Studio"); shot("studio")
            tap("Signature"); shot("signature")
            tap("Ajustes"); shot("settings")
            tap("Widgets")
            device.swipe(device.displayWidth / 2, device.displayHeight * 2 / 3, device.displayWidth / 2, device.displayHeight / 3, 30)
            tap("Personalizar"); shot("customize")
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 3, 30)
            shot("customize_controls")
        }
    }

    /** Fotos propias en Signature: sin repetidos, agrupadas por contenido y con visor que pasa de una a otra. */
    @Test
    fun signatureImportsWithoutDuplicatesAndOpensViewer() = kotlinx.coroutines.runBlocking {
        val ctx = instrumentation.targetContext
        fun photo(name: String, sky: Int, ground: Int): android.net.Uri {
            val b = android.graphics.Bitmap.createBitmap(1080, 2340, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(b)
            c.drawColor(sky)
            val p = android.graphics.Paint().apply { color = ground; isAntiAlias = true }
            val path = android.graphics.Path().apply { moveTo(0f, 2340f); lineTo(540f, 1000f); lineTo(1080f, 2340f); close() }
            c.drawPath(path, p)
            val f = File(ctx.cacheDir, name)
            f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            return android.net.Uri.fromFile(f)
        }
        val repo = app.aura.signature.SignatureRepository(ctx)
        val a = photo("a.jpg", 0xFF3A6EA5.toInt(), 0xFFF2F2F2.toInt())
        val b = photo("b.jpg", 0xFF2B1B3D.toInt(), 0xFFE07A2E.toInt())
        val first = repo.importImages(listOf(a, b))
        val again = repo.importImages(listOf(a))
        println("AURA_SIGNATURE_IMPORT $first $again")
        org.junit.Assert.assertEquals(2, first.added)
        org.junit.Assert.assertEquals(1, again.repeated)
        org.junit.Assert.assertEquals(0, again.added)
        org.junit.Assert.assertEquals("exporta las fotos propias a la galería", 2, repo.exportMine())
        val titles = repo.cached().flatMap { it.items }.map { it.work.title }
        org.junit.Assert.assertEquals(titles.size, titles.distinct().size)

        ActivityScenario.launch(MainActivity::class.java).use {
            dismissSystemDialogs()
            tap("Signature")
            device.wait(Until.findObject(By.desc(titles.first())), 15_000)?.click()
            device.wait(Until.findObject(By.text("Usar en Studio")), 15_000)
            shot("signature_viewer")
            device.swipe(device.displayWidth * 4 / 5, device.displayHeight / 2, device.displayWidth / 5, device.displayHeight / 2, 20)
            assertNotNull("El visor no pasó a la siguiente", device.wait(Until.findObject(By.textContains("2 de")), 10_000))
            shot("signature_viewer_next")
            device.pressBack()
            shot("signature_mine")
        }
        Unit
    }
}
