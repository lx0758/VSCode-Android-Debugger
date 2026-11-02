# Expression Evaluation

## Purpose

为已暂停的调试会话提供表达式求值能力——Watch、REPL、hover、条件断点与日志点共用一条求值管线。表达式按其自身语法形态选择前端解析，通过反射式解释在已暂停的目标 VM 上执行，过程中不向目标进程注入任何代码。

## Requirements

### Requirement: 按语法特征选择前端

求值器 SHALL 根据表达式的**语法特征**选择解析前端，而非依据栈帧的源语言。Kotlin 专有语法（安全调用 `?.`、非空断言 `!!`、`as` / `as?`、elvis `?:`、`is` 类型检查）在 Java 中无对应写法且会被 JDT 直接拒绝，此类表达式必须交由 Kotlin 前端；其余表达式（cast、`new`、数组分配、class literal、包限定名引用等）一律交由 JDT 前端——即使处于 Kotlin 栈帧中也是如此。栈帧语言仅在一处参与判定：字符串字面量中的 `$name` / `${expr}` 仅在 Kotlin 源文件中算作 Kotlin 模板。

前端选择仅决定表达式语法形态，**不**限定可引用的类型范围（任意语法下都可引用目标 VM 字节码中存在的任何类型）。

当所选前端无法解析表达式时，应回退尝试另一前端（两者语法有交叠但不完全相同）。两前端均失败时，应报告**所选前端**的错误（它描述用户实际书写的语法形态），并把另一前端的错误作为 suppressed 异常附加。

#### Scenario: Kotlin 专有语法走 Kotlin 前端
- **WHEN** 用户执行 `a?.b`、`a!!`、`x as T`、`x as? T`、`a ?: b` 或 `x is T`
- **THEN** 该表达式按 Kotlin 语法解析

#### Scenario: Java 形态表达式走 JDT 前端
- **WHEN** 用户执行 `(String) obj`、`new int[5]`、`String.class` 或 `java.util.Objects.isNull(x)`
- **THEN** 该表达式按 Java 语法解析

#### Scenario: Kotlin 栈帧中的 Java 形态表达式
- **WHEN** 在 Kotlin 栈帧中执行 `(String) obj`
- **THEN** 该表达式仍按 Java 语法解析——Kotlin 栈帧不改变语法选择依据

#### Scenario: Java 文件中的美元符号不是模板
- **WHEN** 在 Java 栈帧中执行 `"count=$count"`
- **THEN** 该字符串按普通字面量处理，不做模板插值

#### Scenario: 主前端失败时回退
- **WHEN** 所选前端无法解析表达式，但另一前端可以
- **THEN** 求值使用另一前端解析出的结果继续执行

#### Scenario: 两前端均失败
- **WHEN** 两个前端都无法解析表达式
- **THEN** 求值失败并报告主前端的错误，且该错误携带次要前端的错误作为 suppressed 异常

### Requirement: Java 表达式语法

Java 语法 SHALL 接受 Java 语言的表达式形式：字面量（数值、字符串、字符、布尔、null）、局部变量与参数、`this`、实例与静态字段访问、数组访问、带参数的方法调用、通过类名访问静态成员、带构造参数的 `new`（含泛型类型参数）、带大小表达式的数组分配（单维与多维）、数组初始化器、cast、`instanceof`、赋值、条件运算符，以及完整的运算符集合（数值提升与字符串拼接遵循 Java 语义）。

#### Scenario: cast 表达式
- **WHEN** 用户执行 `(String) obj`，且 `obj` 运行时实际持有 `String`
- **THEN** 求值成功并返回字符串值

#### Scenario: 带大小的数组分配
- **WHEN** 用户执行 `new int[5]`
- **THEN** 求值返回长度为 5 且元素全零的 int 数组

#### Scenario: 带泛型类型的对象构造
- **WHEN** 用户执行 `new java.util.ArrayList<String>()`
- **THEN** 求值构造出该列表对象（运行时擦除生效）并返回

#### Scenario: class literal
- **WHEN** 用户执行 `String.class.getName()`
- **THEN** 求值返回 `java.lang.String`

### Requirement: Kotlin 表达式语法

Kotlin 语法 SHALL 接受与 Java 语法相同的核心表达式形式，外加 Kotlin 专属糖：安全调用（`a?.b`——当接收者为 null 时短路返回 null，且不再求值成员）、非空断言（`a!!`——当接收者为 null 时求值失败并报"接收者为 null"错误）、字符串模板（`"prefix ${expr}"`——对内嵌表达式按字符串形式插值）、类型转换（`x as T` / `x as? T`）、类型检查（`x is T`）、elvis 运算符（`a ?: b`）。Kotlin 顶层 `object` 的引用应解析为该 object 的单例实例；类的 `Companion` 成员应可通过 companion 访问。

Kotlin 类型转换与类型检查的语义：`as` 在类型不匹配时求值失败，`as?` 在类型不匹配时返回 null；两者对 null 接收者返回 null。`is` 检查在接收者为 null 时返回 false。Kotlin 基本类型别名（`Int`、`Long`、`Double` 等）与对应包装类型视为同一类型。

#### Scenario: 接收者为 null 时的安全调用
- **WHEN** 在 Kotlin 栈帧中执行 `user?.name`，且 `user` 为 null
- **THEN** 求值返回 null，不报错，且不求值 `name`

#### Scenario: 安全调用短路整条链
- **WHEN** 用户执行 `a?.b.c` 且 `a` 为 null
- **THEN** 求值返回 null 且 `b.c` 不被求值

#### Scenario: 非空断言失败
- **WHEN** 在 Kotlin 栈帧中执行 `user!!` 且 `user` 为 null
- **THEN** 求值失败并提示接收者为 null

#### Scenario: 字符串模板插值
- **WHEN** 在 Kotlin 栈帧中执行 `"count=${this.count}"`，且 `this.count` 为 3
- **THEN** 求值返回字符串 `count=3`

#### Scenario: Kotlin 强转失败时报错
- **WHEN** 用户执行 `x as String` 且 `x` 运行时不是 `String`
- **THEN** 求值失败并报类型不匹配

#### Scenario: Kotlin 安全强转失败时返回 null
- **WHEN** 用户执行 `x as? String` 且 `x` 运行时不是 `String`
- **THEN** 求值返回 null，不报错

#### Scenario: Kotlin 类型检查
- **WHEN** 用户执行 `x is String` 且 `x` 运行时是 `String`
- **THEN** 求值返回 true

#### Scenario: Kotlin 类型检查识别基本类型别名
- **WHEN** 用户执行 `x is Int` 且 `x` 运行时是 `int`
- **THEN** 求值返回 true

#### Scenario: elvis 运算符
- **WHEN** 用户执行 `a ?: b` 且 `a` 为 null
- **THEN** 求值返回 `b` 的值

#### Scenario: Kotlin object 引用
- **WHEN** 在 Kotlin 栈帧中执行 `Foo.bar()`，且 `Foo` 是带函数 `bar` 的 Kotlin `object`
- **THEN** 求值在该 object 的单例实例上调用 `bar`

### Requirement: 短名解析

表达式中的简单类型名 SHALL 按 Java 的名字解析顺序解析为完全限定名，候选集来源于求值栈帧对应源文件的内容：(1) 同源文件中声明的类型；(2) 该文件的单类型 import；(3) 同包中的类型；(4) `java.lang` 中的类型；(5) 该文件的 on-demand（`*`）import。import 候选集应由源码索引条目提供。当同一优先级下出现多个候选匹配时，求值应失败并以错误形式列出所有候选。

#### Scenario: 通过单类型 import 解析
- **WHEN** 栈帧所在源文件包含 `import java.util.Objects;`，且用户执行 `Objects.isNull(x)`、`x` 为 null
- **THEN** 名字 `Objects` 解析为 `java.util.Objects`，求值返回 true

#### Scenario: 无 import 时通过 java.lang 解析
- **WHEN** 栈帧所在源文件未导入 `Math`，且用户执行 `Math.max(1, 2)`
- **THEN** 名字 `Math` 解析为 `java.lang.Math`，求值返回 2

#### Scenario: 通过同包解析
- **WHEN** 栈帧所在类是 `com.example.Foo`，`com.example` 包内存在 `Bar`，且用户执行 `Bar.STATIC_FIELD`
- **THEN** 名字 `Bar` 解析为 `com.example.Bar`

#### Scenario: 短名歧义
- **WHEN** 栈帧所在源文件有两个 on-demand import 都提供名为 `Config` 的类型，且用户执行 `Config.VALUE`
- **THEN** 求值失败并以错误形式列出所有候选的完全限定名

### Requirement: 运行时类型解析

类型引用 SHALL 通过运行时反射解析目标 VM 中已加载的类。求值**不**应要求编译期 classpath。当所引用的类未被目标 VM 加载时，求值应失败并以错误形式指出未解析的类名。

所有需要运行时类型的地方——cast、`instanceof` / `is`、`new`、class literal、以及看起来像类名的变量引用——应走同一条类型解析路径，不得各自用短名与全限定名的字面比较来判断类型。该路径应支持：完全限定名（`java.util.Objects`）、嵌套类（`Outer.Inner` 解析为 JVM 的 `Outer$Inner`）、短名（经名字解析候选链得到全限定名），以及 Kotlin 基本类型别名（`Int` 映射到 `java.lang.Integer`）。当短名在候选链中解析失败时，Kotlin 别名作为兜底。

#### Scenario: 完全限定名解析为已加载类
- **WHEN** 用户执行 `java.util.Objects.isNull(null)`
- **THEN** 该类型从已加载的 `java.util.Objects` 解析，求值返回 true

#### Scenario: 未加载的类产生命名错误
- **WHEN** 用户执行 `com.example.NotYetLoaded.FIELD` 且该类尚未被目标 VM 加载
- **THEN** 求值失败并以错误形式指出 `com.example.NotYetLoaded` 未解析

#### Scenario: 短名 cast 按候选链解析
- **WHEN** 用户执行 `(String) obj` 且 `obj` 运行时实际持有 `java.lang.String`
- **THEN** 目标类型解析为 `java.lang.String`，求值成功并返回字符串值

#### Scenario: 嵌套类按 JVM 二进制名解析
- **WHEN** 用户执行 `(Outer.Inner) obj` 且该嵌套类以 `Outer$Inner` 加载
- **THEN** 目标类型解析为 `Outer$Inner`，求值成功

#### Scenario: cast 到不相关类型时报错
- **WHEN** 用户执行 `(Integer) obj` 且 `obj` 运行时持有 `java.lang.String`
- **THEN** 求值失败并以错误形式指出实际类型与目标类型

### Requirement: 方法调用语义

求值过程中的方法调用 SHALL 在已暂停栈帧的线程上执行，且不应扰乱其它线程的暂停状态。被调用方法抛出的异常应作为求值错误上抛，并标注异常的类型名。

#### Scenario: 实例方法带参
- **WHEN** 用户执行 `list.subList(0, 2)`，`list` 是非空集合
- **THEN** 该方法在目标 VM 中执行，并返回其结果

#### Scenario: 被调用方法抛出
- **WHEN** 用户执行 `obj.boom()` 且 `boom` 抛出 `IllegalStateException`
- **THEN** 求值失败并以错误形式指出 `IllegalStateException`

#### Scenario: 不影响其它线程
- **WHEN** 求值过程中调用某个方法，而此时调试器暂停了其它线程
- **THEN** 求值结束后那些线程的暂停状态保持不变

### Requirement: 重载解析

方法重载候选 SHALL 排除 synthetic 与 bridge 方法。实参匹配应接受精确类型匹配、基本类型 widening、以及基本类型与包装类的装箱匹配。当经过这些规则后仍有多个候选同等匹配时，求值应失败并以错误形式列出所有候选签名（显式歧义，不猜测）。

#### Scenario: 基本类型 widening
- **WHEN** 用户执行 `Math.max(1, 2L)`
- **THEN** 选中 `(long, long)` 重载，求值返回 2

#### Scenario: 装箱匹配
- **WHEN** 用户执行 `list.add(42)`，`list` 是含有其它元素的 `java.util.List<Integer>`
- **THEN** 装箱后的 `Integer` 实参被接受，add 成功

#### Scenario: 歧义重载报错并列出候选
- **WHEN** 名为 `f` 的方法存在 `f(int, Object)` 与 `f(Object, int)` 两个重载，且用户执行 `f(1, 2)`
- **THEN** 求值失败并以错误形式列出两个候选签名

#### Scenario: 继承层级上的同一方法不算歧义
- **WHEN** 目标方法在继承链上有多个同名同参的声明（如 `String.length()` 同时声明于 `String` 与 `CharSequence`），且用户执行 `s.length()`
- **THEN** 求值按最派生的实现调用成功，不报歧义

### Requirement: class literal 的可用 API

class literal（`Foo.class`）的求值结果 SHALL 是目标 VM 中 `java.lang.Class` 的实例。因此它既可以访问目标类型上的静态成员，也可以调用 `java.lang.Class` 自身的实例方法（`getName`、`getSimpleName` 等）。

#### Scenario: class literal 的实例方法
- **WHEN** 用户执行 `java.lang.String.class.getName()`
- **THEN** 求值返回 `java.lang.String`

### Requirement: 赋值支持

求值器 SHALL 支持对局部变量、实例字段、静态字段、数组元素的赋值表达式。当目标是 final、值类型不兼容、或目标无法解析时，赋值应给出明确的错误。

#### Scenario: 给局部变量赋值
- **WHEN** 用户在 `count` 为非 final 局部的栈帧中执行 `count = 5`
- **THEN** 该变量值变为 5，后续求值看到新值

#### Scenario: 给数组元素赋值
- **WHEN** 用户执行 `arr[0] = 42`，`arr` 是 int 数组
- **THEN** 第一个元素变为 42

#### Scenario: final 字段赋值失败
- **WHEN** 用户执行 `this.id = 9`，`id` 是 final 实例字段
- **THEN** 求值失败并以错误形式指出该字段是 final

### Requirement: 可展开的求值结果

当求值结果为可展开值（具有 visible 字段的对象，或非空数组）时，`evaluate` 响应 SHALL 返回非零的 `variablesReference`，可在后续 `variables` 请求中用于展开该结果的成员，与变量面板体验一致。原始值与 null 结果应返回 `variablesReference: 0`。求值结果的引用应在执行恢复（continue 或 step）时被失效，与栈帧变量引用的失效规则保持一致；对已失效引用的展开应返回空列表且不报错。

#### Scenario: 对象结果可在 Watch 中展开
- **WHEN** 用户执行 `this.user`，结果为具有 visible 字段的非空对象
- **THEN** 响应携带非零的 `variablesReference`
- **AND** 在 Watch 面板中展开它可看到对象的字段

#### Scenario: 原始值结果不可展开
- **WHEN** 用户执行 `1 + 1`
- **THEN** 响应携带 `variablesReference: 0`

#### Scenario: continue 后引用失效
- **WHEN** 用户执行某个返回对象引用的表达式，然后继续执行，再尝试展开之前的结果
- **THEN** 展开返回空列表且不报错

### Requirement: 断点功能共用同一条求值管线

条件断点的条件与日志断点的占位符 SHALL 通过与 Watch/REPL 同一套表达式管线求值——同一套语法选择、同样的名字解析、同样的运行时语义——求值在断点命中的栈帧中进行。

#### Scenario: 条件断点使用完整表达式语言
- **WHEN** 用户设置条件断点 `items.size() > 3 && isActive`
- **AND** 断点在条件为真的栈帧中命中
- **THEN** 执行挂起

#### Scenario: 日志占位符使用带参数的方法调用
- **WHEN** 消息为 `"sum={computeSum(a, b)}"` 的日志断点命中，且 `computeSum`、`a`、`b` 在作用域内
- **THEN** 输出的消息包含该调用的求值结果
