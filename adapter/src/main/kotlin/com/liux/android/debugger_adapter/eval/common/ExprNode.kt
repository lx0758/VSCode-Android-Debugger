package com.liux.android.debugger_adapter.eval.common

/**
 * AST node types produced by the frontends (JDT translation for Java,
 * [com.liux.android.debugger_adapter.eval.kotlin.Parser] for Kotlin) and
 * consumed by the runtime evaluator.
 *
 * Kept as a sealed class so the evaluator's `when` over [ExprNode] is
 * exhaustive and the compiler will flag missed cases.
 */
internal sealed class ExprNode {

    /** Number literal as raw text; the evaluator parses it into a JDI primitive. */
    data class NumberLit(val text: String) : ExprNode()
    /** String literal including the surrounding double quotes. */
    data class StringLit(val text: String) : ExprNode()
    /** Character literal as int code point. */
    data class CharLit(val code: Int) : ExprNode()

    object NullLit : ExprNode()
    object TrueLit : ExprNode()
    object FalseLit : ExprNode()
    object ThisRef : ExprNode()

    /** Bare identifier — could be a local/parameter OR a class name (resolved by evaluator). */
    data class Var(val name: String) : ExprNode()

    data class Field(val target: ExprNode, val name: String) : ExprNode()
    data class Call(val target: ExprNode, val name: String, val args: List<ExprNode>) : ExprNode()
    data class Index(val target: ExprNode, val index: ExprNode) : ExprNode()

    /** `new ClassName(args)` — name is FQN or simple name. */
    data class New(val className: String, val args: List<ExprNode>) : ExprNode()

    data class BinaryOp(val op: String, val left: ExprNode, val right: ExprNode) : ExprNode()
    data class UnaryOp(val op: String, val operand: ExprNode) : ExprNode()
    data class Ternary(val cond: ExprNode, val thenBranch: ExprNode, val elseBranch: ExprNode) : ExprNode()

    /** `target instanceof FQN` — typeName is the FQN of the right-hand side type. */
    data class InstanceOf(val target: ExprNode, val typeName: String) : ExprNode()

    /** `lhs = rhs` — assignment; `target` is a Var / Field / Index. */
    data class Assign(val target: ExprNode, val value: ExprNode) : ExprNode()

    /**
     * `(TypeName) target` (Java) or `target as TypeName` (Kotlin) — cast;
     * typeName as written (simple name or FQN).
     *
     * [safe] marks Kotlin's `as?`: a failed cast yields null instead of
     * throwing a ClassCastException.
     */
    data class Cast(val target: ExprNode, val typeName: String, val safe: Boolean = false) : ExprNode()

    /**
     * `left ?: right` (Kotlin elvis) — yields [right] when [left] is null,
     * otherwise [left].
     */
    data class Elvis(val left: ExprNode, val right: ExprNode) : ExprNode()

    /**
     * `new T[d1][d2]` (dimensions non-empty, initializer null) or
     * `new T[]{...}` (dimensions empty, initializer non-null).
     */
    data class ArrayAllocation(
        val elementType: String,
        val dimensions: List<ExprNode>,
        val initializer: ArrayInitializer? = null,
    ) : ExprNode()

    /** `TypeName.class` — evaluated to the class mirror (ClassObjectReference). */
    data class ClassLiteral(val typeName: String) : ExprNode()

    /** `{ e1, e2, ... }` — array initializer; nested braces nest nodes. */
    data class ArrayInitializer(val elementType: String, val elements: List<ExprNode>) : ExprNode()

    // ---- Kotlin sugar (parsed by the Kotlin frontend in T8) ----------------

    /** `target?.name` (args == null) or `target?.name(args)` — null-short-circuit access. */
    data class SafeCall(val target: ExprNode, val name: String, val args: List<ExprNode>? = null) : ExprNode()

    /** `target!!` — throw on a null receiver, else the target value. */
    data class NotNull(val target: ExprNode) : ExprNode()

    /** `"a${x}b"` — `parts[i] + formatValue(exprs[i]) + parts[i+1]`; parts.size == exprs.size + 1. */
    data class StringTemplate(val parts: List<String>, val exprs: List<ExprNode>) : ExprNode()
}
