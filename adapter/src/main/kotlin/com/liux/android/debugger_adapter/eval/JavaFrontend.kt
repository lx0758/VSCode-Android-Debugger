package com.liux.android.debugger_adapter.eval

import com.liux.android.debugger_adapter.eval.common.ExprNode
import org.eclipse.jdt.core.dom.AST
import org.eclipse.jdt.core.dom.ASTNode
import org.eclipse.jdt.core.dom.ASTParser
import org.eclipse.jdt.core.dom.ArrayAccess
import org.eclipse.jdt.core.dom.ArrayCreation
import org.eclipse.jdt.core.dom.ArrayInitializer
import org.eclipse.jdt.core.dom.ArrayType
import org.eclipse.jdt.core.dom.Assignment
import org.eclipse.jdt.core.dom.BooleanLiteral
import org.eclipse.jdt.core.dom.CastExpression
import org.eclipse.jdt.core.dom.CharacterLiteral
import org.eclipse.jdt.core.dom.ClassInstanceCreation
import org.eclipse.jdt.core.dom.ConditionalExpression
import org.eclipse.jdt.core.dom.Expression
import org.eclipse.jdt.core.dom.FieldAccess
import org.eclipse.jdt.core.dom.InfixExpression
import org.eclipse.jdt.core.dom.InstanceofExpression
import org.eclipse.jdt.core.dom.MethodInvocation
import org.eclipse.jdt.core.dom.NullLiteral
import org.eclipse.jdt.core.dom.NumberLiteral
import org.eclipse.jdt.core.dom.ParameterizedType
import org.eclipse.jdt.core.dom.ParenthesizedExpression
import org.eclipse.jdt.core.dom.PostfixExpression
import org.eclipse.jdt.core.dom.PrefixExpression
import org.eclipse.jdt.core.dom.PrimitiveType
import org.eclipse.jdt.core.dom.QualifiedName
import org.eclipse.jdt.core.dom.QualifiedType
import org.eclipse.jdt.core.dom.SimpleName
import org.eclipse.jdt.core.dom.SimpleType
import org.eclipse.jdt.core.dom.StringLiteral
import org.eclipse.jdt.core.dom.SuperFieldAccess
import org.eclipse.jdt.core.dom.SuperMethodInvocation
import org.eclipse.jdt.core.dom.ThisExpression
import org.eclipse.jdt.core.dom.Type
import org.eclipse.jdt.core.dom.TypeLiteral
import org.eclipse.jdt.core.dom.WildcardType

/**
 * Java expression frontend: parses an expression with JDT's standalone
 * ASTParser (K_EXPRESSION, no environment — the target VM is the type
 * library, D26) and translates the AST into the shared [ExprNode] IR
 * consumed by [RuntimeEvaluator].
 *
 * Translation is purely syntactic (bindings are unavailable without an
 * environment), so dotted names are split with a case heuristic: the
 * first segment starting with an uppercase letter is taken as the type
 * name, everything before it as the package. `obj.field` (all lowercase)
 * falls back to an instance field chain rooted at a [ExprNode.Var].
 */
internal object JavaFrontend {

    fun parse(expression: String): ExprNode {
        // JLS14 is the highest level JDT 3.23.0 ships (JLS15+ arrived with
        // 3.24+ which requires Java 11) — plenty for watch expressions.
        val parser = ASTParser.newParser(AST.JLS14)
        parser.setKind(ASTParser.K_EXPRESSION)
        parser.setEnvironment(null, null, null, true)
        parser.setSource(expression.toCharArray())
        parser.setResolveBindings(false)
        val node = try {
            parser.createAST(null)
        } catch (e: Exception) {
            throw IllegalArgumentException("表达式语法错误: ${e.message}", e)
        } as? Expression
            ?: throw IllegalArgumentException("表达式语法错误: '$expression'")
        // K_EXPRESSION can silently drop trailing garbage; verify the AST
        // re-prints to (whitespace-insensitively) the same text.
        val printed = node.toString().replace(WHITESPACE, "")
        if (printed != expression.replace(WHITESPACE, "")) {
            throw IllegalArgumentException("表达式语法错误: '$expression'")
        }
        return translate(node)
    }

    private val WHITESPACE = Regex("\\s+")

    // ---------- AST → ExprNode -------------------------------------------

    fun translate(node: Expression): ExprNode = when (node) {
        is NumberLiteral -> ExprNode.NumberLit(node.token)
        is StringLiteral -> ExprNode.StringLit(node.escapedValue)
        is CharacterLiteral -> ExprNode.CharLit(node.charValue().code)
        is NullLiteral -> ExprNode.NullLit
        is BooleanLiteral -> if (node.booleanValue()) ExprNode.TrueLit else ExprNode.FalseLit
        is ThisExpression -> ExprNode.ThisRef
        is SimpleName -> ExprNode.Var(node.identifier)
        is QualifiedName -> translateQualifiedName(node)
        is FieldAccess -> ExprNode.Field(translate(node.expression), node.name.identifier)
        is MethodInvocation -> translateMethodInvocation(node)
        is SuperMethodInvocation -> throw IllegalArgumentException("super 调用暂不支持")
        is SuperFieldAccess -> throw IllegalArgumentException("super 字段访问暂不支持")
        is ClassInstanceCreation -> translateNew(node)
        is ArrayAccess -> ExprNode.Index(translate(node.array), translate(node.index))
        is ArrayCreation -> translateArrayCreation(node)
        is ArrayInitializer ->
            throw IllegalArgumentException("数组初始化器必须配合 new T[]{...} 使用")
        is CastExpression -> ExprNode.Cast(translate(node.expression), baseTypeName(node.type))
        is TypeLiteral -> ExprNode.ClassLiteral(baseTypeName(node.type))
        is InfixExpression -> translateInfix(node)
        is ConditionalExpression ->
            ExprNode.Ternary(translate(node.expression), translate(node.thenExpression), translate(node.elseExpression))
        is Assignment -> translateAssignment(node)
        is InstanceofExpression -> ExprNode.InstanceOf(translate(node.leftOperand), baseTypeName(node.rightOperand))
        is ParenthesizedExpression -> translate(node.expression)
        is PrefixExpression -> when (node.operator.toString()) {
            "++", "--" -> throw IllegalArgumentException("不支持自增/自减表达式 (求值无法回写)")
            else -> ExprNode.UnaryOp(node.operator.toString(), translate(node.operand))
        }
        is PostfixExpression -> throw IllegalArgumentException("不支持自增/自减表达式 (求值无法回写)")
        else -> throw IllegalArgumentException("不支持的 Java 表达式: ${node.javaClass.simpleName}")
    }

    /**
     * Flatten `a.b.C.d` into a dotted chain. The first uppercase segment
     * marks the class name; the prefix is the package. Without an
     * uppercase segment the chain is treated as instance field access
     * rooted at a variable.
     */
    private fun translateQualifiedName(node: QualifiedName): ExprNode {
        val segments = mutableListOf<String>()
        var cur: ASTNode = node
        while (cur is QualifiedName) {
            segments += cur.name.identifier
            cur = cur.qualifier
        }
        when (cur) {
            is SimpleName -> segments += cur.identifier
            is FieldAccess -> {
                // e.g. (expr).a.b — evaluate the qualifier as an expression.
                segments += cur.name.identifier
                var result = translate(cur.expression)
                for (seg in segments.reversed()) result = ExprNode.Field(result, seg)
                return result
            }
            else -> throw IllegalArgumentException("不支持的 Java 表达式: ${cur.javaClass.simpleName}")
        }
        segments.reverse()
        val upperIdx = segments.indexOfFirst { it.isNotEmpty() && it[0].isUpperCase() }
        return if (upperIdx >= 0) {
            // Package prefix + type name, then remaining segments as fields.
            val typeName = segments.subList(0, upperIdx + 1).joinToString(".")
            var result: ExprNode = ExprNode.Var(typeName)
            for (seg in segments.subList(upperIdx + 1, segments.size)) {
                result = ExprNode.Field(result, seg)
            }
            result
        } else {
            var result: ExprNode = ExprNode.Var(segments[0])
            for (seg in segments.subList(1, segments.size)) {
                result = ExprNode.Field(result, seg)
            }
            result
        }
    }

    private fun translateMethodInvocation(node: MethodInvocation): ExprNode =
        ExprNode.Call(
            target = node.expression?.let { translate(it) } ?: ExprNode.ThisRef,
            name = node.name.identifier,
            args = node.arguments().map { translate(it as Expression) },
        )

    private fun translateNew(node: ClassInstanceCreation): ExprNode {
        if (node.anonymousClassDeclaration != null) {
            throw IllegalArgumentException("匿名内部类不支持在调试求值中使用")
        }
        return ExprNode.New(
            className = baseTypeName(node.type),
            args = node.arguments().map { translate(it as Expression) },
        )
    }

    private fun translateArrayCreation(node: ArrayCreation): ExprNode {
        val arrayType = node.type
        val elementTypeName = baseTypeName(arrayType.elementType)
        val initializer = node.initializer
        if (initializer != null) {
            // Elements of the outermost initializer sit one dimension below
            // the declared array: int[][]{{1},{2}} → elements are int[].
            val topElementType = elementTypeName + "[]".repeat(arrayType.dimensions().size)
            return ExprNode.ArrayAllocation(
                elementType = elementTypeName,
                dimensions = emptyList(),
                initializer = translateInitializer(initializer, topElementType),
            )
        }
        return ExprNode.ArrayAllocation(
            elementType = elementTypeName,
            dimensions = node.dimensions().map { translate(it as Expression) },
            initializer = null,
        )
    }

    private fun translateInitializer(node: ArrayInitializer, elementTypeName: String): ExprNode.ArrayInitializer {
        val innerElementType = elementTypeName.removeSuffix("[]")
        return ExprNode.ArrayInitializer(
            elementType = innerElementType,
            elements = node.expressions().map { expr ->
                if (expr is ArrayInitializer) translateInitializer(expr, innerElementType)
                else translate(expr as Expression)
            },
        )
    }

    private fun translateInfix(node: InfixExpression): ExprNode {
        val op = node.operator.toString()
        var result = ExprNode.BinaryOp(op, translate(node.leftOperand), translate(node.rightOperand))
        for (ext in node.extendedOperands()) {
            result = ExprNode.BinaryOp(op, result, translate(ext as Expression))
        }
        return result
    }

    private fun translateAssignment(node: Assignment): ExprNode {
        val target = translate(node.leftHandSide)
        val rhs = translate(node.rightHandSide)
        val op = node.operator.toString()
        return if (op == "=") {
            ExprNode.Assign(target, rhs)
        } else {
            // x += y → x = x + y (target may be re-evaluated; acceptable for
            // watch/condition expressions).
            ExprNode.Assign(target, ExprNode.BinaryOp(op.removeSuffix("="), target, rhs))
        }
    }

    /**
     * Base (non-array, non-generic) type name as written: `int`,
     * `java.util.ArrayList`, `ArrayList`. Nested arrays collapse to the
     * element type (dimensions are tracked separately).
     */
    private fun baseTypeName(t: Type): String = when (t) {
        is PrimitiveType -> t.primitiveTypeCode.toString()
        is ArrayType -> baseTypeName(t.elementType)
        is ParameterizedType -> baseTypeName(t.type)
        is SimpleType -> t.name.toString()
        // `Outer<String>.Inner` — the qualifier carries the package and/or
        // the enclosing type, both of which are part of the name.
        is QualifiedType -> {
            val qualifier = t.qualifier
            val prefix = when (qualifier) {
                is ParameterizedType -> baseTypeName(qualifier.type)
                is SimpleType -> qualifier.name.toString()
                is QualifiedType -> baseTypeName(qualifier)
                else -> throw IllegalArgumentException("不支持的类型: ${qualifier.javaClass.simpleName}")
            }
            "$prefix.${t.name.identifier}"
        }
        is WildcardType -> throw IllegalArgumentException("通配符类型不能出现在表达式中")
        else -> throw IllegalArgumentException("不支持的类型: ${t.javaClass.simpleName}")
    }
}
