# Android Java Debugger (VSCode Extension)

## Why

AOSP framework 和系统 App 的 Java 层代码调试在 VSCode 上缺少合适的工具:

- `vscode-java-debug` 设计为本地 JVM 启动/attach, 不支持远端 JDWP socket attach 到 Android 进程
- `vscode-android` 系列 (RedHat 等) 主要做构建/部署, 不做深度调试
- Android Studio 功能完整但重, 不适合 VSCode 工作流

AOSP 开发者需要: 在 VSCode 里对工作区任意 .java/.kt 设断点, 通过 `adb jdwp` 发现设备可调试进程, attach 到 framework / 系统 App 进程并正常查看 stack/locals/step, 命中源文件时跳转。

## What Changes

新增 VSCode 扩展 `vscode-android-debugger`, 由两部分组成:

- **TypeScript extension** (`extension/`): UI 集成 (命令注册 / QuickPick / 标记根管理 / 配置 / 源码索引)
- **Java debug adapter** (`adapter/`): DAP server + JDI 客户端, 通过 `adb forward` 桥接设备 JDWP socket

关键能力:

- 新增命令 `Debugger with Android`, 触发后枚举设备上可调试进程 (`adb jdwp`)
- 选择进程后, 自动 `adb forward tcp:<port> jdwp:<pid>` 并启动 Java adapter attach
- 支持普通断点、日志断点 (log breakpoint) 和简单条件断点
- 支持 step over / step into / step out
- 支持 stack trace、局部变量、线程查看
- 源码映射: 标记根 + 断点文件自动加入 + 运行期类解析启发式
- 支持多根 workspace, 标记根配置存放在 `ExtensionContext.workspaceState` Memento 里 (per-workspace SQLite, 不用 `.vscode/settings.json`)

### 跨机器部署支持 (Remote-SSH)

AOSP 开发者常见工作模式是 Remote-SSH (源码在远程 Linux 服务器, 设备 USB 接在本地笔记本)。本扩展**首要场景即覆盖此模式**, 仅支持设备+adb 都在本地 (或全本地) 的两种拓扑:

| 场景 | 源码位置 | adb | 设备 | MVP 支持 |
|------|---------|-----|------|---------|
| A 全本地 | 本地 | 本地 | 本地 USB | 默认, 开箱即用 |
| C1 **Remote-SSH, 设备+adb 都在本地** | 远程 | 本地 | 本地 USB | **MVP 重点, 双 ext 协同** |

不在 MVP 范围 (明确不做): 设备或 adb 在远程服务器场景, 无线 adb 场景。

**场景 C1 的核心机制: 本地 ext + 远程 TS SourceIndexer 协同**

扩展声明 `extensionKind: ["ui", "workspace"]`, 在 Remote-SSH 下两端都激活, **但职责严格分工**:

```
+-------------------+                         +---------------------------+
| 本地 (笔记本)        |                         | 远程 (Linux)               |
|                   |                         |                            |
| VSCode UI         | <-- VSCode 内置隧道 --> | VSCode Server              |
| Local Ext (kind=ui)|  extensions.getExtension|  - workspace = aosp/        |
|  ├─ 命令 + QuickPick|  ().exports.buildIndex  |  - 30k .java files          |
|  ├─ adb jdwp / fwd  |  (跨 host IPC)         |                            |
|  │  tcp:LP jdwp:PID | <--------------------> | Remote Ext (kind=workspace)|
|  ├─ Java Adapter    |                         |  └─ buildIndex() API        |
|  │  (local child)   |                         |     - 本地 FS walk          |
|  └─ 调 remote ext   |                         |     - readFile (本地, 快)   |
|     拿 entries      |                         |     - 解析 class FQN        |
|                    |                         |     - 返回 entries[]        |
| Android 设备 (USB) |                         |                            |
+-------------------+                         +---------------------------+
```

- **本地 ext (kind=ui)**: 用户 UI + adb + Java Adapter + 调远程 ext 拿索引 entries
- **远程 ext (kind=workspace)**: **只暴露 `buildIndex(sourceDirs)` API**, 跑在远程, 直接读本地 FS
- **跨 host IPC**: VSCode 官方推荐 `vscode.extensions.getExtension().exports` 模式, 走 VSCode Server↔Client 内置隧道
- **Fallback**: 远程 ext 加载失败时, 本地 ext 降级用 `vscode.workspace.fs.readFile` 跨 host 读 (慢但能用, 跟 v4 一样)

**架构取舍 (5 版迭代总结)**:

| 版本 | 做法 | 问题 |
|------|------|------|
| v1 | 远程 SSH exec 到本地 adb | 本地要 SSH server (Windows 默认关) + node-ssh 解析用户私钥失败 |
| v2 | Local Agent + 9801 JSON-RPC | 用户手动跑 ssh 命令 + 自定义 RPC 协议, 过度工程 |
| v3 | 双 ext host + 自动 ssh -R | ssh -R 生命周期 + 跨 host DAP 路由 + 远程 JDK, 复杂 |
| v4 | 全本地 + TS SourceIndexer | 跨 host FS read 慢 (5-50ms/文件), 10k 文件要 1-8 分钟 |
| **v4-alt (本次)** | **本地 ext + 远程 ext (仅 buildIndex) + `extensions.getExtension().exports` IPC** | **多一个远程 ext 模块 (~50 行), 换来 10-100x 索引速度提升** |

**v4-alt 的关键洞察**:
- Java Adapter 不需要直接读源文件 (v4 已有)
- 远程 ext 只暴露一个 `buildIndex()` 函数, 不挂任何生命周期, 不需要 ssh -R, 不需要跨 host DAP
- `extensions.getExtension().exports` 是 VSCode 官方跨 host IPC 机制, 微软自家扩展 (mssql, remote-containers) 全在用, 不是 hack
- Fallback 路径保证远程 ext 崩了也不影响主流程

**性能对比**:

| Scope | v4 (跨 host read) | v4-alt (远程 ext 本地 FS) |
|-------|-------------------|---------------------------|
| `services` (3k 文件) | 30-150 秒 | 2-3 秒 |
| `base/core + services` (5k) | 50-250 秒 | 3-5 秒 |
| `frameworks/base` (10k) | 100-500 秒 ⚠️ | 5-10 秒 |
| 整个 AOSP (30k) | 5-25 分钟 (需硬卡) | 15-30 秒 (可接受) |

**配置项**:
- `vscode-android-debugger.index.maxFiles`: 单次构建最多索引的文件数 (默认 10000), 防止误标记整个 AOSP 卡死扩展 (v4-alt 整个 AOSP 也才 30s, 兜底)
- **源目录列表**: 不再用 `ConfigurationTarget` 写 settings 文件, 改为 `ExtensionContext.workspaceState` Memento 持久化 (per-workspace 隔离, 不入 git, 单/多 folder 模式自动统一)
- ~~`sshMode` / `sshUser` / `sshIdentityFile`~~: 全部移除

**用户操作**: **零**. 跟场景 A 完全一致 — 打开工作区 → 标记 source dir → 点 attach → 选进程 → 调试. Remote-SSH 对用户透明.

**BREAKING**: 无 (新增项目, 不修改现有代码)。

## Capabilities

### New Capabilities

- `android-java-debugger`: 完整覆盖 AOSP framework / 系统 App 的 Java 层调试能力, 包括进程发现、JDWP attach、断点管理、源码映射、调试控制台

### Modified Capabilities

无 (项目为空白, 无现存 spec)。

## Impact

### 受影响代码 / 系统

- 新增仓库内容 (`extension/` TypeScript + `adapter/` Java)
- 不修改 AOSP 源码, 不修改 Android 设备系统
- 用户机器需安装 JDK 8+ (用于运行 adapter) 和 Android Platform Tools (adb)

### 受影响 API / 协议

- 实现 DAP (Debug Adapter Protocol) 子集, 适配 VSCode 调试器 UI
- 实现 JDWP (Java Debug Wire Protocol) 客户端 (基于 JDK 自带 `com.sun.jdi.*`)
- 调用 `adb` 命令行 (标准 Android SDK Platform Tools)
- 场景 C 通过双 ext 协同: 远程 ext 暴露 `buildIndex()` API 用本地 FS 走文件, 本地 ext 调用拿 entries; 跨 host IPC 走 `vscode.extensions.getExtension().exports` (VSCode 官方跨 host IPC)

### 新增依赖

- Java 端: 仅 `com.sun.jdi.*` (JDK 内置) + `com.google.gson:gson` (~600KB, JSON 编解码); **Kotlin 端 SourceIndexer 简化为纯 map 存储, 解析器搬到 TS 端**
- TS 端: 标准 VSCode extension API (无 `node-ssh`, 无 Local Agent, 无自定义 RPC)
- 用户环境: JDK 8+ (本地即可, 不要求远程) + Android SDK Platform Tools (adb) + 已 root 且为 userdebug/eng 的 Android 设备
- 场景 C 用户额外: 无 (Remote-SSH 由 VSCode 内置处理, 双 ext host 由 VSCode 自动管理)

### 不在 MVP 范围

明确推迟到后续阶段的能力:

- 复杂条件断点 (运算符 / 三元 / cast / 有参方法返回值比较)
- 表达式求值 REPL / Watch expressions
- 热代码替换 (Hot code replace)
- 多设备并发调试
- 跨进程跟踪 (binder 调用链)
- Native 层调试集成 (lldb-server)
- 反编译源码支持 (非 AOSP 场景)
- 源码/运行时版本不匹配的自动检测 (依赖用户保证对应)
- SourceIndex 跨 session 持久化缓存 (MVP 每次 session 重建, 用 `globalState` 缓存 + file watcher 增量失效放 V2)
- Remote Adapter 模式 (在远程跑 Java Adapter, 用于远程构建/低带宽场景), v4-alt 假定本地 JDK 可用

## Open Questions

实施阶段需要进一步验证/决定的事项:

1. **复杂表达式求值**: 简单字段/方法用 JDI, 复杂表达式 (运算符/三元) 是否需要内嵌 Janino 或自写 mini evaluator — 取决于用户实际使用频率
2. **Lambda 调试 UX**: ART 把 lambda 编成内部类 (`ClassName$$Lambda$1`), 断点能否命中、UI 怎么呈现, 需要在实施阶段实测
3. **SourceIndex 构建性能 (v4-alt)**: 远程 ext 直接读本地 FS (微秒级), 10k 文件预计 5-10 秒; Fallback 路径 (远程 ext 加载失败时降级到本地 ext 跨 host read) 退化为 v4 行为 (50-500 秒). 需要实施时实测确认
4. **双 ext 加载时序**: `extensionKind: ["ui", "workspace"]` 下, 本地 ext 和远程 ext 的 activation 时序由 VSCode 控制; 本地 ext 调 `extensions.getExtension().exports.buildIndex()` 时如果远程 ext 还没激活完, 需要等 — 实施时验证
5. **TS 端 class 解析器**: Kotlin 端用 regex 解析 `package` + `class` 声明, v4-alt 把解析器搬到 TS 端的**远程 ext** (仍跑在 V8), 需确认 Node 环境对 `.java`/`.kt` 文件的 `object`/`interface`/`sealed class`/`data class` 解析正确
6. **`index.maxFiles` 兜底**: v4-alt 整个 AOSP 也才 30 秒, 上限可放宽到 30000 (默认 10000 仍合理); 用户标更大目录时 UI 弹确认 + 估算时间
7. **adb 路径默认探测**: 跨平台常见路径 (/opt/android-sdk, ~/Android/Sdk, %LOCALAPPDATA%\Android\Sdk) 需要在实施阶段验证
8. **WSL + Windows Remote**: 用户如果是 WSL 跑 VSCode Server + Windows 当 local, 扩展两端跑在哪? 取决于哪个 ext host 被识别为 "workspace"。MVP 不专门处理, 文档化
