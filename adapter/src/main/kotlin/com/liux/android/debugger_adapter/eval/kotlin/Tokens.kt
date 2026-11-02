package com.liux.android.debugger_adapter.eval.kotlin

/**
 * Token types produced by the [Lexer]. The parser consumes a flat list of
 * these and produces an [com.liux.android.debugger_adapter.eval.common.ExprNode] tree.
 */
internal sealed class Token {
    data class Ident(val name: String) : Token()
    /** Number literal including type suffix, e.g. `42`, `3.14f`, `100L`. */
    data class NumberLit(val text: String) : Token()
    /** String literal WITH surrounding double quotes, e.g. `"foo"`. */
    data class StringLit(val text: String) : Token()
    /** Character literal as int code, e.g. `'a'`. */
    data class CharLit(val code: Int) : Token()

    object LParen : Token()
    object RParen : Token()
    object LBrack : Token()
    object RBrack : Token()
    object LBrace : Token()
    object RBrace : Token()
    object Dot : Token()
    object Comma : Token()
    object Semi : Token()
    object Question : Token()
    object Colon : Token()
    object Assign : Token()

    /** All multi-character operators, kept as their symbol for the parser to dispatch on. */
    data class Op(val symbol: String) : Token()

    /** Kotlin null-safe access `?.`. */
    object SafeCallOp : Token()
    /** Kotlin null-safety assertion `!!`. */
    object NotNullOp : Token()

    object KwNew : Token()
    object KwNull : Token()
    object KwTrue : Token()
    object KwFalse : Token()
    object KwThis : Token()
    object KwInstanceof : Token()
    object EOF : Token()
}

/**
 * Lexer / tokenizer. Splits an expression string into a flat list of [Token]s.
 *
 * The lexer is deliberately small and stateless across calls — tokenize()
 * returns the whole list at once so the parser can do its own lookahead.
 */
internal class Lexer(private val src: String) {

    private var pos = 0

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (pos < src.length) {
            val c = src[pos]
            when {
                c.isWhitespace() -> pos++
                c == '"' -> tokens.add(readString())
                c == '\'' -> tokens.add(readChar())
                c.isDigit() -> tokens.add(readNumber())
                c.isLetter() || c == '_' || c == '$' -> tokens.add(readIdentOrKeyword())
                c == '(' -> { tokens.add(Token.LParen); pos++ }
                c == ')' -> { tokens.add(Token.RParen); pos++ }
                c == '[' -> { tokens.add(Token.LBrack); pos++ }
                c == ']' -> { tokens.add(Token.RBrack); pos++ }
                c == '{' -> { tokens.add(Token.LBrace); pos++ }
                c == '}' -> { tokens.add(Token.RBrace); pos++ }
                c == ',' -> { tokens.add(Token.Comma); pos++ }
                c == ';' -> { tokens.add(Token.Semi); pos++ }
                c == '.' -> { tokens.add(Token.Dot); pos++ }
                c == '?' -> {
                    // `?.` is the Kotlin safe-call, `?:` the elvis operator;
                    // a bare `?` stays ternary.
                    when {
                        pos + 1 < src.length && src[pos + 1] == '.' -> {
                            tokens.add(Token.SafeCallOp); pos += 2
                        }
                        pos + 1 < src.length && src[pos + 1] == ':' -> {
                            tokens.add(Token.Op("?:")); pos += 2
                        }
                        else -> {
                            tokens.add(Token.Question); pos++
                        }
                    }
                }
                c == ':' -> {
                    // `::` is a callable reference — not supported by this
                    // frontend. Reject it explicitly instead of letting the
                    // parser see two stray colons and mis-read the rest.
                    if (pos + 1 < src.length && src[pos + 1] == ':') {
                        throw IllegalArgumentException("不支持可调用引用语法 '::' (位置 $pos)")
                    }
                    tokens.add(Token.Colon); pos++
                }
                c == '=' -> tokens.add(readEqualsOp())
                c == '!' -> tokens.add(readNotOp())
                c == '<' -> tokens.add(readLessOp())
                c == '>' -> tokens.add(readGreaterOp())
                c == '+' -> { tokens.add(Token.Op("+")); pos++ }
                c == '-' -> {
                    // `->` is a lambda arrow — not supported by this
                    // frontend. Reject it explicitly so the fallback path
                    // can't silently mis-read `x -> y` as `x - > y`.
                    // `a-->b` (decrement, then compare) stays readable.
                    if (pos + 1 < src.length && src[pos + 1] == '>' &&
                        !(pos >= 1 && src[pos - 1] == '-')
                    ) {
                        throw IllegalArgumentException("不支持 lambda 语法 '->' (位置 $pos)")
                    }
                    tokens.add(Token.Op("-")); pos++
                }
                c == '*' -> { tokens.add(Token.Op("*")); pos++ }
                c == '/' -> tokens.add(readSlashOp())
                c == '%' -> { tokens.add(Token.Op("%")); pos++ }
                c == '&' -> tokens.add(readAndOp())
                c == '|' -> tokens.add(readOrOp())
                c == '~' -> { tokens.add(Token.Op("~")); pos++ }
                else -> throw IllegalArgumentException("无法识别的字符 '$c' (位置 $pos)")
            }
        }
        tokens.add(Token.EOF)
        return tokens
    }

    private fun readString(): Token.StringLit {
        val sb = StringBuilder()
        sb.append('"') // keep quotes so the evaluator can recognise them
        pos++ // skip opening "
        while (pos < src.length && src[pos] != '"') {
            if (src[pos] == '\\' && pos + 1 < src.length) {
                // Preserve escape sequence verbatim — the evaluator interprets
                // them when extracting the actual value.
                sb.append('\\').append(src[pos + 1])
                pos += 2
            } else {
                sb.append(src[pos])
                pos++
            }
        }
        if (pos < src.length) {
            sb.append('"')
            pos++ // skip closing "
        }
        return Token.StringLit(sb.toString())
    }

    private fun readChar(): Token.CharLit {
        pos++ // skip opening '
        val code = if (pos < src.length) {
            if (src[pos] == '\\' && pos + 1 < src.length) {
                when (src[pos + 1]) {
                    'n' -> '\n'.code
                    't' -> '\t'.code
                    'r' -> '\r'.code
                    '\\' -> '\\'.code
                    '\'' -> '\''.code
                    '0' -> 0
                    else -> src[pos + 1].code
                }.also { pos += 2 }
            } else {
                src[pos].code.also { pos++ }
            }
        } else 0
        if (pos < src.length && src[pos] == '\'') pos++
        return Token.CharLit(code)
    }

    private fun readNumber(): Token.NumberLit {
        val start = pos
        while (pos < src.length && src[pos].isDigit()) pos++
        // Decimal part — only consume '.' when followed by another digit, so
        // `arr.length` doesn't get parsed as a number.
        if (pos < src.length && src[pos] == '.' && pos + 1 < src.length && src[pos + 1].isDigit()) {
            pos++
            while (pos < src.length && src[pos].isDigit()) pos++
        }
        // Exponent
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            while (pos < src.length && src[pos].isDigit()) pos++
        }
        // Type suffix
        if (pos < src.length) {
            val c = src[pos]
            if (c == 'l' || c == 'L' || c == 'f' || c == 'F' || c == 'd' || c == 'D') pos++
        }
        return Token.NumberLit(src.substring(start, pos))
    }

    private fun readIdentOrKeyword(): Token {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos] == '$')) {
            pos++
        }
        val name = src.substring(start, pos)
        return when (name) {
            "new" -> Token.KwNew
            "null" -> Token.KwNull
            "true" -> Token.KwTrue
            "false" -> Token.KwFalse
            "this" -> Token.KwThis
            "instanceof" -> Token.KwInstanceof
            else -> Token.Ident(name)
        }
    }

    private fun readEqualsOp(): Token {
        pos++
        return if (pos < src.length && src[pos] == '=') { pos++; Token.Op("==") }
        else Token.Assign
    }

    private fun readNotOp(): Token {
        pos++
        return when {
            pos < src.length && src[pos] == '!' -> { pos++; Token.NotNullOp } // Kotlin `!!`
            pos < src.length && src[pos] == '=' -> { pos++; Token.Op("!=") }
            else -> Token.Op("!")
        }
    }

    private fun readLessOp(): Token {
        pos++
        return if (pos < src.length && src[pos] == '=') { pos++; Token.Op("<=") }
        else Token.Op("<")
    }

    private fun readGreaterOp(): Token {
        pos++
        return if (pos < src.length && src[pos] == '=') { pos++; Token.Op(">=") }
        else Token.Op(">")
    }

    private fun readSlashOp(): Token {
        // We don't support // or /* comments in evaluate context; users don't
        // expect them. Always treat as Op("/").
        pos++
        return Token.Op("/")
    }

    private fun readAndOp(): Token {
        pos++
        if (pos < src.length && src[pos] == '&') { pos++; return Token.Op("&&") }
        throw IllegalArgumentException("位置 $pos 出现单独的 '&', 应为 '&&'")
    }

    private fun readOrOp(): Token {
        pos++
        if (pos < src.length && src[pos] == '|') { pos++; return Token.Op("||") }
        throw IllegalArgumentException("位置 $pos 出现单独的 '|', 应为 '||'")
    }
}
