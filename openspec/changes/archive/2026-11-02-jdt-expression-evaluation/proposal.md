# 提案：表达式求值切换 JDT 前端

## Why

调试器的表达式求值（Watch / REPL / hover / 条件断点 / 日志点共用一条管线）当前由自研递归下降 parser 承担，虽已覆盖局部变量、成员、方法调用等主干，但与 Java 语义存在可见偏差：不支持强制转换 `(Foo) x`、数组分配 `new int[n]`、带泛型的 `new`、`A.b` 静态字段访问；短名解析依赖"大写标识符即类"的启发式而非 import 规则；求值结果不可在 Watch 面板展开（`variablesReference` 恒为 0）；且没有 Java/Kotlin 语言分流。架构文档 §十一（D25-D30）已敲定"解释执行 + 运行时 JDI 反射 + JDT 语法前端"的重设计方案，但尚无任何实现。

## What Changes

- **架构分拆**：`adapter/src/main/kotlin/.../parser/` 拆为 `eval/` 包——共享 IR（`ExprNode`）、Kotlin 前端（现有 Lexer/Parser + 糖层）、共享运行时层（`RuntimeEvaluator`，即现 `Evaluator.kt`）；`jdi/ExpressionEvaluator` 升格为分发器
- **JDT Java 前端**：引入 `org.eclipse.jdt:org.eclipse.jdt.core:3.23.0`（最后一个 Java 8 兼容版），ASTParser standalone 空环境 `K_EXPRESSION` 模式解析表达式，AST→ExprNode 翻译器产出统一 IR；Java 栈帧的表达式语法全覆盖（含 cast、数组分配、泛型 new、`Foo.class`、数组初始化器）
- **前端分流**：按**表达式自身的语法特征**选择前端——Kotlin 专有语法（`?.`、`!!`、`as`/`as?`、`?:`、`is`）走自研 Kotlin 前端，其余走 JDT 前端（即使在 Kotlin 栈帧中）；主前端解析失败时回退尝试另一前端，两端都失败时报主前端错误并附加次要错误。栈帧语言仅用于判定字符串模板是否为 Kotlin 模板
- **统一运行时类型解析**（`TypeResolver`）：cast / `instanceof` / `is` / `new` / class literal / 类名变量引用共用一条类型解析路径（FQN 直查 + 嵌套类 `Outer$Inner` + 短名候选链 + Kotlin 基本类型别名兜底），不再用短名与全限定名的字面比较
- **Kotlin 类型语法补齐**：parser 支持 `as` / `as?`（`ExprNode.Cast` 增 `safe` 标志）、`is`（`ExprNode.InstanceOf`）、`?:`（新增 `ExprNode.Elvis`），优先级贴合 Kotlin 官方表
- **短名解析（NameResolver）**：替换大写启发式，解析链为同文件类型 → 单类型 import → 同包 → java.lang → on-demand import
- **索引携带 imports**：`FqnEntry` 增加 `imports` 字段，TS 索引器解析文件时顺路提取（文本已在手），经 `custom/syncSourceIndex` 下发至 adapter 的 `FileEntry`
- **运行时补齐**：静态字段/方法经 `ReferenceType`/`ClassType` 原语解析；基本类型装箱匹配；`isSynthetic()/isBridge()` 过滤；重载歧义显式报错（列出候选签名，不猜测）
- **求值结果可展开（D30）**：`handleEvaluate` 返回真实 `variablesReference`（ObjectReference 经 `VariableFormatter.registerExpandable` 注册），Watch 面板内可继续展开字段，体验与变量面板一致
- **Kotlin 糖层（最后实施）**：`?.` 安全调用短路、`!!` 非空断言、字符串模板插值

## Capabilities

### New Capabilities

- `expression-evaluation`: 双前端表达式求值架构——JDT（Java）/ 自研（Kotlin）前端产出统一 `ExprNode` IR，共享 `TypeResolver`（类型名→运行时类，统一处理 FQN / 嵌套类 / 短名候选链 / Kotlin 别名）与 `RuntimeEvaluator`（运行时 JDI 反射执行）；前端按表达式语法特征分流并双向回退；求值结果为可展开引用

### Modified Capabilities

- `android-java-debugger`:
  - **Log Breakpoints**：`{expr}` 占位符从"简单表达式（局部变量/this/其字段/无参方法）"扩展为完整表达式语言（方法调用带参、静态成员、`new`、数组、运算符等），因为求值管线整体升级
  - **Simple Conditional Breakpoints**：条件从"单个字段访问与字面量比较"扩展为完整表达式语言（需求更名为 Conditional Breakpoints，原"简单"限定不再成立）
  - **TS-Side SourceIndexer / Incremental Source Index Updates**：索引条目 wire 形态从 `{fqn, file, sourceUri?}` 扩展为 `{fqn, file, sourceUri?, imports?}`（additive，不破坏既有消费方）

## Impact

- **adapter/（Kotlin）**：
  - `parser/` → `eval/` 整体搬迁（Ast/Tokens/Lexer/Parser → eval/kotlin，Evaluator → eval/RuntimeEvaluator）
  - 新增 `eval/` 基础设施：JDT 翻译器、NameResolver、语言检测
  - `DapServer.handleEvaluate` 接通 `variablesReference`
  - `build.gradle.kts` 新增 `org.eclipse.jdt:org.eclipse.jdt.core:3.23.0` 依赖（EPL-2.0），shadowJar 体积约 +20MB；JVM 目标维持 1.8 不变
- **extension/src（TS）**：
  - `core/indexer/Indexer.ts`：`FqnEntry` 增加 `imports?: string[]`
  - `core/indexer/LocalIndexer.ts`、`core/IndexManager.ts`：索引期提取 import 声明（Java/Kotlin）
  - `custom/syncSourceIndex` payload 增量扩展（additive）
- **风险**：JDT 3.23 较旧（2021）但支持解析至 Java 16 语法，Android 场景足够；若依赖树异常需锁 Eclipse 传递坐标
