package com.liux.android.debugger_adapter.jdi

import com.liux.android.debugger_adapter.eval.FrontendSelector
import com.liux.android.debugger_adapter.eval.LanguageDetector
import com.liux.android.debugger_adapter.eval.RuntimeEvaluator
import com.liux.android.debugger_adapter.eval.common.ExprNode
import com.liux.android.debugger_adapter.protocol.Logging
import com.liux.android.debugger_adapter.source.SourceIndex
import com.sun.jdi.ObjectReference
import com.sun.jdi.StackFrame
import com.sun.jdi.Value

/**
 * Public entry point for expression evaluation used by the adapter.
 *
 * Acts as the language dispatcher (D2/D6): the expression is routed to
 * the frontend that can actually parse it — JDT for Java
 * (`com.liux.android.debugger_adapter.eval.JavaFrontend`), the
 * recursive-descent parser for Kotlin — see [FrontendSelector]. The
 * parsed [ExprNode] IR is handed to [RuntimeEvaluator]. This file keeps
 * the helpers the rest of the adapter calls: [evaluate] for log-message
 * interpolation and [evaluateExpression] / [resolveExpression] for direct
 * evaluation.
 */
class ExpressionEvaluator(
    /** Adapter-side source index; drives language detection and name resolution. */
    private val sourceIndex: SourceIndex,
) {

    /**
     * Evaluates a log-message template containing `{expr}` placeholders.
     * Each `{expr}` is evaluated in the frame context and the placeholder
     * is replaced with its string representation. Plain text is passed
     * through unchanged.
     */
    fun evaluate(template: String, frame: StackFrame): String {
        if (template.isEmpty()) return ""
        val sb = StringBuilder()
        val regex = Regex("\\{([^}]+)\\}")
        var lastEnd = 0
        for (m in regex.findAll(template)) {
            sb.append(template, lastEnd, m.range.first)
            val expr = m.groupValues[1].trim()
            sb.append(evaluateExpression(expr, frame))
            lastEnd = m.range.last + 1
        }
        sb.append(template, lastEnd, template.length)
        return sb.toString()
    }

    /**
     * Evaluate a single expression and return its string representation.
     * Mirrors the old API so existing log-point / breakpoint call sites
     * keep working unchanged.
     */
    fun evaluateExpression(expr: String, frame: StackFrame): String {
        return try {
            val cleaned = expr.trim().trimEnd(';').trim()
            if (cleaned.isEmpty()) return ""
            val value = RuntimeEvaluator(frame, sourceIndex = sourceIndex).eval(parse(cleaned, frame))
            formatValue(value)
        } catch (t: Throwable) {
            Logging.warn("[Eval] 表达式求值失败: $expr - ${t.message}")
            "<error: ${t.javaClass.simpleName}: ${t.message ?: ""}>"
        }
    }

    /**
     * Lower-level entry point: evaluate and return the raw JDI [Value].
     * Throws on error (caller is responsible for error handling).
     */
    fun resolveExpression(expr: String, frame: StackFrame): Value? {
        val cleaned = expr.trim().trimEnd(';').trim()
        if (cleaned.isEmpty()) return null
        return RuntimeEvaluator(frame, sourceIndex = sourceIndex).eval(parse(cleaned, frame))
    }

    /**
     * Single evaluation pass for the DAP `evaluate` handler: returns both
     * the raw JDI [Value] (for `variablesReference` registration, D30) and
     * its display string. Errors are reported inline as `<error: ...>` —
     * same presentation as [evaluateExpression] — so Watch/REPL surface
     * failures without a DAP error response. The expression is evaluated
     * exactly once (side-effecting expressions must not run twice).
     */
    fun evaluateForResponse(expr: String, frame: StackFrame): Pair<Value?, String> {
        return try {
            val value = resolveExpression(expr, frame)
            value to (value?.let { formatValue(it) } ?: "null")
        } catch (t: Throwable) {
            Logging.warn("[Eval] 表达式求值失败: $expr - ${t.message}")
            null to "<error: ${t.javaClass.simpleName}: ${t.message ?: ""}>"
        }
    }

    /**
     * Frontend dispatch: pick the frontend that can parse the expression
     * and parse it into the shared IR.
     *
     * The choice is driven by the syntax of the expression (Kotlin-only
     * shapes go to the Kotlin parser, everything else to JDT), not by the
     * language of the frame — see [FrontendSelector]. The frame's language
     * is still consulted for one thing: a `$name` inside a string literal
     * is a Kotlin template only in a Kotlin file.
     */
    private fun parse(expr: String, frame: StackFrame): ExprNode {
        val language = LanguageDetector.detectLanguage(frame, sourceIndex)
        return FrontendSelector.parse(expr, language)
    }

    /**
     * Render a JDI Value into the human-readable string shown in DAP
     * `evaluate` results and breakpoint log messages.
     */
    private fun formatValue(value: Value?): String {
        if (value == null) return "null"
        if (value is ObjectReference) {
            var str = value.toString()
            if (str.startsWith("instance of ")) str = str.substring("instance of ".length)
            val brace = str.indexOf('(')
            val braceEnd = str.lastIndexOf(')')
            if (brace > 0 && braceEnd > brace && str.substring(brace + 1, braceEnd).startsWith("id=")) {
                str = str.substring(0, brace)
            }
            return str
        }
        return value.toString()
    }
}
