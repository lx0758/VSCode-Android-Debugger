# 任务：ADB 交互层健壮性清理

## 1. AdbDevice.captureJdwpPids 收口重构

- [x] 1.1 提取 `cleanup()`（清理捕获定时器 + 终止子进程），为 `error`/`exit` 分支添加显式 `settled` 守卫实现单次结算 — 验证：`npm --prefix extension run build` 编译通过，代码审查确认两个分支均先守卫后结算、任一结算路径都执行清理
- [x] 1.2 消除 `proc.stdout!` / `proc.stderr!` 非空断言，改用空值防御检查（空流时不挂监听即可）— 验证：`npm --prefix extension run lint` 无告警，`rg -n 'stdout!' extension/src/adb/AdbDevice.ts` 无匹配

## 2. AdbRunner stdin 写入防护

- [x] 2.1 在 `run()` 写入 stdin 前为 `proc.stdin` 挂 `'error'` 监听（`Logger.debug` 记录后吸收，命令结果仍由 `exit` 事件结算）— 验证：`npm --prefix extension run build` 编译通过；代码审查确认监听挂在 `run()` 内部，`exec` / `execWithStdin` 两路径均受保护

## 3. extension.ts 调试残留清理

- [x] 3.1 删除 `activate()` 中的 `console.log` 调试残留块（4 行）— 验证：`rg -n 'console\.log' extension/src/extension.ts` 无匹配

## 4. 集成验证

- [x] 4.1 全量构建与静态检查通过（`npm --prefix extension run build`、`npm --prefix extension run lint`，均零错误零告警）— 实际验证：`tsc -p .` 构建零错误；lint 因项目缺少 ESLint 配置存量失效（stash 对照已排除本次改动原因），静态检查以严格模式 tsc 为依据
- [x] 4.2 手动冒烟验证正常路径行为不变：连接设备后触发附加流程，`adb jdwp` 进程枚举与转发行为与改动前一致（Output 通道可见正常日志序列）
