# 设计：表达式求值切换 JDT 前端

## Context

`adapter/src/main/kotlin/.../parser/`（Lexer/Parser/Evaluator 递归下降）是表达式求值管线的现状实现，已能覆盖局部变量、字段、方法调用（含静态方法的大写启发式）、`new`、数组、运算符、`instanceof`、赋值等主干，但与 Java 语义存在可见偏差：cast `(Foo) x`、数组分配 `new int[n]`、带泛型的 `new`、`A.b` 静态字段访问、装箱匹配均缺失；短名解析依赖"大写标识符即类"的启发式而非 import 规则；求值结果在 Watch 面板不可展开（`DapServer.handleEvaluate` 硬编码 `variablesReference: 0`）；没有 Java/Kotlin 语言分流。架构文档 §十一（D25-D30）已敲定"解释执行 + 运行时 JDI 反射 + JDT 语法前端"的重设计方案，但尚无任何实现。当前 PR 把 §十一 P1 与 P2 合并为单一变更落地：引入 JDT Java 前端，重构 adapter 包结构，索引期携带 import 声明，新增 NameResolver 与语言检测，最终让表达式求值与 IDEA / vscode-java-debug 体验对齐。

约束（影响设计选择）：
- `adapter/build.gradle.kts` 目标 JVM 是 1.8（`sourceCompatibility = VERSION_1_8`）—— JDT 3.24+ 要 Java 11、3.31+ 要 Java 17
- adapter 在 Remote-SSH 窗口下运行在 UI 宿主（本地 Windows/Mac/Linux），无法直接读盘上的远端源文件
- 现有 `VariableFormatter` 已支持 `registerExpandable` 给 ObjectReference 注册 ref，并随 `continue/step/disconnect` 自动 `clear()`
- 现有 `LocalIndexer` 在索引时已 `vscode.workspace.fs.readFile` 拿到源文件全文（在 `IndexManager.ts:74` / `LocalIndexer.ts:135`），提取 FQN 后丢弃

## Goals / Non-Goals

**Goals:**
- Java 栈帧的表达式语法全覆盖 JLS 16 形态（cast、数组分配、泛型 new、class literal、数组初始化器）
- Kotlin 栈帧的表达式语法在现有自研 Lexer/Parser 基础上增加糖层（`?.`、`!!`、字符串模板）
- 双前端产出统一 `ExprNode` IR，共享 `NameResolver` 与 `RuntimeEvaluator`
- 短名解析基于 import 候选集（imports 在 TS 索引期提取，随索引下发至 adapter）
- 求值结果为可展开引用（ObjectReference / 非空数组 → `variablesReference > 0`），体验与变量面板一致
- 重载解析支持 widening、装箱、过滤 synthetic/bridge；歧义显式报错列出候选签名
- 任务排序让每一步独立可回滚；最小可发布切片（A2 D30 接通）可单独提前

**Non-Goals:**
- P3 精确模式：用户提供 classpath（android.jar + 依赖 jar）时切换 ASTParser `setEnvironment` 启用 binding、命中编译器特有语义——本变更不做
- JDT 3.24+ 升级到 Java 11/17 宿主——本变更不触碰，adapter JVM 目标维持 1.8
- 远程窗口反向通道（adapter → VSCode 请求源文件）——本变更不建，避免依赖 VSCode 私有 reverse request 能力
- 语句级表达式（`if`、`for`、`while` 等）——本变更仅做表达式形态
- 编译期 snippet 注入目标进程——D25 已封死此路线（ART 的 JDWP 不支持向运行中进程定义新类）

## Decisions

### 1. JDT 版本选型：`org.eclipse.jdt:org.eclipse.jdt.core:3.23.0`

- **选择**：3.23.0
- **理由**：JDT Core 自 3.24.0（2021）起要求 Java 11 运行时，3.31.0（2023）起要求 Java 17。`adapter/build.gradle.kts` 的 `sourceCompatibility = VERSION_1_8` 把宿主 JVM 目标钉在 1.8（用户 `javaPath` 配置默认 8+），无法升级。3.23.0 是最后一个 Java 8 兼容版本（BREE=JavaSE-1.8 已实测），AST 最高支持 JLS 14（JLS15+ 属于 3.24+），对 Android 调试表达式场景充分覆盖（实施时已从 JLS16 修正为 JLS14）
- **备选**：
  - 升级 adapter JVM 目标到 17 → 拒绝：把兼容范围从"用户 JDK 8+"缩到"用户 JDK 17+"，破坏现有用户群体
  - 切到更轻的 ECJ `org.eclipse.jdt.core.compiler:batch` → 拒绝：batch 是命令行编译器 API，不暴露 ASTParser，无法做语法前端翻译
  - 完全自研 JLS 解析器 → 拒绝：JLS 边缘场景（cast 与泛型交互、菱形、注解）维护成本高
- **打包**：`com.gradleup.shadow` 已配置 `mergeServiceFiles`，把 JDT Core 及其 Eclipse 传递依赖（`org.eclipse.core.runtime`、`org.eclipse.core.resources`、`org.eclipse.text` 等）打入 `adapter.jar`；adapter.jar 体积从约 4MB 增长到约 24MB，落在构建产物体量可接受范围

### 2. 索引携带 imports，不建源文件文本通道

- **选择**：TS 索引期提取 import 声明（Java/Kotlin），经 `custom/syncSourceIndex` 下发至 adapter `FileEntry.imports`；adapter 不向 VSCode 反向请求源文件
- **理由**：
  - adapter 运行在 Remote-SSH 的 UI 宿主上，无法直接读盘上的远端源文件；DAP reverse request 不可依赖
  - `LocalIndexer` 已 `vscode.workspace.fs.readFile` 拿到全文（已验证 `IndexManager.ts:74`），顺路做正则扫描 import 零额外 IO
  - import 列表体积远小于源文件全文：典型 Java 文件 import < 30 个，total < 1KB/文件
- **备选**：
  - 推送全文源文件到 adapter → 拒绝：远程窗口读不到本地盘；wire 体积爆炸
  - adapter 在 evaluate 时按需发 reverse request → 拒绝：VSCode 对自定义 reverse request 支持不可靠（实测易失败）
  - 仅靠 `vm.classesByName(shortName)` 扫描所有同名类 → 拒绝：性能与歧义（同名类无法选哪个）

### 3. Adapter 包结构：`parser/` → `eval/`

- **选择**：新增 `com.liux.android.debugger_adapter.eval` 包；Kotlin 前端置于子包 `eval.kotlin`，运行时层 `eval.RuntimeEvaluator`，共享 IR `eval.ExprNode` 置于 `eval.common`
- **理由**：§11.4 规划的双前端 + 统一 IR + 共享运行时层布局直接映射到包结构；`RuntimeEvaluator` 与 `KotlinFrontend` 互不依赖（构造期由 `ExpressionEvaluator` 注入）；Kotlin 前端在文件层易于被 Java 前端（P2）替换
- **搬迁清单**：
  - `parser/Ast.kt` → `eval/common/ExprNode.kt`（同时扩展节点：Cast / ArrayAllocation / ClassLiteral / ArrayInitializer）
  - `parser/Tokens.kt` → `eval/kotlin/Tokens.kt`
  - `parser/Lexer.kt` → `eval/kotlin/Lexer.kt`（扩展支持 `?.` `!!` 算符）
  - `parser/Parser.kt` → `eval/kotlin/Parser.kt`（增加 SafeCall / NotNull / StringTemplate 解析）
  - `parser/Evaluator.kt` → `eval/RuntimeEvaluator.kt`（移除大写启发式、改用 NameResolver；新增静态字段/装箱/歧义报错；移除对 `frame` 的强耦合以支持 front-end 注入的 import 候选集）
- **新增**：
  - `eval/JavaFrontend.kt`：JDT ASTParser + AST→ExprNode 翻译器
  - `eval/NameResolver.kt`：短名→FQN 解析器，接收 `(StackFrame, SourceIndex, FileEntry)` 作为上下文
  - `eval/LanguageDetector.kt`：`detectLanguage(frame, sourceIndex): Language`
  - `eval/KotlinSugar.kt`：SafeCall/NotNull/StringTemplate 三个 ExprNode 节点 + 解析分支
  - `jdi/ExpressionEvaluator.kt`：升格为分发器（保留现有 `evaluate` / `evaluateExpression` / `resolveExpression` API，向后兼容 log point / condition 调用方）
- **公共导入**：`eval.common.ExprNode` 被前端、RuntimeEvaluator、NameResolver 共同依赖（无循环）

### 4. JDT 前端：`ASTParser.K_EXPRESSION` 模式 + AST→ExprNode 翻译器

- **选择**：
  ```kotlin
  val parser = ASTParser.newParser(AST.JLS14)  // 3.23.0 的最高 JLS（JLS15+ 在 3.24+）
  parser.setSource(expression.toCharArray())
  parser.setEnvironment(null, null, null, true)  // standalone 空环境
  parser.setKind(ASTParser.K_EXPRESSION)
  val ast = parser.createAST(null) as Expression
  // 翻译 Expression → ExprNode
  ```
- **理由**：`K_EXPRESSION` 只解析单个表达式，K_COMPILATION_UNIT 需要源文件全文且走编译路径；standalone 空环境不依赖 classpath，符合 D26（无 classpath 自足）
- **翻译器实现**：
  - 直接 AST→ExprNode 节点映射（`CastExpression → ExprNode.Cast`、`ArrayCreation → ExprNode.ArrayAllocation` 等）
  - 名字引用 `SimpleName` / `QualifiedName` 暂不解析为 FQN（`NameResolver` 在 RuntimeEvaluator 求值时按需短名→FQN）
  - JDT 类型字面量 `TypeLiteral` → `ExprNode.ClassLiteral`
  - 数组初始化器 `ArrayInitializer` → `ExprNode.ArrayInitializer`（运行时按元素类型构造数组）

### 5. `ExprNode` 节点扩展

新增节点（Java 前端会产出，旧 Kotlin 前端不产出）：

| 节点 | 描述 | RuntimeEvaluator 处理 |
|------|------|---------------------|
| `Cast(target, typeName)` | `(Foo) x` | 求 `target`，校验运行时类型是否可赋给 `typeName`（不可赋则抛 ClassCastException 包装错误）；Java 语义上是 no-op 引用 cast |
| `ArrayAllocation(elementType, dimensions, initializer?)` | `new int[5]` / `new int[2][3]` | `ArrayType.newInstance(thread, componentType, sizes)` |
| `ClassLiteral(typeName)` | `Foo.class` | `vm.classesByName(typeName).classObject()` → `ObjectReference` |
| `ArrayInitializer(elementType, elements)` | `new int[]{1,2,3}` | 创建 ArrayList 包装后传 `ArrayType.newInstance` + 逐元素 setValue |

Kotlin 糖层节点（Kotlin 前端产出）：

| 节点 | 描述 | RuntimeEvaluator 处理 |
|------|------|---------------------|
| `SafeCall(target, name, args?)` | `a?.b` / `a?.b(args)` | target 为 null 短路返回 null；否则正常求值 |
| `NotNull(target)` | `a!!` | target 为 null 抛 IllegalStateException 包装的 NPE；否则返回 target |
| `StringTemplate(parts, exprs)` | `"x=${a.b}"` | 拼接 `parts[0] + formatString(exprs[0]) + parts[1] + ...` |

### 6. 静态字段/方法解析：走 `ReferenceType` / `ClassType`

- **选择**：`ExprNode.Field` 与 `ExprNode.Call` 的 `target` 在求值时若结果是 `ClassType` 而非 `ObjectReference`，走静态路径
  - 静态字段：`type.getValue(field)` / `type.setValue(field, value)`
  - 静态方法：`type.invokeMethod(thread, method, args, INVOKE_SINGLE_THREADED)`
- **当前 `parser/Evaluator.kt:149-160` 的"大写 Var 启发式"删除**：改由 `NameResolver.resolveStaticMember(name)` 显式解析（`Math.max` → `ClassType(Math)` + 方法 `max`），语义更可预测

### 7. 重载解析与歧义显式报错

- **算法**：
  1. `type.allMethods()` 过滤 `!isSynthetic() && !isBridge()`，按名+参数数初筛
  2. 精确匹配（实参 JDI 类型 == 形参类型，含 null 形参兼容性）
  3. widening 匹配（byte→short→int→long→float→double）
  4. 装箱匹配（int ↔ Integer、long ↔ Long 等）
  5. 仍多于 1 个候选 → 报错，消息列出所有匹配签名
- **选择**：歧义显式报错（不选"参数数最少 + 派生最浅"启发式）
- **理由**：可预测；与 vscode-java-debug 在歧义时的行为一致（也是 error）；实现简单

### 8. 装箱匹配

- **形参-实参类型表**（按形参查实参能否匹配）：
  - 形参为 `int` ↔ 实参为 `Integer`（装箱/拆箱）
  - 形参为 `Integer` ↔ 实参为 `int`
  - 形参为 `long` ↔ 实参为 `Long`，`double ↔ Double` 等所有基本类型 ↔ 包装类对
- **结合 widening**：装箱的 widening 链 `int→long` 与 `Integer→Long` 并存，形参 `long` 既能接受 `int`（widening）也能接受 `Integer`（装箱+widening），保留最高优先级候选

### 9. D30 引用生命周期：复用 `VariableFormatter.clear()`

- **选择**：evaluate 返回的 ref 与 Locals ref 同等对待，随 `continue/step/disconnect` 自动失效
- **理由**：
  - 现有 `varFormatter.clear()` 已在 `DapServer.kt:593/600/608/616/1240` 五处正确调用
  - 失效后展开返回空（`VariableFormatter.expand` 找不到 ref 返回 `emptyList()`），与现有 locals 行为一致
  - 无需为 evaluate ref 单独维护 namespace / TTL / 显式失效

### 10. 任务排序与依赖

按依赖深度排序（每一步独立可合并 / 可回滚）：

```
T1 架构分拆         ← 零行为变化，最稳起点
  └─→ T2 D30 接通   ← 最小可发布切片（用户立即收益）
        └─→ T3 索引携带 imports
              └─→ T4 NameResolver
                    ├─→ T5 JDT Java 前端
                    │     └─→ T6 D27 语言分流
                    │           └─→ T7 运行时补齐
                    └─→ T7 运行时补齐 (静态成员 + 装箱 + synthetic/bridge + 歧义报错)
                          └─→ T8 Kotlin 糖层 (A5: 最后)
```

T1-T2 切分逻辑：T1 是纯搬迁，行为完全不变；T2 引入新行为（D30）。两者可独立 review，把 T1 作为 low-risk 先行 PR。

## Risks / Trade-offs

| 风险 / 取舍 | 缓解 |
|------------|------|
| JDT 3.23（2021）较老，传递依赖若干 Eclipse JAR；mavenCentral 锁版本需谨慎 | shadowJar 已配 `mergeServiceFiles`；构建时验证依赖树；如异常可锁 `org.eclipse.platform:org.eclipse.core.runtime:3.23.100` 等传递坐标 |
| adapter.jar 体积从 4MB 增至 24MB（+20MB） | 落在构建产物体量可接受范围；如不可接受可考虑 lazy-load 分离 jar——但当前不做 |
| 索引条目加 `imports` 字段后 `custom/syncSourceIndex` 体积增加（典型 < 1KB/文件） | 远小于既有的路径/URI 字段；wire 形态是 additive（旧 consumer 忽略未知字段） |
| D27 链第二步（`sourceName` 后缀）可能与 source index 命中冲突 | 链上第一步优先；只有声明类在 SourceIndex 查不到时才走 sourceName——冲突时取 SourceIndex 命中（更可靠） |
| 重载歧义显式报错可能让 IDEA/IDEA 习惯的"智能选最具体重载"用户感到不便 | 行为可预测；与 vscode-java-debug 一致；可后续按反馈改为"歧义时选最具体" |
| `vm.classesByName(shortName)` 在大型应用（> 10K 加载类）中可能慢 | 候选集已通过 `FileEntry.imports` + 同文件类型 + 同包 + java.lang 限制到 O(< 100)；classesByName 的内部索引 O(1) |
| 现有 `parser/Evaluator.kt:149-160` 删除"大写 Var 启发式"可能回归旧行为（用户原本能用 `Math.max(1,2)` 走启发式） | T4 NameResolver 实现的 java.lang 链覆盖；T4 验证矩阵显式包含 `Math.max(1, 2)` 走新链路 |
| T8 Kotlin 糖层在 T7 之后做：用户在早期阶段 evaluate `user?.name` 会因 parser 不支持 `?.` 而失败 | 文档化：糖层未落地时 Kotlin 栈帧表达式遵循 Java 语法（无 `?.` `!!` 糖） |
| 表达式求值期间的 `InvocationException` 暴露内部类型给用户 | 已有 `parser/Evaluator.kt:178-181` 的"包装异常 + 暴露异常类名"逻辑；保持不变 |

## Migration Plan

1. **构建产物**：`./gradlew :adapter:shadowJar` 触发 `copyJarToExtension` 把新 adapter.jar 复制到 `extension/resources/adapter.jar`
2. **部署**：用户重启 VSCode 即可；extension 加载新 adapter.jar（`SessionContextImpl.resolveAdapterJar` 读 `extension/resources/adapter.jar`）
3. **回滚**：revert PR 即可；旧 adapter.jar 仍可从 git history 还原
4. **wire 兼容性**：`custom/syncSourceIndex` payload 加 `imports?` 字段是 additive（TS 发、adapter 收；旧 TS 发的 payload 缺 `imports` 也能被新 adapter 兼容——只损失 NameResolver 的 import 链能力）
5. **DAP 兼容性**：`evaluate` 响应从 `variablesReference: 0` 改为按需返回非零值；旧 VSCode 收到非零值正常处理（这是 DAP 标准用法）

## Open Questions

1. **JDT AST 解析的 import 是否需要从 AST 反推**（替代 TS 端提取）？当前选 TS 端——adapter 端不重复提取。但 JDT 解析表达式时如果遇到 unresolved 类型短名，是否回退到运行时 `vm.classesByName` 兜底？默认回退（已有逻辑）；不需要额外决策。
2. **`StringTemplate` 的表达式分隔边界**：Kotlin 字符串模板支持嵌套（如 `"${list.map { it.toString() }}"`）。本变更 P1 阶段只实现非嵌套的 `${expr}` 形式（expr 内不再解析字符串），文档化为已知限制。
3. **多维数组分配 `new int[2][3]` 的语义**：当前节点定义为 `ArrayAllocation(elementType="int", dimensions=[2, 3])`，运行时构造二维数组（`int[][]`）。若 `dimensions` 含运行时表达式（如 `new int[n][3]`），需 RuntimeEvaluator 现场求值——T7 实现时确认。
