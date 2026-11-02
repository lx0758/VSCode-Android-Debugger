package com.liux.android.debugger_adapter.source

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceIndexerTest {

    @Test
    fun `parseEntries reads imports when the payload carries them`() {
        val json = JsonParser.parseString(
            """
            [
              {
                "fqn": "com.example.Foo",
                "file": "/home/liux/Foo.java",
                "sourceUri": "vscode-remote://ssh-remote%2Bhost/home/liux/Foo.java",
                "imports": ["java.util.List", "java.util.*"]
              }
            ]
            """.trimIndent()
        ).asJsonArray

        val parsed = SourceIndexer.parseEntries(json)

        assertEquals(1, parsed.size)
        assertEquals("com.example.Foo", parsed[0].fqn)
        assertEquals("/home/liux/Foo.java", parsed[0].file)
        assertEquals("vscode-remote://ssh-remote%2Bhost/home/liux/Foo.java", parsed[0].sourceUri)
        assertEquals(listOf("java.util.List", "java.util.*"), parsed[0].imports)

        val indexer = SourceIndexer()
        indexer.add(parsed)
        val entry = indexer.sourceIndex().entries().single()
        assertEquals(listOf("java.util.List", "java.util.*"), entry.imports)
    }

    @Test
    fun `parseEntries falls back to empty imports for legacy payloads`() {
        val json = JsonParser.parseString(
            """
            [
              { "fqn": "com.example.Bar", "file": "/home/liux/Bar.kt" },
              { "fqn": "com.example.Baz", "file": "/home/liux/Baz.kt", "sourceUri": null, "imports": null }
            ]
            """.trimIndent()
        ).asJsonArray

        val parsed = SourceIndexer.parseEntries(json)

        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.imports.isEmpty() })
        assertEquals(null, parsed[0].sourceUri)
        assertEquals(null, parsed[1].sourceUri)

        val indexer = SourceIndexer()
        indexer.set(parsed)
        assertEquals(2, indexer.sourceIndex().size())
        assertTrue(indexer.sourceIndex().entries().all { it.imports.isEmpty() })
    }
}
