package com.liux.android.debugger_adapter.eval

import com.liux.android.debugger_adapter.eval.common.ExprNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [FrontendSelector] — which frontend parses an expression, and what
 * happens when the chosen one rejects the syntax.
 */
class FrontendSelectorTest {

    // ---------- selection ----------

    @Test fun `java expression selects the java frontend`() {
        assertEquals(Frontend.JAVA, FrontendSelector.select("foo.bar()", Language.JAVA))
    }

    @Test fun `kotlin syntax selects the kotlin frontend`() {
        assertEquals(Frontend.KOTLIN, FrontendSelector.select("a?.b", Language.JAVA))
    }

    @Test fun `kotlin syntax in a kotlin frame selects the kotlin frontend`() {
        assertEquals(Frontend.KOTLIN, FrontendSelector.select("a as String", Language.KOTLIN))
    }

    @Test fun `java syntax in a kotlin frame still selects the java frontend`() {
        // The choice follows the syntax, not the language of the frame —
        // otherwise Kotlin frames could not use Java-style casts.
        assertEquals(Frontend.JAVA, FrontendSelector.select("(String) obj", Language.KOTLIN))
    }

    @Test fun `a template only counts as kotlin syntax in a kotlin frame`() {
        assertEquals(Frontend.JAVA, FrontendSelector.select("\"count=\$count\"", Language.JAVA))
        assertEquals(Frontend.KOTLIN, FrontendSelector.select("\"count=\$count\"", Language.KOTLIN))
    }

    // ---------- parsing ----------

    @Test fun `java expression parses with the java frontend`() {
        val node = FrontendSelector.parse("foo.bar()", Language.JAVA)
        assertTrue(node is ExprNode.Call)
    }

    @Test fun `kotlin expression parses with the kotlin frontend`() {
        val node = FrontendSelector.parse("a?.b", Language.JAVA)
        assertEquals(ExprNode.SafeCall(ExprNode.Var("a"), "b", null), node)
    }

    @Test fun `a kotlin cast in a kotlin frame parses`() {
        val node = FrontendSelector.parse("a as String", Language.KOTLIN)
        assertEquals(ExprNode.Cast(ExprNode.Var("a"), "String"), node)
    }

    @Test fun `a java cast in a kotlin frame parses`() {
        val node = FrontendSelector.parse("(String) obj", Language.KOTLIN)
        assertEquals(ExprNode.Cast(ExprNode.Var("obj"), "String"), node)
    }

    // ---------- fallback ----------

    @Test fun `a syntax the primary frontend rejects falls back to the other one`() {
        // JDT rejects `final` as a reserved word, the Kotlin frontend
        // treats it as an ordinary identifier — so the fallback finds a
        // parse where the primary one found none.
        val node = FrontendSelector.parse("x.final", Language.JAVA)
        assertEquals(ExprNode.Field(ExprNode.Var("x"), "final"), node)
    }

    @Test fun `when both frontends fail the primary error is reported`() {
        try {
            FrontendSelector.parse("a ??? b", Language.JAVA)
            fail("应当抛出解析异常")
        } catch (e: IllegalArgumentException) {
            assertTrue("错误信息应描述表达式: ${e.message}", e.message!!.contains("a ??? b"))
        }
    }

    @Test fun `the secondary error is attached to the primary one`() {
        try {
            FrontendSelector.parse("a ??? b", Language.JAVA)
            fail("应当抛出解析异常")
        } catch (e: IllegalArgumentException) {
            assertTrue("次要错误应作为 suppressed 附加", e.suppressed.isNotEmpty())
        }
    }
}
