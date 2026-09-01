package com.liux.android.debugger_adapter.parser

import com.liux.android.debugger_adapter.protocol.Logging
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ArrayReference
import com.sun.jdi.BooleanValue
import com.sun.jdi.ByteValue
import com.sun.jdi.CharValue
import com.sun.jdi.ClassType
import com.sun.jdi.DoubleValue
import com.sun.jdi.Field
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerValue
import com.sun.jdi.InvocationException
import com.sun.jdi.LongValue
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.PrimitiveValue
import com.sun.jdi.ReferenceType
import com.sun.jdi.ShortValue
import com.sun.jdi.StackFrame
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.Type
import com.sun.jdi.Value
import com.sun.jdi.VirtualMachine

/**
 * Walks an [ExprNode] tree against the paused target VM, returning the
 * JDI [Value] for the result. Used by `com.liux.android.debugger_adapter.jdi.ExpressionEvaluator`
 * for everything beyond simple variable / field access.
 */
internal class Evaluator(
    private val frame: StackFrame,
    private val vm: VirtualMachine = frame.virtualMachine(),
    private val thread: ThreadReference = frame.thread(),
) {

    fun eval(node: ExprNode): Value? = when (node) {
        is ExprNode.NumberLit -> parseNumber(node.text)
        is ExprNode.StringLit -> parseString(node.text)
        is ExprNode.CharLit -> vm.mirrorOf(node.code.toChar())
        is ExprNode.NullLit -> null
        is ExprNode.TrueLit -> vm.mirrorOf(true)
        is ExprNode.FalseLit -> vm.mirrorOf(false)
        is ExprNode.ThisRef -> frame.thisObject()
            ?: throw IllegalArgumentException("'this' 在静态上下文中为 null")
        is ExprNode.Var -> evalVar(node.name)
        is ExprNode.Field -> evalField(eval(node.target), node.name)
        is ExprNode.Call -> evalCall(node)
        is ExprNode.Index -> evalIndex(eval(node.target), eval(node.index))
        is ExprNode.New -> evalNew(node)
        is ExprNode.BinaryOp -> evalBinaryOp(node)
        is ExprNode.UnaryOp -> evalUnaryOp(node)
        is ExprNode.Ternary -> {
            val cond = eval(node.cond)
            if (isTruthy(cond)) eval(node.thenBranch) else eval(node.elseBranch)
        }
        is ExprNode.InstanceOf -> vm.mirrorOf(evalInstanceOf(node))
        is ExprNode.Assign -> evalAssign(node)
    }

    // ---------- Literals --------------------------------------------------

    private fun parseNumber(text: String): Value {
        return try {
            when {
                text.endsWith("f") || text.endsWith("F") -> vm.mirrorOf(text.dropLast(1).toFloat())
                text.endsWith("d") || text.endsWith("D") -> vm.mirrorOf(text.dropLast(1).toDouble())
                text.endsWith("l") || text.endsWith("L") -> vm.mirrorOf(text.dropLast(1).toLong())
                '.' in text -> vm.mirrorOf(text.toDouble())
                else -> {
                    // Default to Long for integers so big literals like
                    // 12345678901 don't silently overflow Int.
                    val v = text.toLong()
                    if (v in Int.MIN_VALUE..Int.MAX_VALUE) vm.mirrorOf(v.toInt())
                    else vm.mirrorOf(v)
                }
            }
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("无法解析数字: $text")
        }
    }

    private fun parseString(text: String): StringReference {
        // text still has surrounding quotes. Strip them and unescape the
        // common escape sequences.
        val inner = text.substring(1, text.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (inner[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    '0' -> sb.append('\u0000')
                    else -> sb.append(inner[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return vm.mirrorOf(sb.toString())
    }

    // ---------- Variable / field -----------------------------------------

    private fun evalVar(name: String): Value? {
        val v = findLocalOrParam(name)
        if (v == null) throw IllegalArgumentException("variable not found: $name")
        return v
    }

    private fun findLocalOrParam(name: String): Value? {
        return try {
            frame.visibleVariables().firstOrNull { it.name() == name }?.let { frame.getValue(it) }
        } catch (e: AbsentInformationException) {
            throw IllegalArgumentException("没有局部变量信息 (请用 -g 编译)")
        }
    }

    private fun evalField(target: Value?, name: String): Value {
        val obj = target as? ObjectReference
            ?: throw IllegalArgumentException(
                "无法访问字段 '$name' on ${target?.type()?.name() ?: "null"}"
            )
        val type = obj.referenceType()
        val field: Field? = type.allFields().firstOrNull { it.name() == name }
            ?: throw IllegalArgumentException("field '$name' not found on ${type.name()}")
        return obj.getValue(field)
    }

    // ---------- Method call ----------------------------------------------

    private fun evalCall(node: ExprNode.Call): Value? {
        val args = node.args.map { eval(it) }

        // Static method call heuristic: target is a Var whose name starts
        // with an uppercase letter AND resolves to a known class. If the
        // class lookup fails, fall through to the instance path so users
        // can still access static-looking instance fields.
        if (node.target is ExprNode.Var) {
            val name = node.target.name
            if (name.isNotEmpty() && name[0].isUpperCase()) {
                val classType = resolveClass(name)
                if (classType != null) {
                    val method = findMatchingMethod(classType, node.name, args, staticOnly = true)
                        ?: throw IllegalArgumentException(
                            "静态方法 '${node.name}' 未找到于 $name (参数 ${args.size} 个)"
                        )
                    return invokeStatic(classType, method, args)
                }
            }
        }

        val target = eval(node.target) ?: throw IllegalArgumentException("方法调用目标为 null")
        val obj = target as? ObjectReference
            ?: throw IllegalArgumentException("无法在 ${target.type().name()} 上调用方法")
        val refType = obj.referenceType()
        val method = findMatchingMethod(refType, node.name, args)
            ?: throw IllegalArgumentException(
                "方法 '${node.name}' 未找到于 ${refType.name()} (参数 ${args.size} 个)"
            )
        return invokeInstance(obj, method, args)
    }

    private fun invokeInstance(obj: ObjectReference, method: Method, args: List<Value?>): Value? {
        return try {
            obj.invokeMethod(thread, method, args, ObjectReference.INVOKE_SINGLE_THREADED)
        } catch (ie: InvocationException) {
            throw IllegalArgumentException(
                "方法 '${method.name()}' 抛出: ${ie.exception().referenceType().name()}", ie
            )
        }
    }

    private fun invokeStatic(cls: ClassType, method: Method, args: List<Value?>): Value? {
        return try {
            cls.invokeMethod(thread, method, args, ObjectReference.INVOKE_SINGLE_THREADED)
        } catch (ie: InvocationException) {
            throw IllegalArgumentException(
                "静态方法 '${method.name()}' 抛出: ${ie.exception().referenceType().name()}", ie
            )
        }
    }

    /**
     * Find a method on `type` matching `name` and the runtime arg types.
     * Strategy: filter by name + arg count, then prefer exact match, then
     * allow primitive widening, then fall back to the first candidate
     * (let `invokeMethod` raise a clearer error if types still mismatch).
     */
    private fun findMatchingMethod(
        type: ReferenceType,
        name: String,
        args: List<Value?>,
        staticOnly: Boolean = false,
    ): Method? {
        val candidates = type.allMethods().filter {
            it.name() == name &&
                it.argumentTypes().size == args.size &&
                (!staticOnly || it.isStatic())
        }
        for (m in candidates) {
            if (argsMatch(m.argumentTypes(), args, widening = false)) return m
        }
        for (m in candidates) {
            if (argsMatch(m.argumentTypes(), args, widening = true)) return m
        }
        return candidates.firstOrNull()
    }

    private fun argsMatch(formal: List<Type>, actual: List<Value?>, widening: Boolean): Boolean {
        if (formal.size != actual.size) return false
        for (i in formal.indices) {
            if (!typeMatches(formal[i], actual[i], widening)) return false
        }
        return true
    }

    private fun typeMatches(formal: Type, actual: Value?, widening: Boolean): Boolean {
        if (actual == null) return !isPrimitiveName(formal.name())
        val a = actual.type().name()
        val f = formal.name()
        if (a == f) return true
        if (!widening) return false
        return wideningPath(a, f)
    }

    private fun wideningPath(from: String, to: String): Boolean = when (from) {
        "byte" -> to in setOf("short", "int", "long", "float", "double")
        "short" -> to in setOf("int", "long", "float", "double")
        "char" -> to in setOf("int", "long", "float", "double")
        "int" -> to in setOf("long", "float", "double")
        "long" -> to in setOf("float", "double")
        "float" -> to == "double"
        else -> false
    }

    private fun resolveClass(name: String): ClassType? {
        return vm.classesByName(name).firstOrNull() as? ClassType
    }

    // ---------- new X(args) ----------------------------------------------

    private fun evalNew(node: ExprNode.New): Value {
        val classType = resolveClass(node.className)
            ?: throw IllegalArgumentException("找不到类: ${node.className}")
        val args = node.args.map { eval(it) }
        val ctor = findMatchingMethod(classType, "<init>", args)
            ?: throw IllegalArgumentException(
                "${node.className} 没有匹配的构造器 (参数 ${args.size} 个)"
            )
        return try {
            classType.newInstance(thread, ctor, args, ObjectReference.INVOKE_SINGLE_THREADED)
        } catch (ie: InvocationException) {
            throw IllegalArgumentException(
                "${node.className} 构造器抛出: ${ie.exception().referenceType().name()}", ie
            )
        }
    }

    // ---------- arr[i] ---------------------------------------------------

    private fun evalIndex(target: Value?, idx: Value?): Value {
        val arr = target as? ArrayReference
            ?: throw IllegalArgumentException("无法索引非数组类型: ${target?.type()?.name() ?: "null"}")
        val intVal = idx as? IntegerValue
            ?: throw IllegalArgumentException("数组下标必须是 int, 实际是 ${idx?.type()?.name() ?: "null"}")
        return arr.getValue(intVal.value())
    }

    // ---------- Binary / unary operators ---------------------------------

    private fun evalBinaryOp(node: ExprNode.BinaryOp): Value? = when (node.op) {
        "&&" -> {
            // Short-circuit: only evaluate RHS when LHS is truthy.
            val l = eval(node.left)
            if (!isTruthy(l)) vm.mirrorOf(false) else vm.mirrorOf(isTruthy(eval(node.right)))
        }
        "||" -> {
            val l = eval(node.left)
            if (isTruthy(l)) vm.mirrorOf(true) else vm.mirrorOf(isTruthy(eval(node.right)))
        }
        else -> {
            val l = eval(node.left)
            val r = eval(node.right)
            applyOp(node.op, l, r)
        }
    }

    private fun evalUnaryOp(node: ExprNode.UnaryOp): Value? {
        val v = eval(node.operand)
        return when (node.op) {
            "!" -> vm.mirrorOf(!isTruthy(v))
            "-" -> negate(v)
            "+" -> v
            "~" -> bitwiseNot(v)
            else -> throw IllegalArgumentException("不支持的一元运算符: ${node.op}")
        }
    }

    private fun negate(v: Value?): Value = when (v) {
        is IntegerValue -> vm.mirrorOf(-v.value())
        is LongValue -> vm.mirrorOf(-v.value())
        is ShortValue -> vm.mirrorOf((-v.value()).toShort())
        is ByteValue -> vm.mirrorOf((-v.value()).toByte())
        is FloatValue -> vm.mirrorOf(-v.value())
        is DoubleValue -> vm.mirrorOf(-v.value())
        null -> throw IllegalArgumentException("无法对 null 取负")
        else -> throw IllegalArgumentException("无法对 ${v.type().name()} 取负")
    }

    private fun bitwiseNot(v: Value?): Value = when (v) {
        is IntegerValue -> vm.mirrorOf(v.value().inv())
        is LongValue -> vm.mirrorOf(v.value().inv())
        is ShortValue -> vm.mirrorOf(v.value().toInt().inv().toShort())
        is ByteValue -> vm.mirrorOf(v.value().toInt().inv().toByte())
        null -> throw IllegalArgumentException("无法对 null 取反")
        else -> throw IllegalArgumentException("无法对 ${v.type().name()} 取反")
    }

    private fun applyOp(op: String, a: Value?, b: Value?): Value {
        // String concatenation wins over numeric +.
        if (op == "+" && (a is StringReference || b is StringReference)) {
            return vm.mirrorOf(formatForConcat(a) + formatForConcat(b))
        }
        if (a == null || b == null) {
            return when (op) {
                "==" -> vm.mirrorOf(a == b)
                "!=" -> vm.mirrorOf(a != b)
                else -> throw IllegalArgumentException("运算符 '$op' 不接受 null 操作数")
            }
        }
        return when (op) {
            "+", "-", "*", "/", "%" -> numericOp(a, b, op)
            "==" -> vm.mirrorOf(primitiveOrRefEquals(a, b))
            "!=" -> vm.mirrorOf(!primitiveOrRefEquals(a, b))
            "<", ">", "<=", ">=" -> compareOp(op, a, b)
            else -> throw IllegalArgumentException("不支持的二元运算符: $op")
        }
    }

    private fun numericOp(a: Value, b: Value, op: String): Value {
        val pa = a as? PrimitiveValue
            ?: throw IllegalArgumentException("运算符 $op 不能用于非基本类型 ${a.type().name()}")
        val pb = b as? PrimitiveValue
            ?: throw IllegalArgumentException("运算符 $op 不能用于非基本类型 ${b.type().name()}")
        // Promote to widest of the two operand types.
        if (pa is DoubleValue || pb is DoubleValue) {
            return vm.mirrorOf(doubleOp(pa.doubleValue(), pb.doubleValue(), op))
        }
        if (pa is FloatValue || pb is FloatValue) {
            return vm.mirrorOf(floatOp(pa.floatValue(), pb.floatValue(), op))
        }
        val x = asLong(pa)
        val y = asLong(pb)
        if (y == 0L && (op == "/" || op == "%")) throw IllegalArgumentException("${op} 零")
        val r = longOp(x, y, op)
        // Preserve Int when result fits AND both operands are Int-sized
        // (don't accidentally widen a small literal result to Long).
        val bothIntSized = (pa is IntegerValue || pa is ShortValue || pa is ByteValue || pa is CharValue) &&
            (pb is IntegerValue || pb is ShortValue || pb is ByteValue || pb is CharValue)
        return when {
            bothIntSized && r in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> vm.mirrorOf(r.toInt())
            else -> vm.mirrorOf(r)
        }
    }

    private fun doubleOp(x: Double, y: Double, op: String): Double = when (op) {
        "+" -> x + y; "-" -> x - y; "*" -> x * y
        "/" -> if (y == 0.0) throw IllegalArgumentException("除零") else x / y
        "%" -> if (y == 0.0) throw IllegalArgumentException("模零") else x % y
        else -> throw IllegalArgumentException("不支持 $op 于 double")
    }

    private fun floatOp(x: Float, y: Float, op: String): Float = when (op) {
        "+" -> x + y; "-" -> x - y; "*" -> x * y
        "/" -> if (y == 0.0f) throw IllegalArgumentException("除零") else x / y
        "%" -> if (y == 0.0f) throw IllegalArgumentException("模零") else x % y
        else -> throw IllegalArgumentException("不支持 $op 于 float")
    }

    private fun longOp(x: Long, y: Long, op: String): Long = when (op) {
        "+" -> x + y; "-" -> x - y; "*" -> x * y
        "/" -> x / y; "%" -> x % y
        else -> throw IllegalArgumentException("不支持 $op 于 long")
    }

    private fun compareOp(op: String, a: Value, b: Value): Value {
        // String compare
        if (a is StringReference && b is StringReference) {
            val cmp = a.value().compareTo(b.value())
            return vm.mirrorOf(when (op) {
                "<" -> cmp < 0; ">" -> cmp > 0; "<=" -> cmp <= 0; ">=" -> cmp >= 0
                else -> throw IllegalArgumentException("不支持 $op 于 String")
            })
        }
        // Numeric compare
        if (a is PrimitiveValue && b is PrimitiveValue) {
            val cmp: Int = when {
                a is DoubleValue || b is DoubleValue -> a.doubleValue().compareTo(b.doubleValue())
                a is FloatValue || b is FloatValue -> a.floatValue().compareTo(b.floatValue())
                else -> asLong(a).compareTo(asLong(b))
            }
            return vm.mirrorOf(when (op) {
                "<" -> cmp < 0; ">" -> cmp > 0; "<=" -> cmp <= 0; ">=" -> cmp >= 0
                else -> throw IllegalArgumentException("不支持 $op")
            })
        }
        // Fallback: compare by string representation.
        val cmp = a.toString().compareTo(b.toString())
        return vm.mirrorOf(when (op) {
            "<" -> cmp < 0; ">" -> cmp > 0; "<=" -> cmp <= 0; ">=" -> cmp >= 0
            else -> throw IllegalArgumentException("不支持 $op 于 ${a.type().name()} / ${b.type().name()}")
        })
    }

    private fun primitiveOrRefEquals(a: Value, b: Value): Boolean {
        if (a is PrimitiveValue && b is PrimitiveValue) {
            if (a.type().name() == b.type().name()) {
                return when (a) {
                    is BooleanValue -> b is BooleanValue && a.value() == b.value()
                    is IntegerValue -> b is IntegerValue && a.value() == b.value()
                    is LongValue -> b is LongValue && a.value() == b.value()
                    is ShortValue -> b is ShortValue && a.value() == b.value()
                    is ByteValue -> b is ByteValue && a.value() == b.value()
                    is CharValue -> b is CharValue && a.value() == b.value()
                    is FloatValue -> b is FloatValue && a.value() == b.value()
                    is DoubleValue -> b is DoubleValue && a.value() == b.value()
                    else -> a == b
                }
            }
            // Mixed primitive types — widen both to Long for comparison.
            return asLong(a) == asLong(b)
        }
        // Reference equality (Java `==`).
        return a == b
    }

    private fun asLong(v: Value): Long = when (v) {
        is IntegerValue -> v.value().toLong()
        is LongValue -> v.value()
        is ShortValue -> v.value().toLong()
        is ByteValue -> v.value().toLong()
        is CharValue -> v.value().code.toLong()
        is BooleanValue -> if (v.value()) 1L else 0L
        else -> throw IllegalArgumentException("不是数值类型: ${v.type().name()}")
    }

    private fun formatForConcat(v: Value?): String = when (v) {
        null -> "null"
        is StringReference -> v.value()
        is CharValue -> v.value().toString()
        else -> v.toString()
    }

    // ---------- instanceof ------------------------------------------------

    private fun evalInstanceOf(node: ExprNode.InstanceOf): Boolean {
        val target = eval(node.target) ?: return false
        val fromName = target.type().name()
        if (fromName == node.typeName) return true
        val fromType = vm.classesByName(fromName).firstOrNull() ?: return false
        if (fromType.name() == node.typeName) return true
        var current: ClassType? = fromType as? ClassType
        while (current != null) {
            if (current.name() == node.typeName) return true
            current = current.superclass()
        }
        // allInterfaces() lives on ClassType, not ReferenceType, so cast.
        val cls = fromType as? ClassType
        if (cls != null) {
            for (iface in cls.allInterfaces()) {
                if (iface.name() == node.typeName) return true
            }
        }
        return false
    }

    // ---------- Assignment ------------------------------------------------

    private fun evalAssign(node: ExprNode.Assign): Value? {
        val newValue = eval(node.value)
        when (val target = node.target) {
            is ExprNode.Var -> {
                val local = try {
                    frame.visibleVariables().firstOrNull { it.name() == target.name }
                } catch (e: AbsentInformationException) {
                    throw IllegalArgumentException("没有局部变量信息 (请用 -g 编译)")
                } ?: throw IllegalArgumentException("variable not found: ${target.name}")
                try {
                    frame.setValue(local, newValue)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "无法写入局部变量 '${target.name}' (final / 合成 / 参数槽位?). ${e.message}"
                    )
                }
                Logging.info("[Eval] evaluate: 局部变量 ${target.name} = $newValue")
                return newValue
            }
            is ExprNode.Field -> {
                val obj = eval(target.target) as? ObjectReference
                    ?: throw IllegalArgumentException("字段赋值目标不是对象")
                val type = obj.referenceType()
                val field = type.allFields().firstOrNull { it.name() == target.name }
                    ?: throw IllegalArgumentException("field '${target.name}' not found on ${type.name()}")
                try {
                    obj.setValue(field, newValue)
                } catch (e: com.sun.jdi.InvalidTypeException) {
                    val kind = if (field.isStatic) "static final" else "final"
                    throw IllegalArgumentException(
                        "无法修改 ${kind} 字段 '${type.name()}.${target.name}'. ${e.message}"
                    )
                }
                Logging.info("[Eval] evaluate: 字段 ${type.name()}.${target.name} = $newValue")
                return newValue
            }
            is ExprNode.Index -> {
                val arr = eval(target.target) as? ArrayReference
                    ?: throw IllegalArgumentException("数组元素赋值目标不是数组")
                val idx = eval(target.index) as? IntegerValue
                    ?: throw IllegalArgumentException("数组下标必须是 int")
                arr.setValue(idx.value(), newValue)
                return newValue
            }
            else -> throw IllegalArgumentException("无效的赋值目标: $target")
        }
    }

    // ---------- Truthiness -----------------------------------------------

    private fun isTruthy(v: Value?): Boolean = when (v) {
        null -> false
        is BooleanValue -> v.value()
        is IntegerValue -> v.value() != 0
        is LongValue -> v.value() != 0L
        is ShortValue -> v.value() != 0.toShort()
        is ByteValue -> v.value() != 0.toByte()
        is FloatValue -> v.value() != 0.0f
        is DoubleValue -> v.value() != 0.0
        is CharValue -> v.value() != 0.toChar()
        is ObjectReference -> true
        else -> true
    }

    private fun isPrimitiveName(name: String): Boolean =
        name in setOf("byte", "short", "int", "long", "char", "float", "double", "boolean")
}
