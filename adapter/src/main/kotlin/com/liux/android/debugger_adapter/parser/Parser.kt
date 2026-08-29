package com.liux.android.debugger_adapter.parser

/**
 * Recursive descent parser for the evaluate-language. Produces an
 * [ExprNode] tree from a flat list of [Token]s.
 *
 * Operator precedence (lowest → highest):
 *
 *   assignment:    =        (right-associative)
 *   ternary:       ? :
 *   logical OR:    ||
 *   logical AND:   &&
 *   equality:      == !=
 *   relational:    < > <= >= instanceof
 *   additive:      + -
 *   multiplicative: * / %
 *   unary:         ! - + ~
 *   postfix:       . () []
 *   primary:       literals, identifiers, ( expr ), new X(args)
 */
internal class Parser(private val tokens: List<Token>) {

    private var pos = 0

    fun parse(): ExprNode {
        val node = parseAssignment()
        // Allow trailing ; (statements look like Java) or EOF.
        if (peek() is Token.Semi) pos++
        if (peek() !is Token.EOF) {
            throw IllegalArgumentException("表达式末尾有多余的 token: ${peek()}")
        }
        return node
    }

    private fun peek(): Token = tokens[pos]
    private fun advance(): Token = tokens[pos++]

    private fun isOp(s: String): Boolean {
        val p = peek()
        return p is Token.Op && p.symbol == s
    }

    private fun expectIdent(): String {
        val p = peek()
        if (p !is Token.Ident) throw IllegalArgumentException("期望标识符, 实际是 $p")
        pos++
        return p.name
    }

    // ---- Operator-precedence climbing ------------------------------------

    private fun parseAssignment(): ExprNode {
        val left = parseTernary()
        if (peek() is Token.Assign) {
            pos++
            val right = parseAssignment() // right-associative
            return ExprNode.Assign(left, right)
        }
        return left
    }

    private fun parseTernary(): ExprNode {
        val cond = parseOr()
        if (peek() is Token.Question) {
            pos++
            val thenB = parseExpression()
            if (peek() !is Token.Colon) throw IllegalArgumentException("三元表达式缺少 ':'")
            pos++
            val elseB = parseExpression()
            return ExprNode.Ternary(cond, thenB, elseB)
        }
        return cond
    }

    /** Lower-bound entry used inside parentheses / array indices / etc. */
    private fun parseExpression(): ExprNode = parseAssignment()

    private fun parseOr(): ExprNode {
        var left = parseAnd()
        while (isOp("||")) {
            pos++
            val right = parseAnd()
            left = ExprNode.BinaryOp("||", left, right)
        }
        return left
    }

    private fun parseAnd(): ExprNode {
        var left = parseEquality()
        while (isOp("&&")) {
            pos++
            val right = parseEquality()
            left = ExprNode.BinaryOp("&&", left, right)
        }
        return left
    }

    private fun parseEquality(): ExprNode {
        var left = parseRelational()
        while (isOp("==") || isOp("!=")) {
            val op = (advance() as Token.Op).symbol
            val right = parseRelational()
            left = ExprNode.BinaryOp(op, left, right)
        }
        return left
    }

    private fun parseRelational(): ExprNode {
        var left = parseAdditive()
        while (true) {
            val p = peek()
            when {
                p is Token.Op && p.symbol in setOf("<", ">", "<=", ">=") -> {
                    val op = (advance() as Token.Op).symbol
                    val right = parseAdditive()
                    left = ExprNode.BinaryOp(op, left, right)
                }
                p is Token.KwInstanceof -> {
                    pos++
                    val typeName = parseTypeName()
                    left = ExprNode.InstanceOf(left, typeName)
                }
                else -> return left
            }
        }
    }

    /**
     * Parse a possibly-dotted type name (`Foo`, `java.util.List`,
     * `Outer.Inner`). We can't tell at parse time which dotted names are
     * packages vs nested classes, so we just take whatever looks like a
     * sequence of identifiers and let the evaluator resolve it.
     */
    private fun parseTypeName(): String {
        val sb = StringBuilder()
        sb.append(expectIdent())
        while (peek() is Token.Dot) {
            pos++
            sb.append('.').append(expectIdent())
        }
        return sb.toString()
    }

    private fun parseAdditive(): ExprNode {
        var left = parseMultiplicative()
        while (isOp("+") || isOp("-")) {
            val op = (advance() as Token.Op).symbol
            val right = parseMultiplicative()
            left = ExprNode.BinaryOp(op, left, right)
        }
        return left
    }

    private fun parseMultiplicative(): ExprNode {
        var left = parseUnary()
        while (isOp("*") || isOp("/") || isOp("%")) {
            val op = (advance() as Token.Op).symbol
            val right = parseUnary()
            left = ExprNode.BinaryOp(op, left, right)
        }
        return left
    }

    private fun parseUnary(): ExprNode {
        return when {
            isOp("!") -> { pos++; ExprNode.UnaryOp("!", parseUnary()) }
            isOp("-") -> { pos++; ExprNode.UnaryOp("-", parseUnary()) }
            isOp("+") -> { pos++; ExprNode.UnaryOp("+", parseUnary()) }
            isOp("~") -> { pos++; ExprNode.UnaryOp("~", parseUnary()) }
            else -> parsePostfix()
        }
    }

    /**
     * Postfix handles `.`, `(args)`, `[idx]` in a loop. Note: a bare
     * `(args)` after an identifier is NOT treated as a call — that would
     * make `foo(bar)` ambiguous when `foo` is a local variable. Users must
     * write `foo.method()` for method calls. (Java itself uses the same
     * convention.)
     */
    private fun parsePostfix(): ExprNode {
        var node = parsePrimary()
        while (true) {
            node = when (peek()) {
                is Token.Dot -> {
                    pos++
                    val name = expectIdent()
                    if (peek() is Token.LParen) {
                        ExprNode.Call(node, name, parseArgList())
                    } else {
                        ExprNode.Field(node, name)
                    }
                }
                is Token.LBrack -> {
                    pos++
                    val idx = parseExpression()
                    if (peek() !is Token.RBrack) throw IllegalArgumentException("数组下标缺少 ']'")
                    pos++
                    ExprNode.Index(node, idx)
                }
                else -> return node
            }
        }
    }

    private fun parseArgList(): List<ExprNode> {
        if (peek() !is Token.LParen) throw IllegalArgumentException("期望 '('")
        pos++
        val args = mutableListOf<ExprNode>()
        if (peek() !is Token.RParen) {
            args.add(parseAssignment())
            while (peek() is Token.Comma) {
                pos++
                args.add(parseAssignment())
            }
        }
        if (peek() !is Token.RParen) throw IllegalArgumentException("参数列表缺少 ')'")
        pos++
        return args
    }

    private fun parsePrimary(): ExprNode {
        return when (val p = peek()) {
            is Token.NumberLit -> { pos++; ExprNode.NumberLit(p.text) }
            is Token.StringLit -> { pos++; ExprNode.StringLit(p.text) }
            is Token.CharLit -> { pos++; ExprNode.CharLit(p.code) }
            is Token.KwNull -> { pos++; ExprNode.NullLit }
            is Token.KwTrue -> { pos++; ExprNode.TrueLit }
            is Token.KwFalse -> { pos++; ExprNode.FalseLit }
            is Token.KwThis -> { pos++; ExprNode.ThisRef }
            is Token.KwNew -> {
                pos++
                val className = parseTypeName()
                val args = if (peek() is Token.LParen) parseArgList() else emptyList()
                ExprNode.New(className, args)
            }
            is Token.Ident -> { pos++; ExprNode.Var(p.name) }
            is Token.LParen -> {
                pos++
                val inner = parseAssignment()
                if (peek() !is Token.RParen) throw IllegalArgumentException("缺少 ')'")
                pos++
                inner
            }
            else -> throw IllegalArgumentException("意外的 token: $p")
        }
    }
}
