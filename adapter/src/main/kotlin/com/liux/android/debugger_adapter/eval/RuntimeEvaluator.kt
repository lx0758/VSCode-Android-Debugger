package com.liux.android.debugger_adapter.eval

import com.liux.android.debugger_adapter.eval.common.ExprNode
import com.liux.android.debugger_adapter.jdi.VariableFormatter
import com.liux.android.debugger_adapter.protocol.Logging
import com.sun.jdi.AbsentInformationException
import com.sun.jdi.ArrayReference
import com.sun.jdi.BooleanValue
import com.sun.jdi.ByteValue
import com.sun.jdi.CharValue
import com.sun.jdi.ClassObjectReference
import com.sun.jdi.ClassType
import com.sun.jdi.DoubleValue
import com.sun.jdi.Field
import com.sun.jdi.FloatValue
import com.sun.jdi.IntegerValue
import com.sun.jdi.InvocationException
import com.sun.jdi.LongValue
import com.sun.jdi.Method
import com.sun.jdi.ObjectReference
import com.sun.jdi.PrimitiveType
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
 *
 * Shared runtime layer for both frontends (Java / Kotlin): name resolution
 * goes through [com.liux.android.debugger_adapter.eval.NameResolver] once it
 * lands; imports and the source index ride in as constructor parameters
 * (defaults keep the old single-frame call sites working).
 */
internal class RuntimeEvaluator(
    private val frame: StackFrame,
    private val vm: VirtualMachine = frame.virtualMachine(),
    private val thread: ThreadReference = frame.thread(),
    val imports: List<String> = emptyList(),
    val sourceIndex: com.liux.android.debugger_adapter.source.SourceIndex? = null,
) {
    private val nameResolver = NameResolver(frame, sourceIndex)

    /**
     * Single entry point for type lookups: dotted names go straight to the
     * VM, short names go through the [NameResolver] chain. Every runtime
     * type lookup (class names used as values, `new`, casts, `instanceof`,
     * class literals) funnels through here so a short name and its
     * fully-qualified spelling behave identically.
     */
    private val typeResolver = TypeResolver(
        classesByName = { fqn -> vm.classesByName(fqn) },
        shortNameResolver = { name -> nameResolver.resolveFqn(name) },
    )

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
        is ExprNode.Cast -> evalCast(node)
        is ExprNode.Elvis -> evalElvis(node)
        is ExprNode.ArrayAllocation -> evalArrayAllocation(node)
        is ExprNode.ClassLiteral -> evalClassLiteral(node)
        is ExprNode.ArrayInitializer -> evalArrayInitializer()
        is ExprNode.SafeCall -> evalSafeCall(node)
        is ExprNode.NotNull -> evalNotNull(node)
        is ExprNode.StringTemplate -> evalStringTemplate(node)
    }

    // ---------- Literals --------------------------------------------------

    /**
     * Parse a numeric literal into a target-VM mirror.
     *
     * Supported forms: decimal, hex (`0x1F`), binary (`0b1010`), octal
     * (`0o17`), scientific notation (`1e10`, `1.5E-3`), digit separators
     * (`1_000_000`) and the `L` / `F` / `D` suffixes. Radix-prefixed
     * literals only accept the `L` suffix — in `0xdead` the trailing `d`
     * is a hex digit, not a double marker.
     */
    private fun parseNumber(text: String): Value {
        // Digit separators are legal in Java 7+ and Kotlin.
        val cleaned = text.replace("_", "")
        return try {
            when {
                cleaned.startsWith("0x") || cleaned.startsWith("0X") ->
                    radixLiteral(cleaned.substring(2), 16)
                cleaned.startsWith("0b") || cleaned.startsWith("0B") ->
                    radixLiteral(cleaned.substring(2), 2)
                cleaned.startsWith("0o") || cleaned.startsWith("0O") ->
                    radixLiteral(cleaned.substring(2), 8)
                cleaned.endsWith("f") || cleaned.endsWith("F") -> vm.mirrorOf(cleaned.dropLast(1).toFloat())
                cleaned.endsWith("d") || cleaned.endsWith("D") -> vm.mirrorOf(cleaned.dropLast(1).toDouble())
                cleaned.endsWith("l") || cleaned.endsWith("L") -> vm.mirrorOf(cleaned.dropLast(1).toLong())
                '.' in cleaned || 'e' in cleaned || 'E' in cleaned -> vm.mirrorOf(cleaned.toDouble())
                else -> {
                    // Default to Long for integers so big literals like
                    // 12345678901 don't silently overflow Int.
                    val v = cleaned.toLong()
                    if (v in Int.MIN_VALUE..Int.MAX_VALUE) vm.mirrorOf(v.toInt())
                    else vm.mirrorOf(v)
                }
            }
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("无法解析数字: $text")
        }
    }

    /**
     * Parse the digits of a radix-prefixed literal (`0x` / `0b` / `0o`).
     * Without an `L` suffix the value is narrowed to Int the way Java does
     * (`0xFFFFFFFF` is the int `-1`).
     */
    private fun radixLiteral(digits: String, radix: Int): Value {
        val longSuffix = digits.endsWith("l") || digits.endsWith("L")
        val body = if (longSuffix) digits.dropLast(1) else digits
        val v = body.toLong(radix)
        return when {
            longSuffix -> vm.mirrorOf(v)
            v in 0..0xFFFFFFFFL -> vm.mirrorOf(v.toInt())
            else -> vm.mirrorOf(v)
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
        findLocalOrParam(name)?.let { return it }
        // Not a local — a bare class name (locals shadow class names, per
        // Java scoping). Hand back the class mirror; static member access
        // on it is routed in evalField / evalCall. The name may be a short
        // name (`Objects`) or a fully-qualified one (`java.util.Objects`).
        typeResolver.resolveReferenceType(name)?.let { refType ->
            // Kotlin `object Foo`: a bare name refers to the singleton.
            (refType as? ClassType)?.let { cls ->
                nameResolver.kotlinObjectInstance(cls)?.let { return it }
            }
            return refType.classObject()
        }
        throw IllegalArgumentException("variable not found: $name")
    }

    private fun findLocalOrParam(name: String): Value? {
        return try {
            frame.visibleVariables().firstOrNull { it.name() == name }?.let { frame.getValue(it) }
        } catch (e: AbsentInformationException) {
            throw IllegalArgumentException("没有局部变量信息 (请用 -g 编译)")
        }
    }

    private fun evalField(target: Value?, name: String): Value {
        // Static field access via a class mirror (e.g. Integer.MAX_VALUE,
        // where evalVar resolved the bare name to its class object).
        if (target is ClassObjectReference) {
            val type = target.reflectedType()
            val field = type.allFields().firstOrNull { it.name() == name }
            if (field != null) return type.getValue(field)
            // Kotlin object: fall back to the INSTANCE singleton's fields.
            (type as? ClassType)?.let { cls ->
                nameResolver.kotlinObjectInstance(cls)?.let { instance ->
                    val instanceField = instance.referenceType().allFields()
                        .firstOrNull { it.name() == name }
                        ?: throw IllegalArgumentException("字段 '$name' 未找到于 ${type.name()}")
                    return instance.getValue(instanceField)
                }
            }
            throw IllegalArgumentException("静态字段 '$name' 未找到于 ${type.name()}")
        }
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
        return invokeOn(eval(node.target), node.name, args)
    }

    /**
     * Invoke `name(args)` on an already-evaluated receiver. A receiver
     * that is a class mirror (e.g. the `Math` in `Math.max(...)`, resolved
     * by NameResolver through evalVar) means a static call on the
     * reflected type. For a Kotlin `object` class with no matching static
     * method, the invocation falls through to the `INSTANCE` singleton.
     */
    private fun invokeOn(target: Value?, name: String, args: List<Value?>): Value? {
        if (target is ClassObjectReference) {
            val classType = target.reflectedType() as? ClassType
                ?: throw IllegalArgumentException(
                    "无法在 ${target.reflectedType().name()} 上调用静态方法 '$name'"
                )
            val method = findMatchingMethod(classType, name, args, staticOnly = true)
            if (method != null) return invokeStatic(classType, method, args)
            // `String.class` is a *java.lang.Class instance*, so its own
            // API (getName, getSimpleName, getClassLoader, ...) lives on
            // java.lang.Class, not on the referenced type.
            val classClass = vm.classesByName("java.lang.Class").firstOrNull() as? ClassType
            if (classClass != null) {
                val instanceMethod = findMatchingMethod(classClass, name, args)
                if (instanceMethod != null) return invokeInstance(target, instanceMethod, args)
            }
            // Kotlin object: instance members live on the singleton.
            nameResolver.kotlinObjectInstance(classType)?.let { instance ->
                val instanceMethod = findMatchingMethod(classType, name, args)
                if (instanceMethod != null) return invokeInstance(instance, instanceMethod, args)
            }
            throw IllegalArgumentException(
                "静态方法 '$name' 未找到于 ${classType.name()} (参数 ${args.size} 个)"
            )
        }

        val obj = target as? ObjectReference
            ?: throw IllegalArgumentException(
                "无法在 ${target?.type()?.name() ?: "null"} 上调用方法"
            )
        val refType = obj.referenceType()
        val method = findMatchingMethod(refType, name, args)
            ?: throw IllegalArgumentException(
                "方法 '$name' 未找到于 ${refType.name()} (参数 ${args.size} 个)"
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
     * Selection phases (JLS-inspired, simplified): exact match → primitive
     * widening → boxing/unboxing (widening allowed). Synthetic and bridge
     * methods are filtered out. Multiple applicable candidates in the
     * exact or boxing phase are an explicit ambiguity error listing the
     * candidate signatures; the widening phase keeps first-match-wins.
     */
    internal fun findMatchingMethod(
        type: ReferenceType,
        name: String,
        args: List<Value?>,
        staticOnly: Boolean = false,
    ): Method? {
        val rawCandidates = type.allMethods().filter {
            it.name() == name &&
                it.argumentTypes().size == args.size &&
                (!staticOnly || it.isStatic()) &&
                !it.isSynthetic() && !it.isBridge()
        }
        // `allMethods()` reports a method once per type in the hierarchy
        // that declares it: `String.length()` shows up from `String` *and*
        // from `CharSequence`, `ArrayList.<init>()` shows up four times.
        // Those are not overloads but one signature seen through several
        // supertypes — without collapsing them every such call would be
        // reported as ambiguous.
        val candidates = collapseDuplicates(rawCandidates, type)
        val actualTypes = args.map { it?.type()?.name() }
        val exact = candidates.filter { argsMatchNames(it.argumentTypes().map { t -> t.name() }, actualTypes, widening = false, boxing = false) }
        if (exact.size > 1) throw methodAmbiguity(type, exact)
        if (exact.size == 1) return exact[0]
        val widened = candidates.firstOrNull {
            argsMatchNames(it.argumentTypes().map { t -> t.name() }, actualTypes, widening = true, boxing = false)
        }
        if (widened != null) return widened
        val boxed = candidates.filter {
            argsMatchNames(it.argumentTypes().map { t -> t.name() }, actualTypes, widening = true, boxing = true)
        }
        if (boxed.size > 1) throw methodAmbiguity(type, boxed)
        return boxed.firstOrNull() ?: candidates.firstOrNull()
    }

    /**
     * Collapse candidates that share a signature into the single method
     * the JVM would pick: the one declared on [owner] itself, i.e. the
     * most derived implementation.
     */
    private fun collapseDuplicates(candidates: List<Method>, owner: ReferenceType): List<Method> {
        val ownerName = try { owner.name() } catch (_: Throwable) { null }
        return candidates
            .groupBy { m ->
                val params = m.argumentTypes().joinToString(",") { it.name() }
                "$params|${if (m.isStatic()) "static" else "instance"}"
            }
            .values
            .map { group ->
                group.firstOrNull { declaringTypeName(it) == ownerName && ownerName != null }
                    ?: group.firstOrNull { it.isStatic() }
                    ?: group.first()
            }
    }

    /** `declaringType()` is not always available (test doubles). */
    private fun declaringTypeName(method: Method): String? =
        try { method.declaringType()?.name() } catch (_: Throwable) { null }

    private fun methodAmbiguity(type: ReferenceType, candidates: List<Method>): IllegalArgumentException =
        IllegalArgumentException(
            "方法调用有歧义, 候选: " + candidates.joinToString(", ") { m ->
                "${type.name()}.${m.name()}(" +
                    m.argumentTypes().joinToString(", ") { it.name().substringAfterLast('.') } + ")"
            }
        )

    private fun argsMatch(formal: List<Type>, actual: List<Value?>, widening: Boolean): Boolean =
        argsMatchNames(formal.map { it.name() }, actual.map { it?.type()?.name() }, widening, boxing = false)

    internal fun argsMatchNames(
        formalNames: List<String>,
        actualTypeNames: List<String?>,
        widening: Boolean,
        boxing: Boolean,
    ): Boolean {
        if (formalNames.size != actualTypeNames.size) return false
        for (i in formalNames.indices) {
            if (!typeMatchesNames(formalNames[i], actualTypeNames[i], widening, boxing)) return false
        }
        return true
    }

    private fun typeMatches(formal: Type, actual: Value?, widening: Boolean): Boolean =
        typeMatchesNames(formal.name(), actual?.type()?.name(), widening, boxing = false)

    private fun typeMatchesNames(formal: String, actual: String?, widening: Boolean, boxing: Boolean): Boolean {
        if (actual == null) return !isPrimitiveName(formal)
        if (actual == formal) return true
        if (boxing) {
            // Unboxing: primitive formal + its exact boxed actual.
            if (isPrimitiveName(formal) && !isPrimitiveName(actual) && primitiveToWrapper(formal) == actual) return true
            // Boxing: a primitive actual is applicable to any reference
            // formal (int → Integer → Object/Number/... via reference
            // widening), mirroring the JLS phase-3 rule.
            if (!isPrimitiveName(formal) && isPrimitiveName(actual)) return true
        }
        if (!widening) return false
        return wideningPath(actual, formal)
    }

    private fun primitiveToWrapper(name: String): String = when (name) {
        "boolean" -> "java.lang.Boolean"
        "byte" -> "java.lang.Byte"
        "char" -> "java.lang.Character"
        "short" -> "java.lang.Short"
        "int" -> "java.lang.Integer"
        "long" -> "java.lang.Long"
        "float" -> "java.lang.Float"
        "double" -> "java.lang.Double"
        else -> throw IllegalArgumentException("不是基本类型: $name")
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

    private fun resolveClass(name: String): ClassType? = typeResolver.resolveClassType(name)

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
        return isInstanceOfType(target, node.typeName)
    }

    /**
     * Runtime assignability check shared by `instanceof` and reference casts.
     *
     * The type name is resolved first (short name → FQN via the resolver
     * chain, dotted name → direct VM lookup), then compared against the
     * runtime type's hierarchy. Without that resolution step a cast to a
     * short name (`(String) x`) would be compared literally against
     * `java.lang.String` and always fail.
     *
     * A primitive runtime type and its boxed counterpart are treated as
     * equivalent, so Kotlin's `x is Int` matches both an `int` field and
     * a boxed `java.lang.Integer`.
     */
    private fun isInstanceOfType(target: Value, typeName: String): Boolean {
        val wantedFqn = typeResolver.resolveFqn(typeName) ?: return false
        val fromName = target.type().name()
        if (fromName == wantedFqn) return true
        if (isBoxedEquivalent(fromName, wantedFqn)) return true
        // A primitive value is boxed before it becomes assignable to a
        // reference type, so the hierarchy is walked from the wrapper.
        val effectiveName =
            if (isPrimitiveName(fromName)) primitiveToWrapper(fromName) else fromName
        if (effectiveName == wantedFqn) return true
        val fromType = vm.classesByName(effectiveName).firstOrNull() ?: return false
        if (fromType.name() == wantedFqn) return true
        var current: ClassType? = fromType as? ClassType
        while (current != null) {
            if (current.name() == wantedFqn) return true
            current = current.superclass()
        }
        // allInterfaces() lives on ClassType, not ReferenceType, so cast.
        val cls = fromType as? ClassType
        if (cls != null) {
            for (iface in cls.allInterfaces()) {
                if (iface.name() == wantedFqn) return true
            }
        }
        return false
    }

    /** `int` ↔ `java.lang.Integer` and friends count as the same type. */
    private fun isBoxedEquivalent(fromName: String, wantedFqn: String): Boolean {
        // The runtime type is primitive, the wanted one is its wrapper.
        if (isPrimitiveName(fromName) && primitiveToWrapper(fromName) == wantedFqn) return true
        // The runtime type is a wrapper, the wanted one is the primitive.
        if (isPrimitiveName(wantedFqn) && primitiveToWrapper(wantedFqn) == fromName) return true
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

    // ---------- Extended nodes ---------------------------------------------

    private fun evalCast(node: ExprNode.Cast): Value? {
        val target = eval(node.target)
        if (isPrimitiveName(node.typeName)) {
            val p = target as? PrimitiveValue
                ?: throw IllegalArgumentException(
                    "无法将 ${target?.type()?.name() ?: "null"} 转换为基本类型 ${node.typeName}"
                )
            return when (node.typeName) {
                "int" -> vm.mirrorOf(p.intValue())
                "long" -> vm.mirrorOf(p.longValue())
                "double" -> vm.mirrorOf(p.doubleValue())
                "float" -> vm.mirrorOf(p.floatValue())
                "short" -> vm.mirrorOf(p.shortValue())
                "byte" -> vm.mirrorOf(p.byteValue())
                "char" -> vm.mirrorOf(p.charValue())
                "boolean" -> p
                else -> throw IllegalArgumentException("不支持的基本类型转换: ${node.typeName}")
            }
        }
        // Reference cast: verify assignability, then the value itself is
        // already of the target type at runtime (or we would have thrown).
        if (target == null) return null
        // Resolve the target type first so an unloaded / unknown class is
        // reported as such instead of a bogus ClassCastException.
        val targetFqn = typeResolver.resolveFqn(node.typeName)
            ?: throw IllegalArgumentException("找不到类: ${node.typeName}")
        if (!isInstanceOfType(target, node.typeName)) {
            // Kotlin's safe cast `as?` yields null instead of throwing.
            if (node.safe) return null
            throw IllegalArgumentException(
                "ClassCastException: 无法将 ${target.type().name()} 转换为 $targetFqn"
            )
        }
        return target
    }

    /**
     * Kotlin's elvis operator: `a ?: b` evaluates to `b` when `a` is null.
     * The right-hand side is only evaluated when it is actually needed.
     */
    private fun evalElvis(node: ExprNode.Elvis): Value? {
        val left = eval(node.left)
        return if (left == null) eval(node.right) else left
    }

    private fun evalArrayAllocation(node: ExprNode.ArrayAllocation): Value {
        val initializer = node.initializer
        if (initializer != null) {
            return buildArray(initializer)
        }
        val sizes = node.dimensions.map { d ->
            (eval(d) as? IntegerValue)?.value()
                ?: throw IllegalArgumentException("数组维度必须是 int")
        }
        if (sizes.isEmpty()) throw IllegalArgumentException("数组分配缺少维度")
        return allocArray(jdiTypeFor(node.elementType), sizes)
    }

    private fun evalClassLiteral(node: ExprNode.ClassLiteral): Value {
        if (isPrimitiveName(node.typeName)) {
            throw IllegalArgumentException("基本类型的 class 字面量暂不支持: ${node.typeName}")
        }
        val type = typeResolver.resolveReferenceType(node.typeName)
            ?: throw IllegalArgumentException("找不到类: ${node.typeName}")
        return type.classObject()
    }

    /**
     * A bare array initializer (`{ e1, e2 }`) has no element type of its
     * own — it is only meaningful as the tail of `new T[]{ ... }`, which
     * [evalArrayAllocation] handles via `ExprNode.ArrayAllocation.initializer`.
     * Reaching this node standalone means the frontend produced an
     * initializer outside of an allocation, which cannot be evaluated.
     */
    private fun evalArrayInitializer(): Value =
        throw IllegalArgumentException("数组初始化器必须配合 new T[]{...} 使用")

    private fun evalSafeCall(node: ExprNode.SafeCall): Value? {
        val target = eval(node.target)
        // Kotlin `?.`: null receiver short-circuits the whole access.
        if (target == null) return null
        return when (val args = node.args) {
            null -> evalField(target, node.name)
            else -> invokeOn(target, node.name, args.map { eval(it) })
        }
    }

    private fun evalNotNull(node: ExprNode.NotNull): Value? {
        val v = eval(node.target)
            ?: throw IllegalArgumentException("接收者为 null (Kotlin `!!` 断言失败)")
        return v
    }

    private fun evalStringTemplate(node: ExprNode.StringTemplate): Value {
        val sb = StringBuilder()
        node.parts.forEachIndexed { i, part ->
            sb.append(part)
            if (i < node.exprs.size) {
                sb.append(VariableFormatter.formatValueForResponse(eval(node.exprs[i])))
            }
        }
        return vm.mirrorOf(sb.toString())
    }

    /**
     * Materialize a `new T[]{...}` tree: allocate the outer array, then
     * fill elements (nested initializers recurse).
     */
    private fun buildArray(init: ExprNode.ArrayInitializer): ArrayReference {
        val arr = allocArray(jdiTypeFor(init.elementType), listOf(init.elements.size))
        for ((i, elem) in init.elements.withIndex()) {
            val value = if (elem is ExprNode.ArrayInitializer) buildArray(elem) else eval(elem)
            arr.setValue(i, value)
        }
        return arr
    }

    /**
     * JDI has no type-allocation API, so arrays are created via target-VM
     * reflection: `java.lang.reflect.Array.newInstance(Class, int)`. The
     * class mirror for primitives comes from the wrapper's `TYPE` field
     * (`Integer.TYPE` etc). Multi-dimensional sizes recurse level by level.
     */
    private fun allocArray(elementType: Type, sizes: List<Int>): ArrayReference {
        if (sizes.isEmpty()) throw IllegalArgumentException("数组维度为空")
        val elementClass = classMirrorFor(elementType)
        val arr = try {
            invokeArrayNewInstance(elementClass, sizes[0])
        } catch (ie: InvocationException) {
            throw IllegalArgumentException(
                "数组分配失败: ${ie.exception().referenceType().name()}", ie
            )
        } as? ArrayReference
            ?: throw IllegalArgumentException("数组分配返回了非数组值")
        if (sizes.size > 1) {
            for (i in 0 until sizes[0]) {
                arr.setValue(i, allocArray(elementType, sizes.subList(1, sizes.size)))
            }
        }
        return arr
    }

    private fun invokeArrayNewInstance(componentClass: ClassObjectReference, length: Int): Value {
        val arrayClass = vm.classesByName("java.lang.reflect.Array").firstOrNull() as? ClassType
            ?: throw IllegalArgumentException("java.lang.reflect.Array 不可用")
        val method = arrayClass.allMethods().firstOrNull {
            it.name() == "newInstance" && it.isStatic() &&
                it.argumentTypes().map { t -> t.name() } == listOf("java.lang.Class", "int")
        } ?: throw IllegalArgumentException("找不到 Array.newInstance(Class, int)")
        return arrayClass.invokeMethod(
            thread, method, listOf(componentClass, vm.mirrorOf(length)),
            ObjectReference.INVOKE_SINGLE_THREADED
        )
    }

    /** The `Class` mirror for a JDI type: `X.class`, or `X.TYPE` for primitives. */
    private fun classMirrorFor(type: Type): ClassObjectReference {
        if (type is PrimitiveType) {
            val wrapperName = primitiveToWrapper(type.name())
            val wrapper = vm.classesByName(wrapperName).firstOrNull()
                ?: throw IllegalArgumentException("找不到包装类: $wrapperName")
            val typeField = wrapper.allFields().firstOrNull { it.name() == "TYPE" }
                ?: throw IllegalArgumentException("找不到 ${wrapper.name()}.TYPE")
            return wrapper.getValue(typeField) as? ClassObjectReference
                ?: throw IllegalArgumentException("${wrapper.name()}.TYPE 不是 Class 镜像")
        }
        return (type as? ReferenceType)?.classObject()
            ?: throw IllegalArgumentException("无法获取 ${type.name()} 的 Class 镜像")
    }

    /** Resolve a type name (primitive, array, dotted FQN, or short name) to a JDI Type. */
    private fun jdiTypeFor(typeName: String): Type = when {
        typeName.endsWith("[]") -> jdiTypeFor(typeName.removeSuffix("[]"))
        isPrimitiveName(typeName) -> primitiveMirror(typeName).type()
        else -> typeResolver.resolveReferenceType(typeName)
            ?: throw IllegalArgumentException("找不到类: $typeName")
    }

    private fun primitiveMirror(name: String): PrimitiveValue = when (name) {
        "boolean" -> vm.mirrorOf(false)
        "byte" -> vm.mirrorOf(0.toByte())
        "char" -> vm.mirrorOf('\u0000')
        "short" -> vm.mirrorOf(0.toShort())
        "int" -> vm.mirrorOf(0)
        "long" -> vm.mirrorOf(0L)
        "float" -> vm.mirrorOf(0.0f)
        "double" -> vm.mirrorOf(0.0)
        else -> throw IllegalArgumentException("不是基本类型: $name")
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
