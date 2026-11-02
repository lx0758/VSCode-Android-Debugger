package com.liux.android.debugger_adapter.eval

import com.liux.android.debugger_adapter.eval.common.ExprNode
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class JavaFrontendTest {

    private fun parse(expr: String): ExprNode = JavaFrontend.parse(expr)

    // ---------- Literals ----------

    @Test fun `number literal`() {
        assertEquals(ExprNode.NumberLit("42"), parse("42"))
        assertEquals(ExprNode.NumberLit("0x1F"), parse("0x1F"))
        assertEquals(ExprNode.NumberLit("1.5f"), parse("1.5f"))
    }

    @Test fun `string literal keeps raw quoted text`() {
        assertEquals(ExprNode.StringLit("\"hi\""), parse("\"hi\""))
        assertEquals(ExprNode.StringLit("\"a\\nb\""), parse("\"a\\nb\""))
    }

    @Test fun `char literal`() {
        assertEquals(ExprNode.CharLit(97), parse("'a'"))
    }

    @Test fun `null literal`() {
        assertEquals(ExprNode.NullLit, parse("null"))
    }

    @Test fun `boolean literals`() {
        assertEquals(ExprNode.TrueLit, parse("true"))
        assertEquals(ExprNode.FalseLit, parse("false"))
    }

    @Test fun `this reference`() {
        assertEquals(ExprNode.ThisRef, parse("this"))
    }

    // ---------- Names ----------

    @Test fun `simple name becomes Var`() {
        assertEquals(ExprNode.Var("x"), parse("x"))
    }

    @Test fun `lowercase dotted chain is instance field access`() {
        assertEquals(
            ExprNode.Field(ExprNode.Var("obj"), "field"),
            parse("obj.field")
        )
        assertEquals(
            ExprNode.Field(ExprNode.Field(ExprNode.Var("obj"), "field"), "name"),
            parse("obj.field.name")
        )
    }

    @Test fun `package-qualified static field splits at first uppercase segment`() {
        assertEquals(
            ExprNode.Field(ExprNode.Var("java.lang.Math"), "PI"),
            parse("java.lang.Math.PI")
        )
    }

    @Test fun `short class static field`() {
        assertEquals(
            ExprNode.Field(ExprNode.Var("Integer"), "MAX_VALUE"),
            parse("Integer.MAX_VALUE")
        )
    }

    @Test fun `field access on this`() {
        assertEquals(ExprNode.Field(ExprNode.ThisRef, "name"), parse("this.name"))
    }

    // ---------- Method invocation ----------

    @Test fun `implicit this call`() {
        assertEquals(
            ExprNode.Call(ExprNode.ThisRef, "foo", listOf(ExprNode.NumberLit("1"))),
            parse("foo(1)")
        )
    }

    @Test fun `receiver call`() {
        assertEquals(
            ExprNode.Call(ExprNode.Var("x"), "foo", emptyList()),
            parse("x.foo()")
        )
    }

    @Test fun `static call on short class name`() {
        assertEquals(
            ExprNode.Call(
                ExprNode.Var("Math"), "max",
                listOf(ExprNode.NumberLit("1"), ExprNode.NumberLit("2"))
            ),
            parse("Math.max(1, 2)")
        )
    }

    @Test fun `static call on package-qualified class`() {
        assertEquals(
            ExprNode.Call(ExprNode.Var("java.lang.Math"), "max", listOf(ExprNode.NumberLit("1"))),
            parse("java.lang.Math.max(1)")
        )
    }

    @Test fun `chained call`() {
        assertEquals(
            ExprNode.Call(
                ExprNode.Call(ExprNode.ThisRef, "foo", emptyList()), "bar", emptyList()
            ),
            parse("foo().bar()")
        )
    }

    // ---------- new ----------

    @Test fun `new with args`() {
        assertEquals(
            ExprNode.New("Foo", listOf(ExprNode.NumberLit("1"))),
            parse("new Foo(1)")
        )
    }

    @Test fun `new with package-qualified type`() {
        assertEquals(
            ExprNode.New("java.util.ArrayList", emptyList()),
            parse("new java.util.ArrayList()")
        )
    }

    @Test fun `new with generic type strips generics`() {
        assertEquals(
            ExprNode.New("ArrayList", emptyList()),
            parse("new ArrayList<String>()")
        )
    }

    // ---------- Arrays ----------

    @Test fun `single-dim array allocation`() {
        assertEquals(
            ExprNode.ArrayAllocation("int", listOf(ExprNode.NumberLit("5")), null),
            parse("new int[5]")
        )
    }

    @Test fun `multi-dim array allocation`() {
        assertEquals(
            ExprNode.ArrayAllocation(
                "int",
                listOf(ExprNode.NumberLit("2"), ExprNode.NumberLit("3")),
                null
            ),
            parse("new int[2][3]")
        )
    }

    @Test fun `array with initializer`() {
        assertEquals(
            ExprNode.ArrayAllocation(
                "int", emptyList(),
                ExprNode.ArrayInitializer("int", listOf(ExprNode.NumberLit("1"), ExprNode.NumberLit("2")))
            ),
            parse("new int[]{1, 2}")
        )
    }

    @Test fun `nested array initializer threads element types`() {
        assertEquals(
            ExprNode.ArrayAllocation(
                "int", emptyList(),
                ExprNode.ArrayInitializer(
                    "int[]",
                    listOf(
                        ExprNode.ArrayInitializer("int", listOf(ExprNode.NumberLit("1"))),
                        ExprNode.ArrayInitializer("int", listOf(ExprNode.NumberLit("2"))),
                    )
                )
            ),
            parse("new int[][]{{1}, {2}}")
        )
    }

    @Test fun `array element access`() {
        assertEquals(
            ExprNode.Index(ExprNode.Var("arr"), ExprNode.NumberLit("0")),
            parse("arr[0]")
        )
    }

    // ---------- Cast / TypeLiteral / instanceof ----------

    @Test fun `cast expression`() {
        assertEquals(ExprNode.Cast(ExprNode.Var("x"), "Foo"), parse("(Foo) x"))
    }

    @Test fun `cast with generic type strips generics`() {
        assertEquals(
            ExprNode.Cast(ExprNode.Var("x"), "Map"),
            parse("(Map<String, String>) x")
        )
    }

    @Test fun `class literal`() {
        assertEquals(ExprNode.ClassLiteral("String"), parse("String.class"))
    }

    @Test fun `instanceof`() {
        assertEquals(
            ExprNode.InstanceOf(ExprNode.Var("x"), "Foo"),
            parse("x instanceof Foo")
        )
    }

    // ---------- Operators ----------

    @Test fun `infix expression`() {
        assertEquals(
            ExprNode.BinaryOp("+", ExprNode.Var("a"), ExprNode.Var("b")),
            parse("a + b")
        )
    }

    @Test fun `infix chains fold left`() {
        assertEquals(
            ExprNode.BinaryOp(
                "+",
                ExprNode.BinaryOp("+", ExprNode.Var("a"), ExprNode.Var("b")),
                ExprNode.Var("c")
            ),
            parse("a + b + c")
        )
    }

    @Test fun `ternary`() {
        assertEquals(
            ExprNode.Ternary(ExprNode.Var("x"), ExprNode.Var("y"), ExprNode.Var("z")),
            parse("x ? y : z")
        )
    }

    @Test fun `plain assignment`() {
        assertEquals(
            ExprNode.Assign(ExprNode.Var("x"), ExprNode.NumberLit("1")),
            parse("x = 1")
        )
    }

    @Test fun `compound assignment desugars to binary op`() {
        assertEquals(
            ExprNode.Assign(
                ExprNode.Var("x"),
                ExprNode.BinaryOp("+", ExprNode.Var("x"), ExprNode.NumberLit("1"))
            ),
            parse("x += 1")
        )
    }

    @Test fun `prefix operators`() {
        assertEquals(ExprNode.UnaryOp("-", ExprNode.Var("x")), parse("-x"))
        assertEquals(ExprNode.UnaryOp("!", ExprNode.Var("flag")), parse("!flag"))
    }

    @Test fun `increment is rejected`() {
        for (expr in listOf("x++", "++x", "--x")) {
            try {
                parse(expr)
                fail("expected rejection: $expr")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test fun `parentheses are transparent`() {
        assertEquals(ExprNode.Var("a"), parse("(a)"))
        assertEquals(
            ExprNode.Cast(ExprNode.Var("x"), "String"),
            parse("((String) x)")
        )
    }

    // ---------- Unsupported / errors ----------

    @Test fun `super access is rejected`() {
        try {
            parse("super.foo()")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun `syntax error is rejected`() {
        try {
            parse("a ++++ b")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
