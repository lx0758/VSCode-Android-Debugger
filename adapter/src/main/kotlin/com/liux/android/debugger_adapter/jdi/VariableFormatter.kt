package com.liux.android.debugger_adapter.jdi

import com.liux.android.debugger_adapter.protocol.Logging
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ArrayReference
import com.sun.jdi.Field
import com.sun.jdi.LocalVariable
import com.sun.jdi.ObjectReference
import com.sun.jdi.StackFrame
import com.sun.jdi.Value
import java.util.concurrent.atomic.AtomicInteger

class VariableFormatter {

    private val varRefSeq = AtomicInteger(1000)
    private val expandableRefs = HashMap<Int, Value>()

    fun formatLocals(frame: StackFrame): List<VarEntry> {
        val result = mutableListOf<VarEntry>()
        try {
            for (v in frame.visibleVariables()) {
                val value = frame.getValue(v)
                val formatted = formatValue(value)
                val ref = if (isExpandable(value)) registerExpandable(value) else 0
                result += VarEntry(v.name(), formatted, ref, v.typeName())
            }
            val thisObj = frame.thisObject()
            if (thisObj != null) {
                val formatted = formatValue(thisObj)
                val ref = registerExpandable(thisObj)
                result += VarEntry("this", formatted, ref, thisObj.referenceType().name())
            }
        } catch (e: AbsentInformationException) {
            Logging.debug("无变量信息 (需 -g 编译)")
        }
        return result
    }

    fun expand(ref: Int): List<VarEntry> {
        val value = expandableRefs[ref] ?: return emptyList()
        return when (value) {
            is ArrayReference -> expandArray(value)
            is ObjectReference -> expandObject(value)
            else -> emptyList()
        }
    }

    /**
     * Look up the original `Value` for a variable reference. Used by the
     * DAP `setVariable` handler so it can call `setValue` on the
     * underlying ObjectReference / ArrayReference / primitive box.
     * Returns null if the ref has been cleared (e.g. after a `continue`).
     */
    fun resolveValue(ref: Int): Value? = expandableRefs[ref]

    private fun expandArray(arr: ArrayReference): List<VarEntry> {
        val result = mutableListOf<VarEntry>()
        val len = arr.length()
        val typeName = arr.type().name()
        for (i in 0 until len) {
            val v = arr.getValue(i)
            val formatted = formatValue(v)
            val ref = if (isExpandable(v)) registerExpandable(v) else 0
            result += VarEntry("[$i]", formatted, ref, typeName)
        }
        return result
    }

    private fun expandObject(obj: ObjectReference): List<VarEntry> {
        val result = mutableListOf<VarEntry>()
        val refType = try {
            obj.referenceType()
        } catch (e: Exception) {
            return emptyList()
        }
        for (field in refType.visibleFields()) {
            val v = try {
                obj.getValue(field)
            } catch (e: Exception) {
                null
            }
            val formatted = formatValue(v)
            val ref = if (isExpandable(v)) registerExpandable(v!!) else 0
            result += VarEntry(field.name(), formatted, ref, field.typeName())
        }
        return result
    }

    fun registerExpandable(value: Value): Int {
        val ref = varRefSeq.incrementAndGet()
        expandableRefs[ref] = value
        return ref
    }

    fun clear() {
        expandableRefs.clear()
    }

    private fun formatValue(value: Value?): String {
        if (value == null) return "null"
        if (value is ArrayReference) {
            val arr = value
            val sb = StringBuilder(arr.type().name())
            val len = arr.length()
            sb.append("[").append(len).append("]")
            if (len > 0 && len <= 20) {
                sb.append(" {")
                for (i in 0 until len) {
                    if (i > 0) sb.append(", ")
                    sb.append(formatValue(arr.getValue(i)))
                }
                sb.append("}")
            }
            return sb.toString()
        }
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

    private fun isExpandable(value: Value?): Boolean {
        if (value == null) return false
        if (value is ArrayReference) return value.length() > 0
        if (value is ObjectReference) {
            return try { value.referenceType().visibleFields().isNotEmpty() } catch (_: Exception) { false }
        }
        return false
    }

    data class VarEntry(
        val name: String,
        val value: String,
        val variablesReference: Int,
        val type: String
    )
}
