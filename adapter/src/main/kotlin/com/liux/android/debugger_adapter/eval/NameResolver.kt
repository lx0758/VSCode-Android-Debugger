package com.liux.android.debugger_adapter.eval

import com.liux.android.debugger_adapter.source.FileEntry
import com.liux.android.debugger_adapter.source.SourceIndex
import com.sun.jdi.ClassType
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StackFrame

/**
 * Short-name → FQN resolution for expression evaluation, mirroring the
 * Java name-resolution order. Candidates come from the evaluation frame's
 * source file: types declared in the same file, its single-type imports,
 * its package, `java.lang`, then its on-demand (`*`) imports. The import
 * candidate set rides in on the source-index entry (extracted by the TS
 * indexer — the adapter has no filesystem access).
 *
 * Resolution operates on FQN candidates with an injected [existsInVm]
 * predicate (the target VM is the "live type library", D26); [resolveClass]
 * is the thin JDI wrapper used by the runtime evaluator.
 */
internal class NameResolver(
    private val declaringFqn: String,
    private val frameEntry: FileEntry?,
    private val sourceIndex: SourceIndex?,
    private val existsInVm: (String) -> Boolean,
    private val frameOrNull: StackFrame? = null,
) {
    constructor(frame: StackFrame, sourceIndex: SourceIndex?) : this(
        declaringFqn = frame.location().declaringType().name(),
        frameEntry = sourceIndex?.resolveByFqn(frame.location().declaringType().name()),
        sourceIndex = sourceIndex,
        existsInVm = { fqn -> frame.virtualMachine().classesByName(fqn).isNotEmpty() },
        frameOrNull = frame,
    )

    private val packageName: String =
        frameEntry?.packageName
            ?: declaringFqn.substringBeforeLast('.', "")

    /**
     * Ordered candidate-FQN levels for [shortName]: same-file types,
     * single-type imports, same package, java.lang, on-demand imports.
     * Within a level the Java rule applies — multiple matches are
     * ambiguous; an empty level falls through to the next one.
     */
    fun candidateFqns(shortName: String): List<List<String>> = buildList {
        if (frameEntry != null && sourceIndex != null) {
            add(
                sourceIndex.resolveFqnsByFile(frameEntry.path)
                    .filter { matchesShortName(it, shortName) }
            )
            add(
                frameEntry.imports
                    .filter { !it.endsWith(".*") && matchesShortName(it, shortName) }
            )
        }
        if (packageName.isNotEmpty()) {
            add(listOf("$packageName.$shortName"))
        }
        add(listOf("java.lang.$shortName"))
        if (frameEntry != null) {
            add(
                frameEntry.imports
                    .filter { it.endsWith(".*") }
                    .map { it.removeSuffix(".*") + "." + shortName }
            )
        }
    }

    /**
     * Resolve a simple type name to a fully-qualified name. Returns null
     * when no level yields a loaded candidate; throws when a level yields
     * more than one (explicit ambiguity — list the candidates, don't guess).
     */
    fun resolveFqn(shortName: String): String? {
        for (level in candidateFqns(shortName)) {
            val hits = level.filter { existsInVm(it) }.distinct()
            if (hits.size > 1) {
                throw IllegalArgumentException(
                    "类型名 '$shortName' 有歧义, 候选: ${hits.joinToString(", ")}"
                )
            }
            if (hits.size == 1) return hits[0]
        }
        return null
    }

    /**
     * Resolve to the loaded [ReferenceType] for a short name; null when
     * unresolved or no VM attached. Unlike [resolveClass] this also
     * returns interfaces — `instanceof` / casts must be able to name one.
     */
    fun resolveReferenceType(shortName: String): ReferenceType? {
        val fqn = resolveFqn(shortName) ?: return null
        val vm = frameOrNull?.virtualMachine() ?: return null
        return vm.classesByName(fqn).firstOrNull() as? ReferenceType
    }

    /** Resolve to the loaded [ClassType]; null when unresolved or no VM attached. */
    fun resolveClass(shortName: String): ClassType? {
        val fqn = resolveFqn(shortName) ?: return null
        val vm = frameOrNull?.virtualMachine() ?: return null
        return vm.classesByName(fqn).firstOrNull() as? ClassType
    }

    /**
     * The `INSTANCE` singleton for a Kotlin top-level `object Foo`, when
     * the evaluation frame is a Kotlin file. In Kotlin bytecode the object
     * declaration is a class `Foo` holding the singleton in a
     * `public static final INSTANCE` field; bare `Foo` in an expression
     * refers to that instance, not the class.
     */
    fun kotlinObjectInstance(type: ClassType): ObjectReference? {
        if (!isKotlinFrame()) return null
        val field = type.allFields().firstOrNull {
            it.name() == "INSTANCE" && it.isStatic && it.isPublic && it.isFinal
        } ?: return null
        return type.getValue(field) as? ObjectReference
    }

    private fun isKotlinFrame(): Boolean =
        frameEntry?.path?.let { it.endsWith(".kt") || it.endsWith(".kts") } == true

    private fun matchesShortName(fqn: String, shortName: String): Boolean =
        fqn == shortName || fqn.endsWith(".$shortName")
}
