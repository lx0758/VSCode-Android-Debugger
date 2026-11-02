# Android 调试器

在 VSCode 中直接调试 Android Framework / 系统应用的 Java 层代码。通过 JDWP attach 到设备上的 Java 进程, 支持在 AOSP 源码 workspace 中打断点、单步、查看调用栈与变量。

## 功能

- **attach 到运行中的进程**: 通过 `adb` 找到目标进程并 attach, 无需重启设备
- **断点**: 支持行断点与方法断点, 命中后自动暂停
- **单步执行**: step in / step out / step over
- **变量与调用栈查看**: 展开对象字段、查看局部变量与调用帧
- **表达式求值**: 在调试控制台中求值, 支持 Java 强转与全路径引用, 以及 Kotlin 的 `as` / `is` / `?:` 语法
- **源码目录标记**: 标记后的目录参与断点匹配, 支持多目录
- **增量源码索引**: 只索引新增/变更的文件, 快速定位断点位置

## 支持的场景

| 场景 | 描述 | 状态 |
|------|------|------|
| A 本地 | 源码、VSCode、adb、设备都在同一台机器 | ✅ |
| C1 Remote-SSH | 源码与 VSCode 在远程 (Remote-SSH), 设备 + adb 在本地 | ✅ |

不支持: B (远程设备)、C2 (远程 adb)、D (无线 adb)、E (远程网络设备)。

## 环境要求

- **JDK 8 或更高** — 仅需安装在运行 VSCode 的机器上 (用于启动 Kotlin 调试适配器)
- **Android SDK Platform Tools** (`adb` 命令) — 仅需安装在运行 VSCode 的机器上
- **调试设备** — 启用 USB 调试的 eng/userdebug 设备 (attach framework 进程需要 root)
- **VSCode 1.87 或更高**

## 使用方法

1. 在 VSCode 中打开 AOSP 源码 workspace
2. 命令面板执行 `Android 调试器: 标记为调试源文件目录`, 把需要调试的源码目录标记上 (可多选)
3. 用 `adb devices` 确认设备已连接
4. 命令面板执行 `Android 调试器: 启动调试`, 选择设备与目标进程
5. 在源码中打断点, 等待命中

## 调试控制台表达式

求值支持 Java 与 Kotlin 两种语法, 适配器会自动按表达式特征选择前端:

```java
// Java: 强转与全路径引用
(java.lang.String) obj
java.util.Objects.isNull(obj)

// Kotlin: 类型判断与安全转换
obj as? String
obj is String

// Kotlin: elvis 运算符
val name = obj?.name ?: "unknown"
```

## 反馈

遇到问题请到 [Issues](https://github.com/lx0758/vscode-android-debugger/issues) 反馈。
