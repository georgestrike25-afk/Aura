package app.aura.core

import app.aura.core.signature.ContentNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentNamesTest {
    @Test
    fun groupsAndNamesByContent() {
        val r = ContentNames.classify(listOf("Mountain" to 0.92f, "Sky" to 0.88f, "Snow" to 0.7f, "Cloud" to 0.6f))
        assertEquals(ContentNames.Group.MOUNTAINS, r.group)
        assertEquals("Montaña y cielo", r.name)
        val dog = ContentNames.classify(listOf("Dog" to 0.95f, "Pet" to 0.9f))
        assertEquals(ContentNames.Group.ANIMALS, dog.group)
        assertEquals("Perro", dog.name)
    }

    @Test
    fun unknownOrWeakLabelsGoToArt() {
        assertEquals(ContentNames.Result(ContentNames.Group.ART, "Obra"), ContentNames.classify(emptyList()))
        assertEquals(ContentNames.Group.ART, ContentNames.classify(listOf("Mountain" to 0.2f, "Xyz" to 0.9f)).group)
    }

    @Test
    fun aFaceMakesItAPortraitNeverABird() {
        val r = ContentNames.classify(listOf("Bird" to 0.7f, "Sky" to 0.8f, "Sea" to 0.6f), faceArea = 0.05f)
        assertEquals(ContentNames.Group.PEOPLE, r.group)
        assertEquals("Retrato y cielo", r.name)
        // Sin cara, un «pájaro» dudoso tampoco manda.
        assertEquals(ContentNames.Group.SKY, ContentNames.classify(listOf("Bird" to 0.7f, "Sky" to 0.8f)).group)
        assertEquals(ContentNames.Group.ANIMALS, ContentNames.classify(listOf("Bird" to 0.95f, "Sky" to 0.6f)).group)
    }

    @Test
    fun uniqueNames() {
        assertEquals("Perro", ContentNames.unique("Perro", listOf("Gato")))
        assertEquals("Perro 3", ContentNames.unique("Perro", listOf("Perro", "Perro 2")))
    }

    @Test
    fun visualHashSpotsTheSamePhoto() {
        val a = IntArray(72) { (it * 37) % 255 }
        val b = IntArray(72) { ((it * 37) % 255 + 3).coerceAtMost(255) } // misma foto, un poco más clara
        val c = IntArray(72) { 255 - (it * 37) % 255 }
        assertTrue(ContentNames.sameImage(ContentNames.dHash(a), ContentNames.dHash(b)))
        assertFalse(ContentNames.sameImage(ContentNames.dHash(a), ContentNames.dHash(c)))
    }

    @Test
    fun sameShapeButOtherColorsIsNotTheSamePhoto() {
        val gray = IntArray(72) { (it * 37) % 255 }
        val blue = IntArray(16) { 0x3A6EA5 }
        val orange = IntArray(16) { 0xE07A2E }
        val blueish = IntArray(16) { 0x3C70A2 }
        val a = ContentNames.fingerprint(gray, blue)
        assertFalse(ContentNames.same(a, ContentNames.fingerprint(gray, orange)))
        assertTrue(ContentNames.same(a, ContentNames.fingerprint(gray, blueish)))
        assertTrue(ContentNames.same(a, a.take(16))) // huella vieja: solo forma
    }
}
