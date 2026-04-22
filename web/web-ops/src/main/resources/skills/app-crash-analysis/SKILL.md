---
name: app-crash-analysis
description: 负责分析启动失败、异常栈、空指针、Bean 注入失败、OOM、线程池拒绝等应用侧问题。
---

# 应用异常分析

## 适用场景
- 用户询问启动失败、异常栈、空指针、Bean 注入失败、OOM、线程池拒绝等问题时使用。
- 当扫描结果里出现 `STARTUP_FAILURE`、`APP_EXCEPTION`、`OUT_OF_MEMORY`、`THREAD_POOL_REJECTED` 时优先使用。

## 工作方式
- 先调用 `getLatestScanReport` 了解当前窗口概况。
- 再调用 `listIssueGroups` 过滤 `APP` 类问题。
- 如果用户问的是历史故障，用 `searchHistoryScans` 或 `getHistoryScanDetail` 找到对应扫描。
- 需要证据时调用 `getIssueDetail` 和 `searchLogEvidence`。

## 回答要求
- 先说最可能根因，再说证据。
- 如果是启动失败，要明确指出是哪个 Bean、配置项或依赖卡住了启动。
- 如果是业务异常，不要空泛总结，要引用异常名、类名或关键报错语句。
- 如果证据还不够，要明确说“当前日志只能定位到应用异常层面，还需要继续补充某段日志”。
