# Structured Console Log

一个面向 JetBrains Rider 的插件原型，用结构化方式接管 Run/Debug 控制台输出，帮助你更快定位关键信息，而不是在原始文本里来回翻找。

## 功能

- 自动监听新启动的 Run/Debug 进程，并按会话归档日志
- 对控制台输出做 best-effort 结构化解析，识别：
  - 时间戳
  - 日志级别（TRACE / DEBUG / INFO / WARN / ERROR / FATAL）
  - logger/category
  - JSON 风格字段
  - `key=value` 风格字段
- 在 `Structured Logs` Tool Window 中提供：
  - 会话列表
  - 表格式日志浏览
  - 关键字搜索
  - 输出流筛选（stdout / stderr / system）
  - 级别筛选
  - 详情面板，显示原始日志和解析出的字段
- 自动合并常见堆栈续行，减少异常日志被拆碎的问题

## 插件结构

- `StructuredLogProjectService`：监听运行进程，维护会话和日志数据
- `StructuredLogParser`：将原始文本解析成结构化日志条目
- `StructConsoleLogToolWindowFactory` / `StructConsoleLogPanel`：提供可视化界面与筛选交互
- `StructConsoleLogProjectActivity`：项目启动后主动初始化日志服务

## 本地构建

需要 JDK 21。

```powershell
.\gradlew.bat buildPlugin
```

如果当前机器已经安装了 Rider，建议直接复用本地 IDE 目录，避免再次下载完整 SDK：

```powershell
.\gradlew.bat -PlocalRiderPath="C:\Program Files\JetBrains\Rider" buildPlugin
```

产物会生成在：

```text
build\distributions\
```

## 当前范围

这个版本是可运行的 MVP，重点解决：

1. 捕获控制台输出
2. 结构化展示日志
3. 快速过滤与查看详情

它暂未改写 Rider 原生 Console 的渲染逻辑，而是通过独立 Tool Window 镜像和增强日志视图，降低实现复杂度并保持 API 稳定性。

