package com.liux.android.debugger_adapter.eval

import com.sun.jdi.ClassType
import com.sun.jdi.ReferenceType

/**
 * Single entry point for turning a type name *as written in an expression*
 * into a type that actually exists in the target VM.
 *
 * Two name shapes have to be supported:
 *
 *  - **Dotted names** (`java.util.Objects`, `com.example.Outer.Inner`) —
 *    resolved directly against the VM's loaded classes. Nested classes are
 *    matched with the JVM's binary spelling (`Outer$Inner`), because that
 *    is what JDI reports and what `vm.classesByName` expects.
 *  - **Short names** (`Objects`, `Helper`) — resolved through
 *    [NameResolver], which follows the Java name-resolution order
 *    (same file → single-type imports → same package → `java.lang` →
 *    on-demand imports) and reports ambiguity explicitly.
 *
 * Everything that needs a type at runtime goes through here: variable
 * lookup that turns out to be a class name, `new X(...)`, casts,
 * `instanceof` and class literals. Keeping the lookup in one place is what
 * makes a cast to a short name (`(String) x`) behave like a cast to a
 * fully-qualified one (`(java.lang.String) x`) — the short name is
 * resolved first, then compared against the runtime type.
 */
internal class TypeResolver(
    /** VM lookup by type name — returns the loaded classes with that name. */
    private val classesByName: (String) -> List<ReferenceType>,
    /** Short-name → FQN resolution (the [NameResolver] chain). */
    private val shortNameResolver: (String) -> String?,
) {

    /**
     * Resolve a type name to its fully-qualified form in the VM, or null
     * when the type is unknown / not loaded.
     */
    fun resolveFqn(name: String): String? {
        if (name.isEmpty()) return null
        if (name.contains('.')) {
            resolveDotted(name)?.let { return it }
            // `Outer.Inner` where `Outer` is only reachable by short name
            // (e.g. `Map.Entry` with `java.util.Map` imported).
            resolveNestedViaShortName(name)?.let { return it }
            return null
        }
        return shortNameResolver(name) ?: kotlinPrimitiveAlias(name)
    }

    /**
     * Kotlin's primitive type aliases (`Int`, `Long`, …) have no class in
     * the VM under that name — they are the JVM primitives `int`, `long`,
     * … and their boxed counterparts. Map them to the boxed type so that
     * `x is Int` / `x as Int` can be resolved.
     *
     * Only used as a *fallback*: a user class actually named `Int` in the
     * evaluated package still wins, because [shortNameResolver] is tried
     * first.
     */
    private fun kotlinPrimitiveAlias(name: String): String? = when (name) {
        "Int" -> "java.lang.Integer"
        "Long" -> "java.lang.Long"
        "Short" -> "java.lang.Short"
        "Byte" -> "java.lang.Byte"
        "Double" -> "java.lang.Double"
        "Float" -> "java.lang.Float"
        "Char" -> "java.lang.Character"
        "Boolean" -> "java.lang.Boolean"
        // Kotlin's `Any` is `java.lang.Object`; it has no class of its own.
        "Any" -> "java.lang.Object"
        else -> null
    }

    /** Resolve to the loaded [ReferenceType] (class *or* interface). */
    fun resolveReferenceType(name: String): ReferenceType? {
        val fqn = resolveFqn(name) ?: return null
        return classesByName(fqn).firstOrNull()
    }

    /** Resolve to the loaded [ClassType] (a concrete class, not an interface). */
    fun resolveClassType(name: String): ClassType? =
        resolveReferenceType(name) as? ClassType

    /**
     * A dotted name may be a plain FQN (`java.util.Objects`) or a nested
     * class written with dots (`com.example.Outer.Inner` → binary name
     * `com.example.Outer$Inner`). Try the plain form first, then replace
     * the dots from the right until a loaded class is found.
     */
    private fun resolveDotted(name: String): String? {
        if (classesByName(name).isNotEmpty()) return name
        var idx = name.lastIndexOf('.')
        while (idx > 0) {
            val candidate = name.substring(0, idx) + "$" + name.substring(idx + 1)
            if (classesByName(candidate).isNotEmpty()) return candidate
            idx = name.lastIndexOf('.', idx - 1)
        }
        return null
    }

    /**
     * `Map.Entry` where `Map` itself is only known by short name: resolve
     * the head via the short-name chain, then look for the nested class.
     */
    private fun resolveNestedViaShortName(name: String): String? {
        val firstDot = name.indexOf('.')
        if (firstDot <= 0) return null
        val head = name.substring(0, firstDot)
        val tail = name.substring(firstDot + 1)
        if (tail.contains('.')) return null
        val headFqn = if (head.contains('.')) resolveDotted(head) else shortNameResolver(head)
            ?: return null
        val nested = "$headFqn\$$tail"
        return if (classesByName(nested).isNotEmpty()) nested else null
    }
}
