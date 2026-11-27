package app.aura.core

import app.aura.core.studio.EdgeSelection
import app.aura.core.studio.MaskRegion
import app.aura.core.studio.MaskStroke
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeSelectionTest {
    private val side = 80
    private val center = side / 2
    private val count = side * side
    private val marked = BooleanArray(count) { position ->
        inEllipse(position, radiusX = 4, radiusY = 9)
    }
    private val objectShape = BooleanArray(count) { inEllipse(it, radiusX = 15, radiusY = 19) }

    @Test
    fun followsAContourWithAlmostEqualLuminance() {
        val photo = IntArray(count) {
            if (objectShape[it]) 0xFF857F84.toInt() else 0xFF808080.toInt()
        }
        val region = EdgeSelection.refine(photo, marked, side, side)!!
        assertFollowsSubject(region)
    }

    @Test
    fun usesASubjectPriorWhenThePhotoHasNoLocalContrast() {
        val photo = IntArray(count) { 0xFF808080.toInt() }
        val foreground = IntArray(count) { if (objectShape[it]) 255 else 0 }
        assertNull(EdgeSelection.refine(photo, marked, side, side))
        assertFollowsSubject(EdgeSelection.refine(photo, marked, side, side, foreground)!!)
    }

    @Test
    fun keepsOldBrushStrokesAndSerializesRefinedOnes() {
        val old = Json.decodeFromString<MaskStroke>("""{"points":[0.5,0.5],"radius":0.1,"erase":false}""")
        assertNull(old.selection)
        val refined = old.copy(selection = MaskRegion(80, 80, listOf(40, 30, 51)))
        assertEquals(refined, Json.decodeFromString<MaskStroke>(Json.encodeToString(refined)))
    }

    private fun assertFollowsSubject(region: MaskRegion) {
        val inside = BooleanArray(count)
        for (i in region.runs.indices step 3) {
            val offset = region.runs[i] * side
            for (x in region.runs[i + 1] until region.runs[i + 2]) inside[offset + x] = true
        }
        val intersection = inside.indices.count { inside[it] && objectShape[it] }
        val union = inside.indices.count { inside[it] || objectShape[it] }
        val iou = intersection.toFloat() / union
        assertTrue("coincidencia con el contorno: $iou", iou > 0.86f)
    }

    private fun inEllipse(position: Int, radiusX: Int, radiusY: Int): Boolean {
        val dx = (position % side - center).toFloat() / radiusX
        val dy = (position / side - center).toFloat() / radiusY
        return dx * dx + dy * dy <= 1f
    }
}
