package com.liux.android.debugger_adapter.eval

import com.liux.android.debugger_adapter.source.FileEntry
import com.liux.android.debugger_adapter.source.SourceIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun `index hit on a java file`() {
        assertEquals(Language.JAVA, detect(path = "/src/Main.java"))
    }

    @Test
    fun `index hit on a kotlin file`() {
        assertEquals(Language.KOTLIN, detect(path = "/src/Main.kt"))
    }

    @Test
    fun `only source name is recognizable`() {
        val index = SourceIndex() // no entry for the frame's class
        assertEquals(
            Language.KOTLIN,
            LanguageDetector.detectLanguage("com.other.Unknown", "Foo.kt", index)
        )
        assertEquals(
            Language.JAVA,
            LanguageDetector.detectLanguage("com.other.Unknown", "Foo.java", index)
        )
    }

    @Test
    fun `completely unknown input defaults to java`() {
        val index = SourceIndex()
        assertEquals(
            Language.JAVA,
            LanguageDetector.detectLanguage("com.other.Unknown", null, index)
        )
        assertEquals(
            Language.JAVA,
            LanguageDetector.detectLanguage("com.other.Unknown", "Unknown", index)
        )
    }

    private fun detect(path: String): Language {
        val index = SourceIndex()
        index.add(FileEntry(path = path, packageName = "com.main", fqns = listOf("com.main.Main")))
        return LanguageDetector.detectLanguage("com.main.Main", null, index)
    }
}
