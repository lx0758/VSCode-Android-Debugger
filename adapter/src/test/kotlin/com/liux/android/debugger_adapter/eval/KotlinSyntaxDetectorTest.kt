package com.liux.android.debugger_adapter.eval

import com.liux.android.debugger_adapter.eval.KotlinSyntaxDetector.Feature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KotlinSyntaxDetector] — which Kotlin-only shapes an expression uses. */
class KotlinSyntaxDetectorTest {

    private fun detect(expr: String): Set<Feature> = KotlinSyntaxDetector.detect(expr)

    // ---------- operator shapes ----------

    @Test fun `safe call is detected`() {
        assertTrue(Feature.SAFE_CALL in detect("a?.b"))
    }

    @Test fun `not-null assertion is detected`() {
        assertTrue(Feature.NOT_NULL in detect("a!!"))
    }

    @Test fun `elvis is detected`() {
        assertTrue(Feature.ELVIS in detect("a ?: b"))
    }

    @Test fun `as cast is detected`() {
        assertTrue(Feature.AS_CAST in detect("a as String"))
    }

    @Test fun `safe as cast is detected`() {
        assertTrue(Feature.AS_CAST in detect("a as? String"))
    }

    @Test fun `is check is detected`() {
        assertTrue(Feature.IS_CHECK in detect("a is String"))
    }

    // ---------- Java expressions have no Kotlin features ----------

    @Test fun `plain java expression has no kotlin features`() {
        assertEquals(emptySet<Feature>(), detect("foo.bar()"))
    }

    @Test fun `java cast has no kotlin features`() {
        assertEquals(emptySet<Feature>(), detect("(String) obj"))
    }

    @Test fun `java instanceof has no kotlin features`() {
        assertEquals(emptySet<Feature>(), detect("obj instanceof String"))
    }

    @Test fun `java ternary has no kotlin features`() {
        assertEquals(emptySet<Feature>(), detect("a ? b : c"))
    }

    // ---------- `as` / `is` in identifier position ----------

    @Test fun `as at the start of an expression is a plain identifier`() {
        assertFalse(Feature.AS_CAST in detect("as"))
    }

    @Test fun `is at the start of an expression is a plain identifier`() {
        assertFalse(Feature.IS_CHECK in detect("is"))
    }

    @Test fun `as without a type after it is not a cast`() {
        assertFalse(Feature.AS_CAST in detect("a as"))
    }

    @Test fun `is without a type after it is not a type check`() {
        assertFalse(Feature.IS_CHECK in detect("a is"))
    }

    @Test fun `a field named is is not a type check`() {
        assertFalse(Feature.IS_CHECK in detect("view.is"))
    }

    @Test fun `a field named as is not a cast`() {
        assertFalse(Feature.AS_CAST in detect("view.as"))
    }

    @Test fun `a method named is is not a type check`() {
        assertFalse(Feature.IS_CHECK in detect("view.is()"))
    }

    @Test fun `a variable named as is not a cast`() {
        assertFalse(Feature.AS_CAST in detect("as + 1"))
    }

    // ---------- literals are skipped ----------

    @Test fun `question mark inside a string is not an operator`() {
        assertEquals(emptySet<Feature>(), detect("\"a?.b\""))
    }

    @Test fun `as inside a string is not a cast`() {
        assertEquals(emptySet<Feature>(), detect("\"x as String\""))
    }

    @Test fun `safe call inside a string is not a safe call`() {
        assertEquals(emptySet<Feature>(), detect("\"a!!b\""))
    }

    // ---------- string templates ----------

    @Test fun `template with a simple variable is detected`() {
        assertTrue(Feature.TEMPLATE in detect("\"count=\$count\""))
    }

    @Test fun `template with an expression is detected`() {
        assertTrue(Feature.TEMPLATE in detect("\"x=\${a.b}\""))
    }

    @Test fun `plain string has no template feature`() {
        assertFalse(Feature.TEMPLATE in detect("\"plain\""))
    }
}
