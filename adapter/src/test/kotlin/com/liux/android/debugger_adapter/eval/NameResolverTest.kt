package com.liux.android.debugger_adapter.eval

import com.liux.android.debugger_adapter.source.FileEntry
import com.liux.android.debugger_adapter.source.SourceIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NameResolverTest {

    private val framePath = "/src/com/main/Main.java"

    /** SourceIndex entry for the evaluation frame's own file. */
    private fun buildSourceIndex(): SourceIndex {
        val index = SourceIndex()
        index.add(
            FileEntry(
                path = framePath,
                packageName = "com.main",
                fqns = listOf("com.main.Main"),
                imports = listOf(
                    "java.util.Objects", // single-type
                    "com.main.Helper",   // single-type (same-package duplicate, higher precedence anyway)
                    "java.a.*",          // on-demand
                    "java.b.*",          // on-demand
                ),
            )
        )
        return index
    }

    private fun resolver(
        index: SourceIndex = buildSourceIndex(),
        loaded: Set<String>,
    ): NameResolver {
        val entry: FileEntry = index.resolveByFqn("com.main.Main")!!
        return NameResolver(
            declaringFqn = "com.main.Main",
            frameEntry = entry,
            sourceIndex = index,
            existsInVm = { it in loaded },
        )
    }

    @Test
    fun `same-file type has highest precedence`() {
        val r = resolver(loaded = setOf("com.main.Main", "java.lang.Main"))
        assertEquals("com.main.Main", r.resolveFqn("Main"))
    }

    @Test
    fun `single-type import beats same package`() {
        val r = resolver(loaded = setOf("java.util.Objects", "com.main.Objects"))
        assertEquals("java.util.Objects", r.resolveFqn("Objects"))
    }

    @Test
    fun `same package resolves without import`() {
        val r = resolver(loaded = setOf("com.main.Helper"))
        assertEquals("com.main.Helper", r.resolveFqn("Helper"))
    }

    @Test
    fun `java-lang resolves without import`() {
        val r = resolver(loaded = setOf("java.lang.Math"))
        assertEquals("java.lang.Math", r.resolveFqn("Math"))
    }

    @Test
    fun `on-demand import is the last resort`() {
        val r = resolver(loaded = setOf("java.a.Thing"))
        assertEquals("java.a.Thing", r.resolveFqn("Thing"))
    }

    @Test
    fun `single-type import beats on-demand`() {
        val r = resolver(
            loaded = setOf("java.util.Objects", "java.a.Objects", "java.b.Objects")
        )
        assertEquals("java.util.Objects", r.resolveFqn("Objects"))
    }

    @Test
    fun `two on-demand imports yielding loaded classes is ambiguous`() {
        val r = resolver(loaded = setOf("java.a.Config", "java.b.Config"))
        try {
            r.resolveFqn("Config")
            fail("expected ambiguity error")
        } catch (e: IllegalArgumentException) {
            val msg = e.message ?: ""
            assertTrue(msg.contains("java.a.Config"))
            assertTrue(msg.contains("java.b.Config"))
        }
    }

    @Test
    fun `unresolvable name returns null`() {
        assertNull(resolver(loaded = emptySet()).resolveFqn("Nothing"))
    }

    @Test
    fun `no frame entry degrades to package plus java-lang`() {
        val index = SourceIndex()
        val r = NameResolver(
            declaringFqn = "com.other.Foo",
            frameEntry = null,
            sourceIndex = index,
            existsInVm = { it in setOf("com.other.Bar", "java.lang.Math") },
        )
        assertEquals("com.other.Bar", r.resolveFqn("Bar"))
        assertEquals("java.lang.Math", r.resolveFqn("Math"))
        assertNull(r.resolveFqn("Objects"))
    }
}
