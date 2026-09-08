# 提案：ADB 交互层健壮性清理

## Why

ADB 交互层（`extension/src/adb/`）存在三处健壮性缺陷：`captureJdwpPids` 在子进程 spawn 失败时不清理定时器与进程、Promise 收口语义不明确；`execWithStdin` 向已退出的子进程写 stdin 会触发未处理的 EPIPE 异常，可能崩溃扩展宿主；`activate()` 中残留一条绕过 Logger 体系的 `console.log` 调试输出。前两者位于每次附加调试会话必经的热路径上。

## What Changes

- `AdbDevice.captureJdwpPids`：显式收口 Promise 结算（单次 settle），spawn 失败时清理捕获定时器并终止子进程，消除 `proc.stdout!` / `proc.stderr!` 非空断言
- `AdbRunner.execWithStdin`：为 stdin 流添加 `'error'` 监听，子进程提前退出（EPIPE）时不再产生未处理异常
- `extension.ts`：删除冗余的 `console.log` 调试残留（其输出内容已被相邻的 `Logger.info` 覆盖）

## Capabilities

### New Capabilities

（无——不引入新功能面）

### Modified Capabilities

- `android-java-debugger`: 收口两个失败模式的行为定义——(1) Process Discovery：`adb jdwp` 采集子进程 spawn 失败时，枚举以明确错误失败并清理定时器/子进程，而非悬挂或泄漏资源；(2) 命令执行：向已退出的子进程写入 stdin 时不产生未处理异常，扩展宿主不受影响。

## Impact

- `extension/src/adb/AdbDevice.ts`：重构 `captureJdwpPids` 内部实现（~20 行）
- `extension/src/adb/AdbRunner.ts`：`run()` 中 stdin 写入增加错误防护（~5 行）
- `extension/src/extension.ts`：删除 4 行调试残留
- 无 API 破坏性变更，无依赖变更
