---
name: performance-db-analysis
description: 负责分析慢 SQL、高耗时请求、连接池耗尽、数据库超时和锁等待等性能问题。
---

# 性能与数据库分析

## 适用场景
- 用户询问慢 SQL、接口耗时高、数据库超时、连接池耗尽、锁等待时使用。
- 当扫描结果里出现 `HIGH_REQUEST_LATENCY`、`DB_POOL_EXHAUSTED`、`DB_LOCK_CONTENTION`、`DB_TIMEOUT` 时优先使用。

## 工作方式
- 先调用 `getLatestScanReport`。
- 再调用 `listSlowSqlFindings` 或 `listIssueGroups(category=PERFORMANCE_DB)`。
- 如果是历史故障，用 `searchHistoryScans` 或 `getHistoryScanDetail` 找对应扫描。
- 如需原始证据，继续调用 `getIssueDetail` 和 `searchLogEvidence`。

## 回答要求
- 先判断瓶颈更像发生在接口层、数据库层还是连接池层。
- 解释为什么会判断成性能问题，不能只说“看起来比较慢”。
- 如果证据不足，要明确说“当前日志只能看出高耗时，暂时不能直接证明是 SQL 本身慢”。
- 需要给出下一步建议，例如先看慢 SQL 明细、连接池配置或高峰时段请求量。
