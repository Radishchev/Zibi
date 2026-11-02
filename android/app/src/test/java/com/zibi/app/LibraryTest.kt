package com.zibi.app

import com.zibi.app.data.LibraryDoc
import com.zibi.app.library.httpBase
import com.zibi.app.tools.findDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun doc(id: Long, name: String) = LibraryDoc(
    id = id, name = name, localPath = "/x/$id.pdf", serverDocId = id, pages = 1, chunks = 1,
    status = "ready", error = null, addedAt = 0,
)

class LibraryTest {
    @Test
    fun websocketUrlBecomesTheRestBase() {
        assertEquals("http://192.168.1.5:8000", httpBase("ws://192.168.1.5:8000/ws"))
        assertEquals("http://10.0.2.2:8000", httpBase("ws://10.0.2.2:8000/ws?token=abc"))
        assertEquals("https://zibi.example.com", httpBase("wss://zibi.example.com/ws/"))
    }

    @Test
    fun documentNamesMatchLoosely() {
        val docs = listOf(doc(1, "Data Science Notes.pdf"), doc(2, "ML_slides_week3.pdf"))
        assertEquals(1L, findDocument("Data Science Notes.pdf", docs)?.id)
        assertEquals(1L, findDocument("data science notes", docs)?.id)
        assertEquals(2L, findDocument("ml slides week3", docs)?.id)
        assertEquals(2L, findDocument("slides", docs)?.id)
        assertNull(findDocument("chemistry", docs))
        assertNull(findDocument("   ", docs))
    }
}
