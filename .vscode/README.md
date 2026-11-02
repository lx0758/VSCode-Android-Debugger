# Debugger for Android

VSCode extension for debugging AOSP framework / system app Java code via JDWP.

## Develop

1. 在 VSCode 中打开本仓库根 (即 `code /path/to/vscode-android-debugger`)
2. 运行配置下拉框选择 `Run Extension` (或按 `F5`)
3. 弹出新的 [Extension Development Host] 窗口, 加载 `extension/` 子目录里的扩展, EDH 默认打开 `sampleWorkspace/` 目录
4. 主窗口停在断点位置时, 表示扩展本身正在被调试

构建任务通过 `Terminal → Run Task` 或 `Ctrl+Shift+B` 触发. 所有 task 都用 `type: "npm"` 直接引用根 `package.json` 的 scripts (单一来源):

- `build: all` → `npm run build` (Kotlin adapter + copy jar + TS 扩展)
- `gradle: shadowJar (adapter)` → `npm run build:adapter` (Java 直接调 wrapper jar, 跨平台)
- `copy: adapter jar` → `npm run copy:adapter` (Node 跨平台文件复制)
- `tsc: compile (extension)` → `npm run build:extension`
- `tsc: watch (extension)` → `npm run tsc:watch` (后台)
- `package: vsix` → `npm run package`

要改构建命令时, 改根 `package.json` 的 `scripts`, `tasks.json` 自动跟上.

## 关键改动说明
- `--extensionDevelopmentPath=${workspaceFolder}/extension` — 因为 package.json 在 extension/ 子目录里
- `--disable-extensions` — 避免其它已安装扩展干扰 (EDH 默认加载所有)
- `preLaunchTask: "build: all"` — F5 前先全量构建

## 测试 Adapter 单独运行

运行配置 `Run Adapter (Standalone)` 直接启动 adapter jar (无 DAP 客户端, 监听 stdin), 可用来验证 adapter 启动和初始日志。