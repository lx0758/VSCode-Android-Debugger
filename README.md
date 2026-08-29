# Android Java Debugger

VSCode 扩展 + Kotlin JDI 调试适配器, 通过 JDWP attach 到 Android framework / 系统应用的 Java 进程, 在 AOSP 源码 workspace 中实现断点、步进、栈与变量查看。

## 支持场景

| 场景 | 描述 | 状态 |
|------|------|------|
| A 本地 | 源码、VSCode、adb、设备都在同一台机器 | ✓ |
| C1 Remote-SSH | 源码与 VSCode 在远程 (Remote-SSH), 设备+adb 在本地 | ✓ |

不支持的场景: B (远程设备)、C2 (远程 adb)、D (无线 adb)、E (远程网络设备)。

## 系统要求

- JDK 8 或更高 (用于构建和运行 Kotlin 调试适配器) — **只需在本地机器上**
- Android SDK Platform Tools (`adb` 命令) — **只需在本地机器上**
- 调试设备: 启用 USB 调试的 eng/userdebug 设备 (需 root 才能 attach framework 进程)
- VSCode 1.85+
- Gradle wrapper 已随仓库签入, 无需手动安装 Gradle

## 构建

```bash
# 首次: 安装 TypeScript 等扩展依赖 (只在 extension/ 下需要)
npm run install:extension

# 一键构建: Kotlin adapter jar + TypeScript 扩展
npm run build

# 仅构建 adapter (Kotlin jar)
npm run build:adapter

# 仅编译扩展 (TS)
npm run build:extension

# 打包 .vsix (扩展离线安装包)
npm run package

# 跑测试
npm test
```

输出位置:
- adapter jar → `extension/resources/adapter.jar`
- 扩展 JS → `extension/out/`
- VSIX → `extension/debugger-for-android-0.1.0.vsix`

## 本地开发

仓库自带 `workspace/sample.code-workspace` 作为本地调试用的示例 workspace (默认指向 `workspace/sample` 和 `../../android`)。由于这个文件里的路径是环境相关的, **不应被提交到仓库** — clone 后请运行下面这条命令, 让 git 永久忽略它的本地改动:

```bash
git update-index --assume-unchanged ./workspace/sample.code-workspace
```

(Windows PowerShell: `git update-index --assume-unchanged .\workspace\sample.code-workspace`)

需要恢复追踪时:

```bash
git update-index --no-assume-unchanged ./workspace/sample.code-workspace
```

## 使用流程

1. 在 VSCode 中打开包含 AOSP 源码的工作区 (单文件夹或多文件夹 workspace)
2. 对需要调试的源码目录执行命令 `Android Debug: Mark as Debug Source Root`
3. 连接 Android 设备 (`adb devices` 应能看到设备)
4. 执行命令 `Android Debug: Debugger with Android`, 选择目标进程
5. 在源码中设置断点, 开始调试

## Remote-SSH (场景 C1) — 双扩展加载模式

本扩展声明 `extensionKind: ["ui", "workspace"]`, VSCode 会在两端都激活:

- **本地 ext host (kind=ui)**: 负责所有用户 UI、`adb` 命令、`adb forward`、启动 Java Adapter
- **远程 ext host (kind=workspace)**: 仅暴露一个 `buildIndex(sourceDirs)` API, 通过原生 FS 读取构建源索引

跨主机调用通过 `vscode.extensions.getExtension('anomaly.debugger-for-android').exports.buildIndex(...)` 完成 (VSCode 官方模式, 由 VSCode 代理跨 host 调用)。Java Adapter 始终运行在本地, JDWP socket 是 `127.0.0.1:<随机端口>` → 本地 `adb forward` → 设备。

**无需任何手动配置**: 无 SSH 隧道、无反向端口、无本地 SSH server、无 sshfs 挂载。如果远程 ext 激活失败, 本地 ext 自动降级到跨 host read 模式 (会慢一些但仍可用)。

## 项目结构

```
debugger-for-android/
├── extension/           # VSCode 扩展 (TypeScript)
│   ├── src/
│   │   ├── adb/         # adb 路径探测、命令执行、JDWP 转发
│   │   ├── source/      # 源根管理、共享 TS SourceIndexer
│   │   ├── debug/       # Adapter 启动器、DAP 客户端
│   │   ├── ui/          # 进程选择器、状态栏 (含 indexer 模式)
│   │   ├── commands/    # attach / markSourceDir
│   │   ├── util/        # 日志、配置、JDK 检测、临时端口分配
│   │   ├── extension.ts          # dispatcher: 根据 remoteName 加载 local 或 remote
│   │   ├── extension.local.ts    # 本地 ext (kind=ui): UI + adb + Adapter
│   │   ├── extension.remote.ts   # 远程 ext (kind=workspace): buildIndex API
│   │   └── i18n/        # 多语言 (zh-CN 默认 + en 翻译)
│   └── resources/       # 编译产物: adapter jar
├── adapter/             # 调试适配器 (Kotlin + Gradle)
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/main/kotlin/
│       └── com/anomaly/debugger/
│           ├── Main.kt
│           ├── server/DapServer.kt
│           ├── jdi/     # JDI 连接、事件、断点、步进、表达式求值
│           ├── source/  # SourceIndexer + SourceIndex (纯存储, 无文件系统访问)
│           ├── protocol/Logging.kt
│           └── util/    # DTO 工厂
├── package.json         # 根 npm scripts: build / build:adapter / build:extension / package / test
└── openspec/            # OpenSpec 规范与变更
```

## 配置项

- `debugger-for-android.adbPath`: `adb` 路径, 默认 `auto` 自动探测
- `debugger-for-android.sourceDirs`: 调试源目录路径列表 (相对工作区文件夹)
- `debugger-for-android.index.maxFiles`: 源索引允许的最大文件数, 默认 10000; 超过时会预估耗时并询问是否继续

## 故障排除

### "未找到 Java" / "JDK version too low"
确保 `JAVA_HOME` 设置或 `java` 在 PATH 中, 且版本 ≥ 8 (本地机器上):
```bash
java -version
```

### "adb: no devices/emulators found"
- 检查 USB 调试是否启用
- 检查 `adb devices` 是否能看到设备
- 如显示 `unauthorized`, 在设备上点击允许 USB 调试

### "process not debuggable"
Framework 进程需要 root 才能 attach。在设备 shell 中:
```bash
su
setprop ro.debuggable 1
restart adbd
```

### Remote-SSH 下索引特别慢
状态栏会显示 "Remote indexer: fallback"。可能原因:
- 远程 ext 还未激活 (首次 attach 可能)
- 远程 VSCode ext host 临时故障

### 断点显示 `verified: false`
- 源码与运行时代码版本不一致 (尝试重新 sync 源码)
- 源码未在源根中 (运行 `Mark as Debug Source Root`)
- 进程已被优化 (需在源码 `-g` 编译)

## 已知限制 (MVP)

- 不支持 lambda 调试 (JDK 8 的 JDI 限制)
- 不支持条件断点的复杂表达式 (仅 field 比较)
- 不支持方法断点
- 不支持多设备/多进程
- 不支持原生代码调试
- 不支持 Java 版本不匹配的自动检测

## 许可证

TBD
