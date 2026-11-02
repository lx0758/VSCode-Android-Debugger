## 1. 架构搬迁（parser → eval/）

- [x] 1.1 搬迁 `adapter/.../parser/Ast.kt` → `eval/common/ExprNode.kt`；`parser/Tokens.kt` + `parser/Lexer.kt` + `parser/Parser.kt` → `eval/kotlin/` 同名文件；包名由 `...parser` 改为 `...eval.common` / `...eval.kotlin`；更新所有 import。验证：构建通过；现有 unit test 全部仍通过（test 目录为空，无现存用例）；手动 attach 一次 evaluate `this.field` 行为零差异
- [x] 1.2 搬迁 `parser/Evaluator.kt` → `eval/RuntimeEvaluator.kt`；将构造参数 `frame: StackFrame` 保留（向后兼容现有调用方），但抽出 `imports: List<String>` 与 `sourceIndex: SourceIndex` 为构造参数（默认值兼容旧调用）。验证：构建通过；evaluate 任意现有表达式（局部变量/字段/调用/`new`/数组/运算符）行为完全一致

## 2. D30 可展开求值结果

- [x] 2.1 改写 `DapServer.handleEvaluate`：走 `exprEvaluator.resolveExpression(expr, frame)` 拿 Value；`formatValueForResponse` 提升为 `jdi/VariableFormatter` 的 `public companion object` 静态方法供 evaluate 与 setVariable 共用；当 `isExpandable(value)` 为真时 `varFormatter.registerExpandable(value)` 并把返回的 ref 填入响应 `variablesReference` 字段（恒为 0 的硬编码删除）。验证：手动 attach → 暂停 → Watch 面板 evaluate 一个返回 ObjectReference 的表达式（如 `this`）→ 结果可点击展开；evaluate 原始值表达式 `1+1` → `variablesReference: 0`；evaluate 后 `continue` 再展开结果 → 空列表不报错

## 3. 索引携带 import 声明

- [x] 3.1 TS 端：扩展 `extension/src/core/indexer/Indexer.ts` 的 `FqnEntry` 接口加 `imports?: string[]`；在 `LocalIndexer.ts` 的 `parseJavaFile` / `parseKotlinFile` 中，文件全文解析后追加正则扫描 import 声明（Java: `^import (?:static )?([\w.*]+);`，Kotlin: `^import ([\w.*]+)(?: as (\w+))?$`，保留 alias 后的 FQN 形态）；结果随 FQN 一起返回。验证：构建通过；本地调试模式下 TS 日志能看到每个文件携带 imports（`[Indexer] FileEntry: foo.java imports=[...]`）；`custom/syncSourceIndex` payload 包含 `imports` 数组
- [x] 3.2 adapter 端：扩展 `source/SourceIndex.kt` 的 `FileEntry` data class 加 `imports: List<String>` 字段（默认 `emptyList()`）；`SourceIndexer.add` 写入时读取 entries map 的 `imports` 字段；`handleSyncSourceIndex` 路径在收到无 imports 字段的旧 payload 时回退空列表（向后兼容旧 TS）。验证：单元测试覆盖有 imports / 无 imports 两种 payload 形态；构建通过

## 4. NameResolver（短名 → FQN）

- [x] 4.1 新增 `eval/NameResolver.kt`：`class NameResolver(frame: StackFrame, sourceIndex: SourceIndex)`；实现 `resolveClass(shortName: String): ClassType?` 按优先级链：同文件类型（`sourceIndex.resolveFqnsByFile(frameFilePath)`）→ 单类型 import（`frameFileEntry.imports` 中精确匹配）→ 同包（`frame.declaringType.packageName() + "." + shortName`）→ java.lang 硬编码表（`Math`/`String`/`Integer`/`Object`/`System` 等常见类型）→ on-demand import（`frameFileEntry.imports` 末尾 `.*`）。歧义（同一优先级下多个匹配）抛 `IllegalArgumentException` 列出所有候选 FQN。验证：单元测试覆盖五个优先级 + 歧义场景
- [x] 4.2 `RuntimeEvaluator` 移除 `parser/Evaluator.kt:149-160` "大写 Var 启发式"（`evalCall` 内的 if 块）；`evalVar` 在 `frame.visibleVariables()` 未命中时改走 `nameResolver.resolveClass(name)`；`evalCall` 静态方法分支同样走 NameResolver。验证：手动 attach 后 evaluate `Math.max(1, 2)`（无 import 走 java.lang 链）→ 2；evaluate `Objects.isNull(x)`（含 `import java.util.Objects`）→ true；evaluate `Bar.STATIC_FIELD`（同包）→ 正确值；evaluate 两个 on-demand import 都提供 `Config` 的歧义场景 → 报错列出两个 FQN

## 5. JDT Java 前端

- [x] 5.1 `adapter/build.gradle.kts` dependencies 块加 `implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.23.0")`；保留 `sourceCompatibility = JavaVersion.VERSION_1_8` 不变；运行 `./gradlew :adapter:shadowJar` 验证依赖解析无冲突。验证：构建通过；`extension/resources/adapter.jar` 体积 ~24MB（基线 4MB + JDT 及其 Eclipse 传递依赖 ~20MB）；`java -jar extension/resources/adapter.jar --version` 仍能启动（确认 JDT 在 1.8 兼容）
- [x] 5.2 新增 `eval/JavaFrontend.kt`：ASTParser `JLS14`（3.23.0 的最高 JLS，JLS15+ 属 3.24+）+ `setEnvironment(null, null, null, true)` + `setKind(ASTParser.K_EXPRESSION)`；AST→ExprNode 翻译器完整覆盖 `MethodInvocation` / `FieldAccess` / `ClassInstanceCreation` / `ArrayCreation`（含多维）/ `ArrayInitializer` / `CastExpression` / `TypeLiteral` / `InfixExpression` / `ConditionalExpression` / `Assignment` / `InstanceofExpression` / `ParenthesizedExpression` / `SimpleName` / `QualifiedName` / `NumberLiteral` / `StringLiteral` / `CharacterLiteral` / `NullLiteral` / `BooleanLiteral` / `PrefixExpression` / `PostfixExpression`。验证：单元测试每种 AST 节点 → ExprNode 映射一条用例
- [x] 5.3 扩展 `eval/common/ExprNode.kt` sealed class 新增 `Cast` / `ArrayAllocation` / `ClassLiteral` / `ArrayInitializer` / `SafeCall` / `NotNull` / `StringTemplate` 节点（SafeCall/NotNull/StringTemplate 由 T8 接入 Kotlin 前端，本任务先把节点类型加齐使 sealed class 子类型完备）；确保 `RuntimeEvaluator.eval` 的 `when` 分支在子类型未实现时编译失败。验证：`./gradlew :adapter:compileKotlin` 编译通过；现有 `when` 已被迫补全（编译错误触发 `else` 分支移除）

## 6. D27 语言检测与分发

- [x] 6.1 新增 `eval/LanguageDetector.kt`：`enum Language { JAVA, KOTLIN }`；`detectLanguage(frame: StackFrame, sourceIndex: SourceIndex): Language` 判定链：FQN → `sourceIndex.resolveByFqn(loc.declaringType().name())` → `FileEntry.path` 后缀（`.kt` → KOTLIN，`.java` → JAVA）→ `loc.sourceName()` 后缀（同样规则）→ 默认 JAVA。验证：单元测试覆盖四类输入（索引命中 .java / 索引命中 .kt / 仅 sourceName 可识别 / 完全未知）
- [x] 6.2 `jdi/ExpressionEvaluator.kt` 升格为分发器：构造参数加 `sourceIndex: SourceIndex`；`resolveExpression` / `evaluateExpression` 入口先 `detectLanguage(frame, sourceIndex)` → 选 frontend（Kotlin → 现有 parser，Java → `JavaFrontend`）→ 解析为 `ExprNode` → 走 `RuntimeEvaluator`；`evaluate(template, frame)` 内部 `{expr}` 占位符按同样管线处理。`DapServer` 构造时把 adapter 侧 `SourceIndexer` 注入。验证：手动 attach 在 Java 栈帧 evaluate `new int[5]`（旧 parser 不支持）→ 返回长度 5 数组（JDT 解析）；在 Kotlin 栈帧 evaluate 同表达式 → 数组（Kotlin 解析）；索引外的源文件 evaluate → 走 Java 语法（JDT）

## 7. 运行时补齐

- [x] 7.1 `RuntimeEvaluator.evalField` 区分 target 类型：`ClassType` 走静态字段 `type.getValue(field)` / `type.setValue(field, value)`；`ObjectReference` 走实例字段 `obj.getValue(field)` / `obj.setValue(field, value)`（保持现状）；`evalCall` 同样区分 `ClassType` 走 `ClassType.invokeMethod` 与 `ObjectReference` 走 `ObjectReference.invokeMethod`。验证：evaluate `Integer.MAX_VALUE` → 2147483647；evaluate `Math.max(1, 2)` → 2；evaluate `obj.staticMethod()`（obj 引用的实际类型有同名静态）→ 调静态
- [x] 7.2 重载解析补齐：`argsMatch` 加装箱匹配规则（形参 `int` ↔ 实参 `Integer`，形参 `Integer` ↔ 实参 `int`，所有基本类型↔包装类对）；`findMatchingMethod` 过滤 `isSynthetic() || isBridge()`；多个精确候选时抛 `IllegalArgumentException` 列出候选签名（`f(int,Object)`, `f(Object,int)` 等）。验证：单元测试覆盖 widening + boxing + synthetic 过滤 + 歧义报错四个场景；evaluate `list.add(42)`（`List<Integer>`）→ 成功；evaluate `f(1, 2)`（f 有 `f(int,Object)` 与 `f(Object,int)` 重载）→ 报错列出两签名
- [x] 7.3 处理 JDT 前端产出的扩展节点：`Cast(target, typeName)` 求值时校验运行时类型可赋给 `typeName`（不可赋则抛 ClassCastException 包装错误，否则返回 target）；`ArrayAllocation(elementType, dimensions, initializer?)` 调用 `ArrayType.newInstance(thread, componentType, sizes)`，`initializer` 非空时按元素逐个 setValue；`ClassLiteral(typeName)` 调 `vm.classesByName(typeName).classObject()` 返回 `ObjectReference`；`ArrayInitializer(elementType, elements)` 同样走 `ArrayType.newInstance` + 逐元素 setValue。验证：单元测试覆盖四种节点；evaluate `(String) obj`（obj 实为 String）→ 字符串值；evaluate `new int[5]` → 长度 5 数组；evaluate `String.class.getName()` → `"java.lang.String"`；evaluate `new int[]{1, 2, 3}`（语法由 JDT 解析） → `int[]` 三个元素

## 8. Kotlin 糖层

- [x] 8.1 `eval/kotlin/Tokens.kt` 加 `SafeCallOp("?.")` / `NotNullOp("!!")` 两个 token；`eval/kotlin/Lexer.kt` 扩展 `readNotOp` 在 `!` 后跟 `!` 产出 NotNullOp（而非 `!`），在 `?.` 位置产出 SafeCallOp；`eval/kotlin/Parser.kt` 增加 `parsePostfix` 循环内对 SafeCall / NotNull 的处理 + 新增 `parseStringTemplate` 解析 `"prefix${expr}suffix"` 中的 `${...}` 块（expr 解析到配对 `}`，非嵌套）。验证：单元测试覆盖 `a?.b` / `a?.b(args)` / `a!!` / `"x=${a.b}"` 四种语法解析
- [x] 8.2 `RuntimeEvaluator` 处理 Kotlin 糖节点：`SafeCall(target, name, args?)` → target 为 null 短路返回 null（`vm.mirrorOf(null)`），否则正常求值访问 name/调用方法；`NotNull(target)` → target 为 null 抛 `IllegalArgumentException` "接收者为 null"，否则返回 target；`StringTemplate(parts, exprs)` → 拼接 `parts[0] + formatValue(exprs[0]) + parts[1] + ...`（复用 `VariableFormatter.formatValue`）。验证：手动 attach 在 Kotlin 栈帧 evaluate `user?.name`（user 为 null）→ null 不报错；evaluate `user!!`（user 为 null）→ 错误；evaluate `"count=${this.count}"`（count=3）→ `"count=3"`
- [x] 8.3 Kotlin 顶层 `object` 引用解析：扩展 `NameResolver.resolveClass` 在 Kotlin 栈帧上下文（Language == KOTLIN）且候选类为 Kotlin object（Kotlin object 编译后类是 `Foo`，INSTANCE 字段是 `public static final`）时，返回该类的 INSTANCE 字段值（`type.getValue(type.allFields().firstOrNull { it.name() == "INSTANCE" })`）；`ExprNode.Var("Foo")` 在 RuntimeEvaluator 走 NameResolver 时命中此分支。验证：手动 attach 在 Kotlin 栈帧 evaluate `Foo.bar()`（Foo 是 Kotlin object）→ 调 bar 在单例上

## 9. P0 统一运行时类型解析

- [x] 9.1 新增 `eval/TypeResolver.kt`：类型名 → 运行时类的唯一入口。FQN 直查 `vm.classesByName`；嵌套类按 JVM 二进制名（`Outer.Inner` → `Outer$Inner`）；短名走 `NameResolver` 候选链；Kotlin 基本类型别名兜底（`Int` → `java.lang.Integer`，仅在候选链失败后生效，用户自定义 `Int` 优先）。`NameResolver` 增 `resolveReferenceType` 返回 `ReferenceType`。验证：单元测试覆盖 FQN / 嵌套类 / 短名 / 别名 / 别名被同名用户类遮蔽五种场景
- [x] 9.2 `RuntimeEvaluator` 全量接入 `TypeResolver`：`evalCast` / `isInstanceOfType` / `evalInstanceOf` / `evalVar`（类名变量）/ `evalClassLiteral` 全部走统一解析，不再用短名与 FQN 的字面比较。`evalCast` 增"找不到类"分支与 `ClassCastException` 消息（同时给出实际类型与目标类型）。验证：单元测试覆盖短名 cast / FQN cast / cast 到超类型 / cast 到不相关类型 / cast 到未加载类；evaluate `(String) obj`（obj 实为 String）→ 成功
- [x] 9.3 `isInstanceOfType` 增装箱等价判定（`int` ↔ `java.lang.Integer` 等）与基本值装箱后的层级判定（`int` 可赋给 `java.lang.Object`）。验证：单元测试覆盖基本类型与包装类型互相判定
- [x] 9.4 `parseNumber` 增强：支持 `0x` / `0b` / `0o` 前缀、科学计数法、下划线分隔符、`L` / `F` / `D` 后缀。验证：单元测试覆盖各类字面量形态

## 10. P1 前端按语法特征分流

- [x] 10.1 新增 `eval/KotlinSyntaxDetector.kt`：检出 Kotlin 专有语法特征（`SAFE_CALL` / `NOT_NULL` / `AS_CAST` / `ELVIS` / `IS_CHECK` / `TEMPLATE`）。`as` / `is` 按上下文判定（左侧须有操作数、右侧须有类型名），不把 `view.is`、`view.as` 误判为运算符；字符串与字符字面量内的内容不计入运算符特征。验证：单元测试覆盖各特征命中与不命中场景
- [x] 10.2 新增 `eval/FrontendSelector.kt`：`select(expression, language)` 按语法特征选前端；`parse(expression, language)` 双向回退——主前端失败则试另一前端，两端都失败时抛主前端错误（次要错误 `addSuppressed`）。`LanguageDetector` 保留，但仅用于判定 TEMPLATE 是否按 Kotlin 解释与日志。验证：单元测试覆盖选择矩阵与回退行为
- [x] 10.3 `jdi/ExpressionEvaluator.parse` 改走 `FrontendSelector.parse(expr, LanguageDetector.detectLanguage(frame, sourceIndex))`。验证：Kotlin 栈帧 evaluate `(String) obj` → Java 语法解析成功；evaluate `x as String` → Kotlin 语法解析成功

## 11. P2 Kotlin 类型语法

- [x] 11.1 IR 扩展：`ExprNode.Cast` 增 `safe: Boolean = false`（`as?` 失配返回 null）；新增 `ExprNode.Elvis(left, right)`。验证：编译通过，`RuntimeEvaluator.eval` 的 `when` 分支补全
- [x] 11.2 词法层：`Tokens.kt` 删除 `Token.KwAs` / `Token.KwIs`（改为 parser 层按标识符上下文判定，不破坏 `x.is` 字段访问）；`?` 分三路（`?.` / `?:` / 裸 `?`）；`::` 与 `->` 直接报错（避免 JDT 不支持项被 Kotlin parser 静默误解析，`a-->b` 除外）。验证：单元测试覆盖 `?.` / `?:` / `?` 的 token 归属与 `::` / `->` 报错
- [x] 11.3 语法层：`Parser.kt` 增 `parseIsCheck()` / `parseElvis()` / `parseCast()`，`as` 目标类型名支持点分标识符并跳过泛型实参（`skipGenericArgs()` 白名单扫描，失败回滚以免吞掉比较运算），`is` 目标类型不允许泛型，`new` 允许泛型。优先级链贴合 Kotlin 官方表：`equality → relational → is-check → elvis → additive → multiplicative → cast → unary → postfix → primary`。验证：单元测试覆盖 as / as? / is / elvis 的解析树与优先级
- [x] 11.4 运行时：`RuntimeEvaluator` 实现 `evalElvis`（左侧为 null 取右侧）、`evalCast` 按 `node.safe` 失配返回 null。验证：单元测试覆盖 elvis 两侧与 safe cast 命中/失配

## 12. Java 侧实测修复

> 本轮以"真实 JVM 端到端求值"为标尺复查 Java 调试体验，发现并修复了 4 个此前被 mock 单测掩盖的问题。

- [x] 12.1 **构建同步失效**：`adapter/build.gradle.kts` 的 `copyJarToExtension` 目标路径算错（指向仓库外的 `../extension/resources`），且用 `outputs.dir` 声明输出导致增量判断永远认为"无需执行"——扩展里的 `adapter.jar` 从未更新，所有修复都没生效。改为 `inputs.file(jarFile)` + `outputs.file(target)`，目标路径取 `projectDir.parentFile/extension/resources`。验证：构建后 `extension/resources/adapter.jar` 的时间戳与内容随 `shadowJar` 更新
- [x] 12.2 **重载候选重复导致误报歧义**：`ReferenceType.allMethods()` 会把同一签名按继承层级重复列出（`String.length()` 来自 `String` 与 `CharSequence`，`ArrayList.<init>()` 出现 4 次），未去重就被当成多个重载 → `s.length()` 报"方法调用有歧义"。新增 `collapseDuplicates()`：按"参数类型序列 + static/instance"分组，每组取声明在 owner 自身上的那个（最派生实现）。验证：真实 JVM 上 `s.length()`、`new java.util.ArrayList<String>()` 正常
- [x] 12.3 **class literal 的实例方法不可调用**：`String.class` 求值结果是 `java.lang.Class` 的**实例**，但 `invokeOn` 对 `ClassObjectReference` 只查静态方法 → `String.class.getName()` 报"静态方法 'getName' 未找到"。补充在 `java.lang.Class` 上查找实例方法的回退分支。验证：真实 JVM 上 `java.lang.String.class.getName()` 返回 `java.lang.String`
- [x] 12.4 **真实 JDI 端到端测试**：新增 `JavaEvaluationEndToEndTest`——attach 到本地子 JVM（JDWP）、在 Java 方法入口打断点、在真实栈帧上求值。覆盖短名/全名/基本类型/超类型/泛型强转、类型不匹配报错、全路径静态方法/构造器/class literal/静态字段、`instanceof`、局部变量、方法调用、算术共 16 个用例。验证：`./gradlew test` 全绿（160 个用例）
