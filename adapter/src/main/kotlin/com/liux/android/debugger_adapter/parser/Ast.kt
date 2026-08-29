package com.liux.android.debugger_adapter.parser

/**
 * AST node types produced by [Parser] and consumed by [Evaluator].
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
}
