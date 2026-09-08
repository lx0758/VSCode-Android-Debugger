# 设计：ADB 交互层健壮性清理

## Context

- `AdbDevice` 是会话级对象（每个调试会话一个实例），`captureJdwpPids` 通过 `AdbRunner.start()` 直接持有 `ChildProcess`，自行管理捕获定时器与 `stdout` 累积。
- `AdbRunner.run()` 是所有一次性 adb 命令的唯一通道（`exec` / `execWithStdin` 共用），spawn 时 stdio 全部配置为 `pipe`。
- 动机见 proposal.md - Why，此处不再复述。

## Goals / Non-Goals

**Goals:**

- 三个修复点互不耦合，可独立实现、独立验证
- 失败路径的清理逻辑集中、可一眼读出，不依赖 Promise 单次结算的隐式语义做正确性保证

**Non-Goals:**

- 不改动 `AdbRunner` / `AdbDevice` 的对外接口签名
- 不为 `execWithStdin` 寻找新的调用方（防护是防御性加固，不是功能启用）
- 不引入测试框架（项目目前没有测试设施，属于独立变更）

## Decisions

### 决策 1：`captureJdwpPids` 用显式 `settled` 标志收口，清理逻辑集中到单一函数

- **做法**：提取 `cleanup()`（清定时器 + kill 子进程），`error` 与 `exit` 两个分支都先检查 `settled` 标志再结算，任一分支结算后调用 `cleanup()`；`proc.stdout` / `proc.stderr` 改用空值检查（stdio 配置为 pipe 时理论非空，防御性兜底）。
- **理由**：Promise 天然只结算一次，但显式 `settled` 守卫让「只结算一次」成为代码里可见的契约（与规格的 "settle exactly once" 场景直接对应），而不是依赖运行时隐式语义。
- **备选方案**：把 `exit` 事件换成 `close`（spawn 失败时 Node 会先发 `error` 再发 `close`）。否决——事件语义差异会牵连正常路径行为，且仍需守卫解决先后顺序问题，收益更低。

### 决策 2：stdin 防护采用流级 `'error'` 监听，而非包裹写入调用

- **做法**：在 `run()` 内写入 stdin 前为 `proc.stdin` 挂 `'error'` 监听，命中时记 `Logger.debug` 并返回（吸收 EPIPE）；命令结果仍由 `exit` 事件结算。
- **理由**：EPIPE 是异步流事件，`try/catch` 只能捕获同步 throw，`write()` 回调也不覆盖流上的 `'error'` 事件；挂监听器是 Node 处理子进程 stdin 写失败的规范做法。守护点放在 `run()` 内部，`exec` / `execWithStdin` 两条路径（含未来新调用方）自动受益。
- **备选方案**：在每次 `write()` 后检查 `proc.stdin.destroyed` 跳过写入。否决——只覆盖「已销毁」的先验状态，无法防御写入与子进程退出之间的竞态。

### 决策 3：`console.log` 残留直接删除，不迁移

- **理由**：其输出的 `sessionId` / `remoteName` 已被紧邻的 `Logger.info`（第 42、44 行）覆盖，无信息增量；迁移到 Logger 只是无意义搬运。

## Risks / Trade-offs

- [`settled` 守卫增加约 6 行样板代码] → 换取失败路径显式可读，收益大于成本；样板集中在单个函数内
- [stdin `'error'` 监听会吸收真实写入错误，可能掩盖调用方 bug] → 以 `Logger.debug` 记录留痕；该 API 目前无调用方，暴露面为零
- [kill 已死进程可能抛出] → 沿用现有 `try/catch` 吞掉的模式（原代码已有此约定）

## Migration Plan

纯内部加固，无迁移步骤；回滚即还原三个文件的改动（可按文件独立回滚）。
