package com.liux.android.debugger_adapter.eval

/**
 * Detects Kotlin-only syntax in an expression, so the dispatcher can pick
 * the frontend that can actually parse it.
 *
 * The expression language of the adapter accepts both Java and Kotlin
 * shapes. A Java expression is a subset of what the Kotlin frontend can
 * parse, but not the other way round: `?.`, `!!`, `as` / `as?`, `?:` and
 * the `is` type check have no Java spelling, and JDT rejects them
 * outright. Detecting those shapes lets the dispatcher route the
 * expression to the Kotlin frontend *before* JDT gets a chance to fail,
 * while everything else (casts, `new`, array allocation, class literals,
 * fully-qualified references) still goes through JDT — even inside a
 * Kotlin stack frame.
 *
 * String literals are scanned separately: a `$name` / `${expr}` inside
 * quotes is a Kotlin template only when the evaluation frame is a Kotlin
 * file. In a Java file the same text is a plain literal, so the template
 * feature is reported but the caller decides what to do with it.
 */
internal object KotlinSyntaxDetector {

    /** Kotlin-only syntactic shapes, grouped by the frontend they need. */
    enum class Feature {
        /** `?.` safe call. */
        SAFE_CALL,

        /** `!!` not-null assertion. */
        NOT_NULL,

        /** `as` / `as?` cast. */
        AS_CAST,

        /** `?:` elvis operator. */
        ELVIS,

        /** `is` type check. */
        IS_CHECK,

        /** `$name` / `${expr}` inside a string literal. */
        TEMPLATE,
    }

    /**
     * All Kotlin-only features present in [expr].
     *
     * String and character literals are skipped for the operator-shaped
     * features (a `?.` inside a string is just text) but scanned for the
     * template feature.
     */
    fun detect(expr: String): Set<Feature> {
        val features = mutableSetOf<Feature>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                c == '"' -> i = scanStringLiteral(expr, i, features)
                c == '\'' -> i = scanCharLiteral(expr, i)
                c == '?' && i + 1 < expr.length && expr[i + 1] == '.' -> {
                    features += Feature.SAFE_CALL
                    i += 2
                }
                c == '?' && i + 1 < expr.length && expr[i + 1] == ':' -> {
                    features += Feature.ELVIS
                    i += 2
                }
                c == '!' && i + 1 < expr.length && expr[i + 1] == '!' -> {
                    features += Feature.NOT_NULL
                    i += 2
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < expr.length && (expr[i].isLetterOrDigit() || expr[i] == '_')) i++
                    val word = expr.substring(start, i)
                    when (word) {
                        "as" -> if (hasLeftOperand(expr, start) && hasRightOperand(expr, i)) {
                            features += Feature.AS_CAST
                        }
                        "is" -> if (hasLeftOperand(expr, start) && hasRightOperand(expr, i)) {
                            features += Feature.IS_CHECK
                        }
                    }
                }
                else -> i++
            }
        }
        return features
    }

    /**
     * Scan a double-quoted literal starting at [start]; returns the index
     * just past the closing quote. Template markers inside are reported.
     */
    private fun scanStringLiteral(expr: String, start: Int, features: MutableSet<Feature>): Int {
        var i = start + 1
        while (i < expr.length) {
            val c = expr[i]
            when {
                c == '\\' -> i += 2
                c == '"' -> return i + 1
                c == '$' && i + 1 < expr.length && (expr[i + 1].isLetter() || expr[i + 1] == '_') -> {
                    features += Feature.TEMPLATE
                    i++
                }
                c == '$' && i + 1 < expr.length && expr[i + 1] == '{' -> {
                    features += Feature.TEMPLATE
                    i++
                }
                else -> i++
            }
        }
        return expr.length
    }

    /** Scan a single-quoted literal; returns the index past the closing quote. */
    private fun scanCharLiteral(expr: String, start: Int): Int {
        var i = start + 1
        while (i < expr.length) {
            when (expr[i]) {
                '\\' -> i += 2
                '\'' -> return i + 1
                else -> i++
            }
        }
        return expr.length
    }

    /**
     * Whether the token ending at [wordStart] has something to its left —
     * an operator position (`x as T`), not the start of the expression
     * (where `as` / `is` would be plain identifiers).
     */
    private fun hasLeftOperand(expr: String, wordStart: Int): Boolean {
        var i = wordStart - 1
        while (i >= 0 && expr[i].isWhitespace()) i--
        if (i < 0) return false
        val c = expr[i]
        return c.isLetterOrDigit() || c == '_' || c == ')' || c == ']' || c == '"'
    }

    /**
     * Whether a type name follows at [wordEnd] — an operator position
     * (`x as T`), not a bare identifier usage.
     */
    private fun hasRightOperand(expr: String, wordEnd: Int): Boolean {
        var i = wordEnd
        // `as?` — the safe cast marker sits right after the keyword.
        if (i < expr.length && expr[i] == '?') i++
        while (i < expr.length && expr[i].isWhitespace()) i++
        if (i >= expr.length) return false
        val c = expr[i]
        return c.isLetter() || c == '_'
    }
}
