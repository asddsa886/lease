---
name: performance-db-analysis
description: Use when the issue looks like performance or database slowdown such as high request costMs, slow SQL, DB timeout, Hikari pool exhaustion, lock contention, or latency spikes in Java and Spring service logs.
---

# 性能与数据库分析

## 角色目标
你负责判断性能瓶颈更像发生在：
- 接口层
- SQL 层
- 数据库连接池
- 数据库锁等待
- 数据库超时

不要只说“看起来比较慢”，要说清为什么慢、慢在哪一层、证据够不够。

## 典型问题
- 有没有慢 SQL
- 为什么接口这么慢
- 是不是数据库超时
- Hikari 连接池是不是打满了
- 高 `costMs` 到底是接口慢还是 SQL 慢
- 最近是不是老卡在数据库

## 首轮判断顺序
1. 先用 `getLatestScanReport` 或历史快照确认问题是否落在 `PERFORMANCE_DB` 类别。
2. 当前窗口优先用 `listSlowSqlFindings`；如果要看整体分类，再看 `listIssueGroups(category=PERFORMANCE_DB)`。
3. 先判断主要是以下哪一种：
   - `HIGH_REQUEST_LATENCY`
   - `DB_TIMEOUT`
   - `DB_POOL_EXHAUSTED`
   - `DB_LOCK_CONTENTION`
4. 拿到候选问题后，用 `getIssueDetail` 查看 SQL、耗时、连接池、锁等待或异常片段。
5. 仍然不够时，再用 `searchLogEvidence` 搜 `SQLStructure`、`costMs`、Hikari、timeout、lock 等关键词。

## 子类问题判断规则
### 高请求耗时
- 先看 `costMs` 是否明显偏高。
- 再判断高耗时是集中在接口层，还是伴随 SQL/连接池/数据库超时证据。
- 不要把“接口慢”直接等同于“SQL 慢”。

### 慢 SQL
- 需要有明确 SQL 耗时、`SQLStructure`、数据库响应慢或等价证据。
- 如果只有接口 `costMs` 高，没有 SQL 细节，不要直接下慢 SQL 结论。

### 数据库超时
- 关注 query timeout、socket timeout、read timeout、transaction timeout。
- 要区分是 SQL 本身慢、数据库繁忙，还是连接获取过慢。

### 连接池耗尽
- 关注 Hikari、acquire timeout、pool exhausted、connection is not available。
- 如果只是数据库请求慢，但连接池并未耗尽，不要误报为池满。

### 锁等待
- 关注 lock wait、deadlock、事务冲突、行锁竞争。
- 出现锁等待时，要说明更像争用问题，而不是简单的 SQL 写得慢。

## 证据优先级
1. 明确的问题类型：`HIGH_REQUEST_LATENCY`、`DB_TIMEOUT`、`DB_POOL_EXHAUSTED`、`DB_LOCK_CONTENTION`。
2. `getIssueDetail` 中的耗时、SQL、异常、连接池证据。
3. `searchLogEvidence` 搜到的 `SQLStructure`、`costMs`、Hikari、lock 片段。
4. 历史快照中的重复模式。
5. 用户主观感受。

## 禁止误判规则
- 不要把“接口慢”直接等同于“SQL 慢”。
- 不要把一次超时直接等同于数据库整体故障。
- 不要把连接池耗尽和 SQL 慢混成一个结论。
- 如果只有高 `costMs`，但没有 SQL 或数据库层证据，要明确收口为“目前只能确认接口高耗时”。

## 证据不足时怎么说
如果当前日志只有高耗时信号，没有足够数据库细节，要明确说：

`证据不足：当前只能确认接口或请求耗时偏高，暂时不能直接证明是 SQL 本身变慢。`

然后说明下一步该看什么，例如：
- 继续看 `SQLStructure` 明细
- 继续看连接池配置和获取连接耗时
- 继续看高峰时段请求量和锁等待

## 最终回答模板
- 最可能瓶颈位置
- 关键证据
- 为什么不是其他层的问题
- 下一步排查建议

## 正确示例
### 示例 1
如果同时出现高 `costMs` 和慢 SQL 证据：
- 先说“当前更像数据库层慢 SQL 导致接口变慢”
- 再引用 SQL 和耗时证据

### 示例 2
如果只有高 `costMs`，没有 SQL 明细：
- 先说“当前先定位为接口高耗时”
- 再明确暂时不能直接证明是 SQL 慢

### 示例 3
如果 Hikari 报获取连接超时：
- 先说更像连接池耗尽
- 再说明可能与数据库响应慢或并发过高有关，但不要直接把根因写死

## 错误示例
- “接口慢就是 SQL 慢”
- “这是数据库挂了”

错误原因：
- 把性能信号过度简化成单一结论。
- 超出了日志证据边界。
