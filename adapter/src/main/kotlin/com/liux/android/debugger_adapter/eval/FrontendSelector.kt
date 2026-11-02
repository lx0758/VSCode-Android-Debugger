package com.liux.android.debugger_adapter.eval

import com.liux.android.debugger_adapter.eval.common.ExprNode
import com.liux.android.debugger_adapter.eval.kotlin.Lexer
import com.liux.android.debugger_adapter.eval.kotlin.Parser
import com.liux.android.debugger_adapter.protocol.Logging

/** The two expression frontends the adapter can pick from. */
internal enum class Frontend { JAVA, KOTLIN }

/**
 * Picks the frontend that parses an expression.
 *
 * The choice is driven by the *syntax* of the expression, not by the
 * language of the stack frame:
 *
 *  - Kotlin-only shapes (`?.`, `!!`, `as` / `as?`, `?:`, `is`) have no
 *    Java spelling and are rejected by JDT outright, so they must go to
 *    the Kotlin parser.
 *  - Everything else — casts, `new`, array allocation, class literals,
 *    fully-qualified references — is handled better by JDT, even inside a
 *    Kotlin frame. The Kotlin parser has no notion of a Java-style cast
 *    and mis-reads package-qualified names as a field chain
 *    (`java.util.Objects.isNull(x)` becomes `java` → `util` → `Objects`),
 *    which fails silently instead of reporting a syntax error.
 *
 * The frame language still matters in one place: a `$name` inside a
 * string literal is a Kotlin template only in a Kotlin file. In a Java
 * file the same text is a plain literal, so the template does not count
 * as Kotlin-only syntax there.
 *
 * If the selected frontend cannot parse the expression, the other one is
 * tried as a fallback (the two frontends accept overlapping but
 * different grammars). When neither can parse it, the error of the
 * *selected* frontend is reported — it describes the syntax the user
 * actually wrote — with the other error attached as a suppressed
 * exception.
 */
internal object FrontendSelector {

    /**
     * The frontend that should parse [expression] first.
     *
     * @param language the language of the stack frame the expression is
     *   evaluated in — only used to decide whether a string template is
     *   Kotlin syntax.
     */
    fun select(expression: String, language: Language): Frontend {
        val features = KotlinSyntaxDetector.detect(expression)
        val kotlinOnly = features.any { it != KotlinSyntaxDetector.Feature.TEMPLATE } ||
            (language == Language.KOTLIN && features.contains(KotlinSyntaxDetector.Feature.TEMPLATE))
        return if (kotlinOnly) Frontend.KOTLIN else Frontend.JAVA
    }

    /**
     * Parse [expression] into the shared IR, falling back to the other
     * frontend when the selected one rejects the syntax.
     */
    fun parse(expression: String, language: Language): ExprNode {
        val primary = select(expression, language)
        val secondary = if (primary == Frontend.JAVA) Frontend.KOTLIN else Frontend.JAVA
        return try {
            parseWith(primary, expression)
        } catch (primaryError: IllegalArgumentException) {
            try {
                Logging.debug {
                    "[Eval] ${primary.name} 前端解析失败, 回退到 ${secondary.name}: ${primaryError.message}"
                }
                parseWith(secondary, expression)
            } catch (secondaryError: IllegalArgumentException) {
                primaryError.addSuppressed(secondaryError)
                throw primaryError
            }
        }
    }

    private fun parseWith(frontend: Frontend, expression: String): ExprNode = when (frontend) {
        Frontend.JAVA -> JavaFrontend.parse(expression)
        Frontend.KOTLIN -> Parser(Lexer(expression).tokenize()).parse()
    }
}
