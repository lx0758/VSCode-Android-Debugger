package com.liux.android.debugger_adapter.eval.kotlin

import com.liux.android.debugger_adapter.eval.common.ExprNode

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
 *   comparison:    < > <= >= instanceof
 *   type check:    is      (Kotlin `x is Foo`)
 *   elvis:         ?:      (Kotlin `a ?: b`, right-associative)
 *   additive:      + -
 *   multiplicative: * / %
 *   cast:          as as?  (Kotlin `x as Foo` / `x as? Foo`)
 *   unary:         ! - + ~
 *   postfix:       . () []
 *   primary:       literals, identifiers, ( expr ), new X(args)
 *
 * The chain mirrors Kotlin's own precedence table (`as` binds tighter
 * than `*`, `?:` tighter than `is`, `is` tighter than the comparison
 * operators). `as` / `is` are recognised *contextually* — they are
 * ordinary identifiers in the lexer and only act as operators when they
 * sit in operator position — so that Java-ish names such as `is` or
 * `as` keep working as field names (`view.is`).
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

    /**
     * Whether the next token is the plain identifier [name]. Used for the
     * contextual keywords `as` / `is`: they are ordinary identifiers in
     * the lexer and only turn into operators in operator position.
     */
    private fun peekIsIdent(name: String): Boolean {
        val p = peek()
        return p is Token.Ident && p.name == name
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
        var left = parseIsCheck()
        while (true) {
            val p = peek()
            when {
                p is Token.Op && p.symbol in setOf("<", ">", "<=", ">=") -> {
                    val op = (advance() as Token.Op).symbol
                    val right = parseIsCheck()
                    left = ExprNode.BinaryOp(op, left, right)
                }
                p is Token.KwInstanceof -> {
                    pos++
                    val typeName = parseTypeName(allowGenerics = false)
                    left = ExprNode.InstanceOf(left, typeName)
                }
                else -> return left
            }
        }
    }

    /**
     * Kotlin's type check: `x is Foo`.
     *
     * `is` is recognised *contextually* — the lexer keeps it an ordinary
     * identifier and only the operator position turns it into a type
     * check. That keeps Java-ish names such as `is` usable as fields
     * (`view.is`) and as plain variables.
     *
     * The right-hand side is a *type*, not an expression, so it is
     * parsed as a dotted type name (no generic arguments — Kotlin
     * rejects `is List<String>` anyway).
     */
    private fun parseIsCheck(): ExprNode {
        val left = parseElvis()
        if (peekIsIdent("is")) {
            pos++
            val typeName = parseTypeName(allowGenerics = false)
            // Kotlin rejects `is List<String>` ("Cannot check for instance
            // of erased type"). A `<` that does *not* open a type-argument
            // list is a comparison (`x is T < y`) and stays untouched.
            if (isOp("<") && skipGenericArgs()) {
                throw IllegalArgumentException("Kotlin 不允许在 'is' 检查中使用泛型类型")
            }
            return ExprNode.InstanceOf(left, typeName)
        }
        return left
    }

    /**
     * Kotlin's elvis operator: `a ?: b` yields `b` when `a` is null.
     * Right-associative, so `a ?: b ?: c` means `a ?: (b ?: c)`.
     */
    private fun parseElvis(): ExprNode {
        val left = parseAdditive()
        if (isOp("?:")) {
            pos++
            val right = parseElvis()
            return ExprNode.Elvis(left, right)
        }
        return left
    }

    /**
     * Kotlin's cast: `x as Foo` (throws on mismatch) and `x as? Foo`
     * (yields null on mismatch).
     *
     * `as` binds tighter than `*` (Kotlin's own precedence table), so
     * it sits between the multiplicative and the unary level.
     */
    private fun parseCast(): ExprNode {
        var left = parseUnary()
        while (peekIsIdent("as")) {
            pos++
            // `as?` — the safe-cast marker is the question mark right
            // after the keyword.
            val safe = peek() is Token.Question
            if (safe) pos++
            left = ExprNode.Cast(left, parseTypeName(allowGenerics = true), safe)
        }
        return left
    }

    /**
     * Parse a possibly-dotted type name (`Foo`, `java.util.List`,
     * `Outer.Inner`). We can't tell at parse time which dotted names are
     * packages vs nested classes, so we just take whatever looks like a
     * sequence of identifiers and let the evaluator resolve it.
     *
     * When [allowGenerics] is set, a trailing `<...>` type-argument list
     * is consumed and dropped (the runtime only knows the erasure).
     */
    private fun parseTypeName(allowGenerics: Boolean): String {
        val sb = StringBuilder()
        sb.append(expectIdent())
        while (peek() is Token.Dot) {
            pos++
            sb.append('.').append(expectIdent())
        }
        if (allowGenerics) skipGenericArgs()
        return sb.toString()
    }

    /**
     * Skip a `<...>` type-argument list if — and only if — the tokens
     * really form one. A greedy skip would swallow the comparison in
     * `x as Foo < y`, so the scan is restricted to type-ish tokens and
     * gives up (restoring the position) on anything else.
     *
     * @return true when a type-argument list was consumed.
     */
    private fun skipGenericArgs(): Boolean {
        if (!isOp("<")) return false
        val start = pos
        var depth = 0
        var i = pos
        while (i < tokens.size) {
            when (val t = tokens[i]) {
                is Token.Op -> when (t.symbol) {
                    "<" -> depth++
                    ">" -> {
                        depth--
                        if (depth == 0) {
                            pos = i + 1
                            return true
                        }
                    }
                    else -> {
                        pos = start
                        return false
                    }
                }
                is Token.Ident, is Token.Dot, is Token.Comma, is Token.Question -> {}
                else -> {
                    pos = start
                    return false
                }
            }
            i++
        }
        pos = start
        return false
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
        var left = parseCast()
        while (isOp("*") || isOp("/") || isOp("%")) {
            val op = (advance() as Token.Op).symbol
            val right = parseCast()
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
                is Token.SafeCallOp -> {
                    pos++
                    val name = expectIdent()
                    if (peek() is Token.LParen) {
                        ExprNode.SafeCall(node, name, parseArgList())
                    } else {
                        ExprNode.SafeCall(node, name, null)
                    }
                }
                is Token.NotNullOp -> {
                    pos++
                    ExprNode.NotNull(node)
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

    /**
     * Kotlin string template: `"prefix${expr}suffix"` and simple `$var`.
     * Produces interleaved [ExprNode.StringTemplate] parts. Only
     * non-nested `${...}` is supported (P1 limitation): the inner
     * expression must not contain braces or strings.
     */
    private fun parseStringTemplate(raw: String): ExprNode {
        val inner = raw.substring(1, raw.length - 1)
        val parts = mutableListOf<String>()
        val exprs = mutableListOf<ExprNode>()
        val sb = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                c == '$' && i + 1 < inner.length && inner[i + 1] == '{' -> {
                    val close = inner.indexOf('}', i + 2)
                    if (close < 0) throw IllegalArgumentException("字符串模板缺少配对 '}'")
                    parts += sb.toString()
                    sb.clear()
                    val exprSrc = inner.substring(i + 2, close)
                    exprs += Parser(Lexer(exprSrc).tokenize()).parse()
                    i = close + 1
                }
                c == '$' && i + 1 < inner.length &&
                    (inner[i + 1].isLetterOrDigit() || inner[i + 1] == '_') -> {
                    var j = i + 1
                    while (j < inner.length && (inner[j].isLetterOrDigit() || inner[j] == '_')) j++
                    parts += sb.toString()
                    sb.clear()
                    exprs += Parser(Lexer(inner.substring(i + 1, j)).tokenize()).parse()
                    i = j
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        parts += sb.toString()
        return ExprNode.StringTemplate(parts, exprs)
    }

    private fun parsePrimary(): ExprNode {
        return when (val p = peek()) {
            is Token.NumberLit -> { pos++; ExprNode.NumberLit(p.text) }
            is Token.StringLit -> {
                pos++
                if ('$' in p.text) parseStringTemplate(p.text) else ExprNode.StringLit(p.text)
            }
            is Token.CharLit -> { pos++; ExprNode.CharLit(p.code) }
            is Token.KwNull -> { pos++; ExprNode.NullLit }
            is Token.KwTrue -> { pos++; ExprNode.TrueLit }
            is Token.KwFalse -> { pos++; ExprNode.FalseLit }
            is Token.KwThis -> { pos++; ExprNode.ThisRef }
            is Token.KwNew -> {
                pos++
                val className = parseTypeName(allowGenerics = true)
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
