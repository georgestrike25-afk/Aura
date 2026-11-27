package app.aura.core

import app.aura.core.studio.SubjectPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectPickTest {
    private val w = 20
    private val h = 10
    /** Dos objetos que reconoce el modelo: uno a la izquierda (x 2–6) y otro a la derecha (x 12–17). */
    private val alpha = IntArray(w * h) { i -> val x = i % w; if (x in 2..6 || x in 12..17) 230 else 10 }

    private fun stroke(vararg xs: Int) = BooleanArray(w * h) { i -> i / w == 5 && i % w in xs }

    @Test
    fun keepsOnlyTheObjectTheFingerTouched() {
        val sel = SubjectPick.touched(alpha, stroke(3, 4, 5), w, h)!!
        assertTrue(sel[2 * w + 2] && sel[9 * w + 6])
        assertFalse("el otro objeto no se agrega", sel[5 * w + 14])
        assertFalse("el fondo no se agrega", sel[5 * w + 9])
    }

    @Test
    fun strokeOutsideAnyObjectGivesNothing() {
        assertNull(SubjectPick.touched(alpha, stroke(8, 9, 10), w, h))
    }

    @Test
    fun regionLandsInTheFullGrid() {
        val sel = SubjectPick.touched(alpha, stroke(13), w, h)!!
        val region = SubjectPick.toRegion(sel, w, h, offsetX = 100, offsetY = 50, gridW = 400, gridH = 300)
        assertNotNull(region)
        assertEquals(400, region!!.width)
        assertEquals(listOf(50, 112, 118), region.runs.take(3))
    }
}
