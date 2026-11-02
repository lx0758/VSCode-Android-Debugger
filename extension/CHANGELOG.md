# Changelog

## 0.1.0 - 2026-11-02

- 首个版本: VSCode 扩展 + Kotlin JDI 调试适配器, 支持通过 JDWP attach 到 Android framework / 系统应用进程
- 断点、步进 (step in / out / over)、调用栈与变量查看
- 调试控制台表达式求值: 使用 Eclipse JDT 解析, 同时支持 Java 强转/全路径引用与 Kotlin `as` / `is` / `?:` 语法
- 调试源目录标记 (支持多目录) 与增量源码索引
- 支持本地与 Remote-SSH (场景 C1) 两种调试场景
