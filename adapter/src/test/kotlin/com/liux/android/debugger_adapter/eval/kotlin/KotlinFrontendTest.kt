package com.liux.android.debugger_adapter.eval.kotlin

import com.liux.android.debugger_adapter.eval.common.ExprNode
import org.junit.Assert.assertEquals
import org.junit.Test

class KotlinFrontendTest {

    private fun parse(expr: String): ExprNode = Parser(Lexer(expr).tokenize()).parse()

    @Test fun `safe field access`() {
        assertEquals(
            ExprNode.SafeCall(ExprNode.Var("a"), "b", null),
            parse("a?.b")
        )
    }

    @Test fun `safe call with args`() {
        assertEquals(
            ExprNode.SafeCall(
                ExprNode.Var("a"), "b",
                listOf(ExprNode.NumberLit("1"))
            ),
            parse("a?.b(1)")
        )
    }

    @Test fun `safe access chains with not-null`() {
        assertEquals(
            ExprNode.NotNull(
                ExprNode.SafeCall(ExprNode.Var("user"), "name", null)
            ),
            parse("user?.name!!")
        )
    }

    @Test fun `not-null assertion`() {
        assertEquals(ExprNode.NotNull(ExprNode.Var("a")), parse("a!!"))
    }

    @Test fun `plain not operator is unaffected`() {
        assertEquals(
            ExprNode.UnaryOp("!", ExprNode.Var("flag")),
            parse("!flag")
        )
        assertEquals(
            ExprNode.BinaryOp("!=", ExprNode.Var("a"), ExprNode.Var("b")),
            parse("a != b")
        )
    }

    @Test fun `ternary still uses bare question mark`() {
        assertEquals(
            ExprNode.Ternary(ExprNode.Var("x"), ExprNode.Var("y"), ExprNode.Var("z")),
            parse("x ? y : z")
        )
    }

    @Test fun `string template with expression`() {
        assertEquals(
            ExprNode.StringTemplate(
                listOf("x=", ""),
                listOf(ExprNode.Field(ExprNode.Var("a"), "b"))
            ),
            parse("\"x=\${a.b}\"")
        )
    }

    @Test fun `string template with simple variable interpolation`() {
        assertEquals(
            ExprNode.StringTemplate(listOf("count=", ""), listOf(ExprNode.Var("count"))),
            parse("\"count=\$count\"")
        )
    }

    @Test fun `string without interpolation stays a literal`() {
        assertEquals(ExprNode.StringLit("\"plain\""), parse("\"plain\""))
    }

    @Test fun `string template with trailing text`() {
        assertEquals(
            ExprNode.StringTemplate(
                listOf("a=", " b"),
                listOf(ExprNode.Var("v"))
            ),
            parse("\"a=\$v b\"")
        )
    }

    // ---------- `as` / `as?` ----------

    @Test fun `as cast`() {
        assertEquals(
            ExprNode.Cast(ExprNode.Var("a"), "String", safe = false),
            parse("a as String")
        )
    }

    @Test fun `safe as cast`() {
        assertEquals(
            ExprNode.Cast(ExprNode.Var("a"), "String", safe = true),
            parse("a as? String")
        )
    }

    @Test fun `as cast with a qualified type name`() {
        assertEquals(
            ExprNode.Cast(ExprNode.Var("a"), "java.util.List", safe = false),
            parse("a as java.util.List")
        )
    }

    @Test fun `as cast drops the generic arguments`() {
        assertEquals(
            ExprNode.Cast(ExprNode.Var("a"), "List", safe = false),
            parse("a as List<String>")
        )
    }

    @Test fun `as cast keeps a comparison that follows the type name`() {
        // `Foo < y` is a comparison, not a generic argument list.
        assertEquals(
            ExprNode.BinaryOp(
                "<",
                ExprNode.Cast(ExprNode.Var("a"), "Foo", safe = false),
                ExprNode.Var("y"),
            ),
            parse("a as Foo < y")
        )
    }

    @Test fun `a field named as is not a cast`() {
        assertEquals(
            ExprNode.Field(ExprNode.Var("view"), "as"),
            parse("view.as")
        )
    }

    // ---------- `is` ----------

    @Test fun `is check`() {
        assertEquals(
            ExprNode.InstanceOf(ExprNode.Var("a"), "String"),
            parse("a is String")
        )
    }

    @Test fun `is check with a qualified type name`() {
        assertEquals(
            ExprNode.InstanceOf(ExprNode.Var("a"), "java.util.List"),
            parse("a is java.util.List")
        )
    }

    @Test fun `a field named is is not a type check`() {
        assertEquals(
            ExprNode.Field(ExprNode.Var("view"), "is"),
            parse("view.is")
        )
    }

    // ---------- elvis ----------

    @Test fun `elvis operator`() {
        assertEquals(
            ExprNode.Elvis(ExprNode.Var("a"), ExprNode.Var("b")),
            parse("a ?: b")
        )
    }

    @Test fun `elvis operator is right associative`() {
        assertEquals(
            ExprNode.Elvis(
                ExprNode.Var("a"),
                ExprNode.Elvis(ExprNode.Var("b"), ExprNode.Var("c")),
            ),
            parse("a ?: b ?: c")
        )
    }

    @Test fun `ternary still works next to the elvis operator`() {
        assertEquals(
            ExprNode.Ternary(ExprNode.Var("x"), ExprNode.Var("y"), ExprNode.Var("z")),
            parse("x ? y : z")
        )
    }

    // ---------- precedence ----------

    @Test fun `as binds tighter than multiplication`() {
        // Kotlin's own precedence table: `as` sits above `*`.
        assertEquals(
            ExprNode.BinaryOp(
                "*",
                ExprNode.Cast(ExprNode.Var("a"), "T", safe = false),
                ExprNode.Var("b"),
            ),
            parse("a as T * b")
        )
    }

    @Test fun `elvis binds looser than addition`() {
        assertEquals(
            ExprNode.Elvis(
                ExprNode.BinaryOp("+", ExprNode.Var("a"), ExprNode.Var("b")),
                ExprNode.Var("c"),
            ),
            parse("a + b ?: c")
        )
    }

    @Test fun `is check binds looser than the elvis operator`() {
        assertEquals(
            ExprNode.InstanceOf(
                ExprNode.Elvis(ExprNode.Var("a"), ExprNode.Var("b")),
                "T",
            ),
            parse("a ?: b is T")
        )
    }

    @Test fun `is check binds looser than a comparison`() {
        assertEquals(
            ExprNode.BinaryOp(
                "<",
                ExprNode.InstanceOf(ExprNode.Var("a"), "T"),
                ExprNode.Var("b"),
            ),
            parse("a is T < b")
        )
    }
}
