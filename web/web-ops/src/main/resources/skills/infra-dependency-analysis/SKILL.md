---
name: infra-dependency-analysis
description: Use when the issue looks like dependency or infrastructure trouble such as Redis, RabbitMQ, MySQL, MinIO, Milvus, network reachability, authentication failure, timeout, connection failure, or missing configuration.
---

# 依赖与基础设施分析

## 角色目标
你负责判断问题是不是由依赖或基础设施导致，重点覆盖：
- Redis
- RabbitMQ
- MySQL
- MinIO
- Milvus
- 网络不可达
- 认证失败
- 配置缺失
- 连接失败或超时

## 典型问题
- 是不是 Redis 出问题了
- 为什么连不上 MySQL
- RabbitMQ 是不是超时了
- MinIO / Milvus 是不是挂了
- 是认证失败还是网络不通
- 最近依赖异常多不多

## 首轮判断顺序
1. 先用 `getLatestScanReport` 或历史快照确认问题是否落在 `INFRA` 类别。
2. 当前窗口优先用 `listDependencyFailures`；如果需要全量分类视角，再看 `listIssueGroups(category=INFRA)`。
3. 识别问题是以下哪一种：
   - 连接失败
   - 认证失败
   - 超时
   - 配置缺失
   - 网络不可达
4. 用 `getIssueDetail` 查看问题分组里的关键报错、依赖名称、时间范围和证据片段。
5. 证据不够时，再用 `searchLogEvidence` 搜具体依赖名、主机、端口、账号、超时词和异常链。

## 问题分类规则
### 连接失败
- 关注 refused、unreachable、cannot connect、connection reset 等证据。
- 先判断是目标服务没起来、端口不通，还是客户端连接参数错了。

### 认证失败
- 关注 password、access denied、authentication failed、unauthorized、signature mismatch 等证据。
- 认证失败不等于网络不通，要单独说清。

### 超时
- 关注 read timeout、connect timeout、socket timeout、acquire timeout 等词。
- 不要仅凭一条 timeout 就断言“服务挂了”，先说明它更像链路慢、依赖忙、网络波动，还是资源耗尽。

### 配置缺失
- 关注 missing property、placeholder、bucket 不存在、topic/exchange 未创建、endpoint 配错。
- 如果是配置问题，要指出最可能缺的是哪一项。

### 网络不可达
- 关注 DNS 失败、主机不可达、端口不可达、容器之间无法通信。
- 如果日志只说明无法连接，不要越级断言是对端服务崩溃。

## 证据优先级
1. 依赖名称 + 明确异常类型。
2. 主机、端口、账号、endpoint、bucket、vhost 等关键配置线索。
3. `getIssueDetail` 的证据片段。
4. `searchLogEvidence` 搜到的上下文。
5. 用户猜测。

## 禁止误判规则
- 不要把连接失败、认证失败、超时混为一类。
- 不要仅凭一条 timeout 就断言“服务挂了”。
- 不要因为出现 Redis / MySQL 关键词，就直接说对应中间件故障；先看是客户端侧配置错、认证失败，还是网络问题。
- 不要替基础设施下超出证据边界的结论，比如“就是容器挂了”“就是网络断了”。

## 证据不足时怎么说
如果当前日志只能证明依赖不可用，但不能证明原因，要明确说：

`证据不足：当前可以确认依赖访问异常，但还不能直接确认是服务宕机、网络故障还是配置错误。`

然后给最短排查路径，例如：
- 先看服务实例和容器状态
- 再看端口和连通性
- 再看账号密码、endpoint、配置项

## 最终回答模板
- 最可能根因
- 关键证据
- 为什么更像连接失败 / 认证失败 / 超时 / 配置缺失
- 下一步排查建议

## 正确示例
### 示例 1
如果日志出现 `Access denied for user`：
- 明确说这是认证失败，不是网络不通
- 点出数据库和账号相关证据

### 示例 2
如果日志出现 Redis connect timeout：
- 先说这是 Redis 链路超时
- 再说明目前更像超时，不足以直接证明 Redis 服务已经挂了

## 错误示例
- “Redis 挂了，重启一下吧”
- “数据库有问题，具体不清楚”

错误原因：
- 超出了证据边界。
- 没有区分认证失败、超时、连接失败。
