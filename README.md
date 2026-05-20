# Structured Console Log

[![Version](https://img.shields.io/badge/version-0.1.1-blue.svg)](https://github.com/denglei1024/rd-structured-console-logs)
[![Rider](https://img.shields.io/badge/Rider-2026.1-orange.svg)](https://www.jetbrains.com/rider/)
[![JDK](https://img.shields.io/badge/JDK-21+-green.svg)](https://www.oracle.com/java/technologies/downloads/)

> 告别在滚动日志中大海捞针——将 Run/Debug 控制台输出转化为结构化、可过滤的日志视图。

---

## 概述

**Structured Console Log** 是一款面向 JetBrains Rider 的插件，它在不改动原生 Console 的前提下，通过独立的 Tool Window 镜像并增强控制台输出。插件自动解析日志级别、时间戳、字段等关键信息，让你可以像查询数据一样浏览运行日志。

---

## 功能特性

### 🔍 结构化解析
自动识别控制台输出中的：
- **时间戳** — 自动提取并格式化
- **日志级别** — `TRACE` / `DEBUG` / `INFO` / `WARN` / `ERROR` / `FATAL`
- **Logger / Category** — 快速定位来源
- **JSON 字段** — 内联 JSON 自动展开
- **`key=value` 字段** — 结构化键值对提取

### 📋 Structured Logs Tool Window
在独立面板中提供完整的日志浏览体验：
- **会话列表** — 按 Run/Debug 进程归档，历史会话随时回看
- **表格式日志视图** — 每条日志结构清晰，一目了然
- **关键字搜索** — 即时过滤，快速定位目标日志
- **输出流筛选** — 按 `stdout` / `stderr` / `system` 分类查看
- **级别筛选** — 聚焦关键错误，屏蔽无关噪音
- **详情面板** — 显示原始日志原文及所有解析字段

### 🛡️ 异常日志聚合
自动合并连续堆栈行，避免多行异常在表格中被拆散，保持异常信息完整可读。

---


