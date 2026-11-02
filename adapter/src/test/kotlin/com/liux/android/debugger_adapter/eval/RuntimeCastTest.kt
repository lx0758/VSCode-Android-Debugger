package com.liux.android.debugger_adapter.eval

import com.liux.android.debugger_adapter.eval.common.ExprNode
import com.sun.jdi.ClassType
import com.sun.jdi.LocalVariable
import com.sun.jdi.Location
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Runtime semantics of the expression nodes that P0/P2 changed: casts
 * (including the Kotlin safe cast and the `as`/`is` type names) and the
 * elvis operator.
 *
 * The target VM is a set of dynamic proxies — only `name()`,
 * `superclass()` and `allInterfaces()` are needed to answer the type
 * questions the evaluator asks.
 */
class RuntimeCastTest {

    // ---------- fake VM ----------

    /** Loaded classes, by their binary name. */
    private val loadedTypes = mutableSetOf(
        "java.lang.String",
        "java.lang.Integer",
        "java.lang.Object",
    )

    private fun jdiProxy(vararg interfaces: Class<*>, handler: (String, Array<Any?>) -> Any?): Any {
        val wrapped: (String, Array<Any?>) -> Any? = { name, args ->
            when (name) {
                "hashCode" -> 42
                "equals" -> args.firstOrNull() === this
                "toString" -> "jdi-proxy"
                else -> handler(name, args)
            }
        }
        return Proxy.newProxyInstance(
            javaClass.classLoader, interfaces, InvocationHandler { _, m, args ->
                wrapped(m.name, args ?: emptyArray())
            }
        )
    }

    private fun classTypeOf(name: String, superName: String?): ClassType =
        jdiProxy(ClassType::class.java) { op, _ ->
            when (op) {
                "name" -> name
                "superclass" -> superName?.let { classTypeOf(it, null) }
                "allInterfaces" -> emptyList<ClassType>()
                else -> throw UnsupportedOperationException(op)
            }
        } as ClassType

    private fun vmProxy(): VirtualMachine = jdiProxy(VirtualMachine::class.java) { op, args ->
        when (op) {
            "classesByName" -> {
                val fqn = args[0] as String
                if (fqn in loadedTypes) listOf(classTypeOf(fqn, "java.lang.Object")) else emptyList()
            }
            else -> throw UnsupportedOperationException(op)
        }
    } as VirtualMachine

    private fun valueOf(typeName: String): Value = jdiProxy(Value::class.java) { op, _ ->
        when (op) {
            "type" -> jdiProxy(com.sun.jdi.Type::class.java) { op2, _ ->
                if (op2 == "name") typeName else throw UnsupportedOperationException(op2)
            }
            else -> throw UnsupportedOperationException(op)
        }
    } as Value

    /** A frame with one local variable named [localName] of type [localType]. */
    private fun frameWith(localName: String, localType: String): StackFrame {
        val vm = vmProxy()
        val local = jdiProxy(LocalVariable::class.java) { op, _ ->
            when (op) {
                "name" -> localName
                "type" -> jdiProxy(com.sun.jdi.Type::class.java) { op2, _ ->
                    if (op2 == "name") localType else throw UnsupportedOperationException(op2)
                }
                else -> throw UnsupportedOperationException(op)
            }
        } as LocalVariable
        return jdiProxy(StackFrame::class.java) { op, _ ->
            when (op) {
                "virtualMachine" -> vm
                "thread" -> jdiProxy(ThreadReference::class.java) { _, _ -> throw UnsupportedOperationException() }
                "location" -> jdiProxy(Location::class.java) { op2, _ ->
                    when (op2) {
                        "declaringType" -> jdiProxy(ReferenceType::class.java) { op3, _ ->
                            if (op3 == "name") "com.test.Fake" else throw UnsupportedOperationException(op3)
                        }
                        else -> throw UnsupportedOperationException(op2)
                    }
                }
                "visibleVariables" -> listOf(local)
                "getValue" -> valueOf(localType)
                else -> throw UnsupportedOperationException(op)
            }
        } as StackFrame
    }

    private fun evaluator(localName: String, localType: String): RuntimeEvaluator =
        RuntimeEvaluator(frameWith(localName, localType))

    // ---------- casts ----------

    @Test fun `a cast to a short name resolves the type`() {
        // The P0 bug: `(String) x` was compared literally against
        // `java.lang.String` and always failed.
        val evaluator = evaluator("s", "java.lang.String")
        val result = evaluator.eval(ExprNode.Cast(ExprNode.Var("s"), "String"))
        assertEquals("java.lang.String", result?.type()?.name())
    }

    @Test fun `a cast to a fully qualified name works too`() {
        val evaluator = evaluator("s", "java.lang.String")
        val result = evaluator.eval(ExprNode.Cast(ExprNode.Var("s"), "java.lang.String"))
        assertEquals("java.lang.String", result?.type()?.name())
    }

    @Test fun `a cast to an unrelated type fails`() {
        val evaluator = evaluator("s", "java.lang.String")
        try {
            evaluator.eval(ExprNode.Cast(ExprNode.Var("s"), "Integer"))
            fail("应当抛出 ClassCastException")
        } catch (e: IllegalArgumentException) {
            assertTrue("错误信息应包含类型: ${e.message}", e.message!!.contains("java.lang.String"))
            assertTrue("错误信息应包含目标类型: ${e.message}", e.message!!.contains("java.lang.Integer"))
        }
    }

    @Test fun `the safe cast yields null instead of throwing`() {
        val evaluator = evaluator("s", "java.lang.String")
        assertNull(evaluator.eval(ExprNode.Cast(ExprNode.Var("s"), "Integer", safe = true)))
    }

    @Test fun `the safe cast keeps a matching value`() {
        val evaluator = evaluator("s", "java.lang.String")
        val result = evaluator.eval(ExprNode.Cast(ExprNode.Var("s"), "String", safe = true))
        assertEquals("java.lang.String", result?.type()?.name())
    }

    @Test fun `a cast to an unknown class is reported as such`() {
        val evaluator = evaluator("s", "java.lang.String")
        try {
            evaluator.eval(ExprNode.Cast(ExprNode.Var("s"), "NoSuchClass"))
            fail("应当报告找不到类")
        } catch (e: IllegalArgumentException) {
            assertTrue("错误信息应说明类不存在: ${e.message}", e.message!!.contains("找不到类"))
        }
    }

    @Test fun `a cast to a supertype succeeds`() {
        val evaluator = evaluator("s", "java.lang.String")
        val result = evaluator.eval(ExprNode.Cast(ExprNode.Var("s"), "Object"))
        assertEquals("java.lang.String", result?.type()?.name())
    }

    // ---------- elvis ----------

    @Test fun `elvis returns the right side when the left is null`() {
        val evaluator = evaluator("s", "java.lang.String")
        val result = evaluator.eval(ExprNode.Elvis(ExprNode.NullLit, ExprNode.Var("s")))
        assertEquals("java.lang.String", result?.type()?.name())
    }

    @Test fun `elvis returns the left side when it is not null`() {
        val evaluator = evaluator("s", "java.lang.String")
        val result = evaluator.eval(ExprNode.Elvis(ExprNode.Var("s"), ExprNode.NullLit))
        assertEquals("java.lang.String", result?.type()?.name())
    }
}
