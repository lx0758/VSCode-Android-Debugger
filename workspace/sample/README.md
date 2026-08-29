# sampleWorkspace/

调试目标工作区。在 Run Extension (F5) 时, Extension Development Host 默认打开这里。

## 怎么用

### 方式 A: 放占位 Java/Kotlin 文件测解析器
直接在这里创建 .java/.kt 文件(可以是从 AOSP 抽出来的几个代表性类), 触发:
- `Mark as Debug Source Root` 把这里标记为源根
- `Debugger with Android` attach 真实进程

### 方式 B: 软链 AOSP 源码
```bash
ln -s /path/to/aosp/frameworks/base/core/java sampleWorkspace/aosp-core
```

### 方式 C: 多文件夹 workspace
VSCode 主窗口先 `Add Folder to Workspace...`, 第一个文件夹是本仓库(扩展开发), 第二个是 AOSP 源码, 然后 F5 启动 EDH — EDH 会沿用相同的多文件夹 workspace。

## 注意

- 这里不是扩展开发目录, 改动 sampleWorkspace 内容不会触发 tsc watch (它只监视 extension/src/)
- 如果想修改扩展本身, 在主窗口里改 extension/src/*.ts, tsc watch 自动重编译, EDH 自动 reload 扩展