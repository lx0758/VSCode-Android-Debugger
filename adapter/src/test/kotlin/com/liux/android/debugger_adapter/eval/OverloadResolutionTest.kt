package com.liux.android.debugger_adapter.eval

import com.sun.jdi.Method
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference
import com.sun.jdi.Type
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Overload resolution matrix ([RuntimeEvaluator.findMatchingMethod]) against
 * dynamic-proxy JDI fakes — no real VM needed. The evaluator only reads
 * `name()` / `argumentTypes()` / `isStatic()` / `isSynthetic()` / `isBridge()`
 * during resolution.
 */
class OverloadResolutionTest {

    // ---------- proxy helpers ----------

    private fun proxy(handler: (String, Array<Any?>) -> Any?): Any {
        val h = InvocationHandler { _, m, args ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(m)
                "equals" -> m === (args?.firstOrNull())
                "toString" -> "proxy"
                else -> handler(m.name, args ?: emptyArray())
            }
        }
        return Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(Type::class.java), h
        )
    }

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

    private fun typeOf(name: String): Type =
        jdiProxy(Type::class.java) { op, _ ->
            if (op == "name") name else throw UnsupportedOperationException(op)
        } as Type

    private fun valueOf(typeName: String?): Value? =
        if (typeName == null) null
        else jdiProxy(Value::class.java) { op, _ ->
            if (op == "type") typeOf(typeName) else throw UnsupportedOperationException(op)
        } as Value

    private fun methodOf(
        name: String,
        formalNames: List<String>,
        isStatic: Boolean = false,
        isSynthetic: Boolean = false,
        isBridge: Boolean = false,
    ): Method = jdiProxy(Method::class.java) { op, _ ->
        when (op) {
            "name" -> name
            "argumentTypes" -> formalNames.map { typeOf(it) }
            "isStatic" -> isStatic
            "isSynthetic" -> isSynthetic
            "isBridge" -> isBridge
            else -> throw UnsupportedOperationException(op)
        }
    } as Method

    private fun refTypeOf(name: String, methods: List<Method>): ReferenceType =
        jdiProxy(ReferenceType::class.java) { op, _ ->
            when (op) {
                "name" -> name
                "allMethods" -> methods
                else -> throw UnsupportedOperationException(op)
            }
        } as ReferenceType

    private fun frameProxy(): StackFrame = jdiProxy(StackFrame::class.java) { op, _ ->
        when (op) {
            "virtualMachine" -> jdiProxy(VirtualMachine::class.java) { _, _ -> throw UnsupportedOperationException() }
            "thread" -> jdiProxy(ThreadReference::class.java) { _, _ -> throw UnsupportedOperationException() }
            "location" -> jdiProxy(com.sun.jdi.Location::class.java) { op2, _ ->
                when (op2) {
                    "declaringType" -> refTypeOf("com.test.Fake", emptyList())
                    else -> throw UnsupportedOperationException(op2)
                }
            }
            else -> throw UnsupportedOperationException(op)
        }
    } as StackFrame

    private fun resolve(
        methods: List<Method>,
        actualTypes: List<String?>,
        staticOnly: Boolean = false,
    ): Method? {
        val evaluator = RuntimeEvaluator(frameProxy())
        return evaluator.findMatchingMethod(
            refTypeOf("com.test.Fake", methods),
            "f",
            actualTypes.map { valueOf(it) },
            staticOnly,
        )
    }

    // ---------- phases ----------

    @Test fun `exact match beats widening`() {
        val picked = resolve(
            methods = listOf(methodOf("f", listOf("long")), methodOf("f", listOf("int"))),
            actualTypes = listOf("int"),
        )
        assertEquals("int", picked?.argumentTypes()?.first()?.name())
    }

    @Test fun `widening applies when no exact match`() {
        val picked = resolve(
            methods = listOf(methodOf("f", listOf("long")), methodOf("f", listOf("double"))),
            actualTypes = listOf("int"),
        )
        // first-match-wins within the widening phase
        assertEquals("long", picked?.argumentTypes()?.first()?.name())
    }

    @Test fun `boxing phase converts primitive actual to wrapper formal`() {
        val picked = resolve(
            methods = listOf(methodOf("f", listOf("java.lang.Object"))),
            actualTypes = listOf("int"),
        )
        assertEquals("java.lang.Object", picked?.argumentTypes()?.first()?.name())
    }

    @Test fun `unboxing phase converts boxed actual to primitive formal`() {
        val picked = resolve(
            methods = listOf(methodOf("f", listOf("int"))),
            actualTypes = listOf("java.lang.Integer"),
        )
        assertEquals("int", picked?.argumentTypes()?.first()?.name())
    }

    @Test fun `synthetic and bridge methods are filtered out`() {
        val picked = resolve(
            methods = listOf(
                methodOf("f", listOf("int"), isSynthetic = true),
                methodOf("f", listOf("int"), isBridge = true),
                methodOf("f", listOf("int")),
            ),
            actualTypes = listOf("int"),
        )
        assertEquals(false, picked?.isSynthetic())
        assertEquals(false, picked?.isBridge())
    }

    @Test fun `two boxing-applicable candidates raise ambiguity listing signatures`() {
        try {
            resolve(
                methods = listOf(
                    methodOf("f", listOf("int", "java.lang.Object")),
                    methodOf("f", listOf("java.lang.Object", "int")),
                ),
                actualTypes = listOf("int", "int"),
            )
            fail("expected ambiguity error")
        } catch (e: IllegalArgumentException) {
            val msg = e.message ?: ""
            assertTrue(msg.contains("int, Object"))
            assertTrue(msg.contains("Object, int"))
        }
    }

    @Test fun `staticOnly filter excludes instance methods`() {
        val picked = resolve(
            methods = listOf(
                methodOf("f", listOf("int"), isStatic = false),
                methodOf("f", listOf("long"), isStatic = true),
            ),
            actualTypes = listOf("int"),
            staticOnly = true,
        )
        assertEquals(true, picked?.isStatic())
    }

    @Test fun `null actual matches reference formals only`() {
        val picked = resolve(
            methods = listOf(methodOf("f", listOf("java.lang.Object")), methodOf("f", listOf("int"))),
            actualTypes = listOf(null),
        )
        assertEquals("java.lang.Object", picked?.argumentTypes()?.first()?.name())
    }
}
