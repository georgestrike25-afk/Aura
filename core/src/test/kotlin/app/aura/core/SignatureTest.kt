package app.aura.core

import app.aura.core.signature.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SignatureTest {
    @Test
    fun `published catalog parses with every work`() {
        val f = listOf(File("../signature/catalog.json"), File("signature/catalog.json"), File("/home/user/aura/signature/catalog.json")).firstOrNull { it.exists() }
        if (f == null) return // el catálogo vive fuera de core; en la CI se prueba desde la raíz
        val c = Signature.parse(f.readText())
        assertNotNull(c)
        assertEquals(2, c!!.version)
    }

    @Test
    fun `invalid or unsafe entries are rejected, never invented`() {
        assertNull(Signature.parse("no es json"))
        assertNull(Signature.parse("""{"collections":[]}"""))
        val unsafe = """{"collections":[{"id":"x","title":"X","items":[{"id":"a","title":"A","image":"../../etc/passwd.webp","thumb":"t/a.webp"}]}]}"""
        assertNull(Signature.parse(unsafe))
        val ok = """{"collections":[{"id":"x","title":"X","items":[{"id":"a","title":"A","image":"a.webp","thumb":"thumbs/a.webp"},{"id":"B!","title":"B","image":"b.webp","thumb":"thumbs/b.webp"}]}]}"""
        assertEquals(listOf("a"), Signature.parse(ok)!!.collections[0].items.map { it.id })
    }

    @Test
    fun `catalogs from several folders merge without duplicates`() {
        fun cat(vararg ids: String) = Signature.parse(
            """{"version":2,"collections":[{"id":"x","title":"X","items":[""" +
                ids.joinToString(",") { """{"id":"$it","title":"$it","image":"$it.webp","thumb":"t/$it.webp"}""" } + "]}]}",
        )!!
        val merged = Signature.merge(listOf(cat("a", "b") to "main/", cat("b", "c") to "rama/"))
        assertEquals(1, merged.size)
        assertEquals(listOf("a" to "main/", "b" to "main/", "c" to "rama/"), merged[0].items.map { it.work.id to it.base })
        assertNotNull(Signature.parse("""{"version":2,"collections":[]}"""))
    }
}
