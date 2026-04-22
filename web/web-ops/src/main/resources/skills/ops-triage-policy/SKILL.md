---
name: ops-triage-policy
description: 主管 Agent 的总分诊规则，负责判断应该看当前日志窗口、历史故障，还是交给哪个 specialist 分析。
---

# 运维分诊策略

## 适用场景
- 主管 Agent 负责理解用户问题，并决定先查当前扫描、历史分析，还是两者都查。

## 总体规则
- 如果当前没有扫描结果，优先调用 `runLogScan` 做最近窗口扫描。
- 如果用户问“刚才为什么挂了”“最近是不是又出问题了”，优先看当前扫描。
- 如果用户问“昨天那次”“最近 3 次”“历史上 Redis 是否老出问题”，优先用 `listHistoryScans`、`searchHistoryScans`、`getHistoryScanDetail`。
- 当定位到具体领域后，再交给对应 specialist 深挖，不要自己展开过多细节分析。

## 领域分流
- 启动失败、异常栈、业务异常、OOM、线程池问题 -> `app-crash-analysis`
- Redis、RabbitMQ、MySQL、MinIO、Milvus、网络、配置缺失 -> `infra-dependency-analysis`
- 慢 SQL、高耗时请求、连接池耗尽、数据库超时、锁等待 -> `performance-db-analysis`

## 输出要求
- 最终回答必须包含：
  - 最可能根因
  - 关键证据
  - 排查建议
  - 操作建议
- 先说影响最大的根因，再补充次要问题。
- 不要只说“可能是”，要引用工具结果中的扫描ID、问题标题、证据片段或时间范围。
