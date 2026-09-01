package com.liux.android.debugger_adapter.jdi

import com.liux.android.debugger_adapter.parser.Evaluator
import com.liux.android.debugger_adapter.parser.ExprNode
import com.liux.android.debugger_adapter.parser.Lexer
import com.liux.android.debugger_adapter.parser.Parser
import com.liux.android.debugger_adapter.protocol.Logging
import com.sun.jdi.ObjectReference
import com.sun.jdi.StackFrame
import com.sun.jdi.Value

/**
 * Public entry point for expression evaluation used by the adapter.
 *
 * Delegates the heavy lifting to the recursive-descent parser in
 * `com.liux.android.debugger_adapter.parser`. This file only keeps the two helpers
 * the rest of the adapter calls: [evaluate] for log-message interpolation
 * and [evaluateExpression] / [resolveExpression] for direct evaluation.
 */
class ExpressionEvaluator {

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
            val node = Parser(Lexer(cleaned).tokenize()).parse()
            val value = Evaluator(frame).eval(node)
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
        val node = Parser(Lexer(cleaned).tokenize()).parse()
        return Evaluator(frame).eval(node)
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
