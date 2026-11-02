package com.liux.android.debugger_adapter.eval

import com.sun.jdi.ReferenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * [TypeResolver] behaviour against a fake "loaded classes" table — the VM
 * is injected as a plain lookup function, so no real JDI is needed.
 *
 * The table below models a VM that has `java.util.Objects`,
 * `com.example.Outer` (with the nested class `Outer$Inner`) and
 * `com.example.Helper` loaded.
 */
class TypeResolutionTest {

    // ---------- fake VM ----------

    /** Types that are "loaded" in the fake VM, by their binary name. */
    private val loadedTypes = setOf(
        "java.lang.String",
        "java.lang.Integer",
        "java.util.Objects",
        "com.example.Helper",
        "com.example.Outer",
        "com.example.Outer\$Inner",
    )

    private fun fakeReferenceType(name: String): ReferenceType {
        val handler = InvocationHandler { _, m, args ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(m)
                "equals" -> m === args?.firstOrNull()
                "toString" -> name
                "name" -> name
                else -> throw UnsupportedOperationException(m.name)
            }
        }
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(ReferenceType::class.java),
            handler,
        ) as ReferenceType
    }

    /** Short names the "source file" knows about. */
    private val shortNames = mapOf(
        "Objects" to "java.util.Objects",
        "Helper" to "com.example.Helper",
        "Outer" to "com.example.Outer",
        "String" to "java.lang.String",
    )

    private fun resolver(): TypeResolver = TypeResolver(
        classesByName = { name ->
            if (name in loadedTypes) listOf(fakeReferenceType(name)) else emptyList()
        },
        shortNameResolver = { name -> shortNames[name] },
    )

    // ---------- dotted names ----------

    @Test fun `fully qualified name resolves directly`() {
        assertEquals("java.util.Objects", resolver().resolveFqn("java.util.Objects"))
    }

    @Test fun `unknown fully qualified name resolves to null`() {
        assertNull(resolver().resolveFqn("java.util.Collections"))
    }

    @Test fun `nested class resolves via binary name`() {
        assertEquals("com.example.Outer\$Inner", resolver().resolveFqn("com.example.Outer.Inner"))
    }

    @Test fun `nested class with short outer name resolves`() {
        // `Outer.Inner` where `Outer` itself is only known by short name.
        assertEquals("com.example.Outer\$Inner", resolver().resolveFqn("Outer.Inner"))
    }

    @Test fun `dotted name with unknown head resolves to null`() {
        assertNull(resolver().resolveFqn("Nope.Inner"))
    }

    // ---------- short names ----------

    @Test fun `short name resolves through the short-name chain`() {
        assertEquals("java.util.Objects", resolver().resolveFqn("Objects"))
    }

    @Test fun `unknown short name resolves to null`() {
        assertNull(resolver().resolveFqn("Missing"))
    }

    @Test fun `empty name resolves to null`() {
        assertNull(resolver().resolveFqn(""))
    }

    // ---------- reference types ----------

    @Test fun `reference type is looked up by resolved fqn`() {
        val type = resolver().resolveReferenceType("Objects")
        assertEquals("java.util.Objects", type?.name())
    }

    @Test fun `reference type of unknown name is null`() {
        assertNull(resolver().resolveReferenceType("Missing"))
    }

    // ---------- Kotlin primitive aliases ----------

    @Test fun `kotlin primitive aliases map to their boxed types`() {
        val r = resolver()
        assertEquals("java.lang.Integer", r.resolveFqn("Int"))
        assertEquals("java.lang.Long", r.resolveFqn("Long"))
        assertEquals("java.lang.Double", r.resolveFqn("Double"))
        assertEquals("java.lang.Boolean", r.resolveFqn("Boolean"))
    }

    @Test fun `a real class named like a kotlin alias wins over the alias`() {
        // The short-name chain is consulted first, so a user type called
        // `Int` is not shadowed by the Kotlin alias.
        val r = TypeResolver(
            classesByName = { name -> if (name == "com.example.Int") listOf(fakeReferenceType(name)) else emptyList() },
            shortNameResolver = { name -> if (name == "Int") "com.example.Int" else null },
        )
        assertEquals("com.example.Int", r.resolveFqn("Int"))
    }
}
