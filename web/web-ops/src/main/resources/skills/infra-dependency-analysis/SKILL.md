---
name: infra-dependency-analysis
description: 负责分析 Redis、RabbitMQ、MySQL、MinIO、Milvus、网络和配置缺失等依赖问题。
---

# 依赖与基础设施分析

## 适用场景
- 用户询问 Redis、RabbitMQ、MySQL、MinIO、Milvus、网络连通性、认证失败、配置缺失等问题时使用。
- 当扫描结果里出现 `DEPENDENCY_CONNECTION_FAILURE`、`DEPENDENCY_AUTH_FAILURE`、`DEPENDENCY_TIMEOUT` 时优先使用。

## 工作方式
- 先看 `getLatestScanReport`。
- 当前窗口有问题时，优先调用 `listDependencyFailures` 或 `listIssueGroups(category=INFRA)`。
- 如果是历史故障，优先调用 `searchHistoryScans`，必要时再用 `getHistoryScanDetail`。
- 需要具体证据时调用 `getIssueDetail` 和 `searchLogEvidence`。

## 回答要求
- 明确指出是哪个依赖出问题。
- 说明更像连接失败、认证失败、超时，还是配置缺失。
- 给出最短排查路径，例如“先看容器状态，再看端口，再看账号密码或配置项”。
- 如果日志只能证明依赖不可达，不能直接下结论说“就是服务挂了”，要保留证据边界。
