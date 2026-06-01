# 快手 Java 后端日常实习面试题库

> 面试主线：不要把项目讲成普通租房 CRUD。核心讲法是：智慧公寓租赁业务系统 + 高并发缓存治理 + 可靠消息/延时关单 + Spring AI 多 Agent/RAG 助手。

## 0. 项目 60 秒介绍

**题目：你这个项目整体是做什么的？**

**答案：**

这是一个智慧公寓租赁后端系统，采用 Maven 多模块结构：

- `model`：实体类、枚举、公共模型。
- `common`：JWT 安全认证、Redis 限流、Redisson、RabbitMQ、Outbox、热点缓存、MinIO 等公共能力。
- `web-admin`：后台管理端，负责公寓、房间、租约、订单、知识库管理。
- `web-app`：用户端，负责房源浏览、预约看房、签约订单、浏览历史、AI 租房助手。
- `web-ops`：运维助手，负责日志扫描和多 Agent 运维分析。

业务主链路是：用户浏览房源 -> 查看公寓/房间详情 -> 预约看房 -> 创建签约订单 -> 支付/取消/超时关单 -> 后台确认和租约流转。

我重点做了几块工程化增强：

- 房源详情做了 Caffeine + Redis 两级缓存，并处理缓存穿透、击穿、雪崩。
- 签约订单通过 Redisson 锁和状态条件更新处理并发。
- RabbitMQ + Outbox 实现订单事件可靠投递和延时自动关单。
- Spring AI 多 Agent + Tools + RAG 实现租房助手，实时业务走工具，规则说明走知识库。

**源码点：**

- `pom.xml`
- `common/src/main/java/com/atguigu/lease/common`
- `web/web-app/src/main/java/com/atguigu/lease/web/app`
- `web/web-admin/src/main/java/com/atguigu/lease/web/admin`
- `web/web-ops/src/main/java/com/atguigu/lease/web/ops`

**追问补刀：为什么这个项目不是普通 CRUD？**

因为核心链路里有高并发读缓存治理、订单并发控制、可靠消息最终一致性、延时关单、RAG 检索、Agent 编排和 SSE 流式输出，这些都不是普通 CRUD 能覆盖的。

---

## 1. 看房到签约的完整链路

**题目：用户从看房到签约订单，系统里发生了什么？**

**答案：**

用户先通过 `web-app` 查询房源列表和房间详情。详情接口会先走热点缓存，未命中再回源 MySQL 聚合公寓、房间、图片、标签、配套、租期、支付方式等数据。

用户确定后可以预约看房。预约提交会先做 Redis 限流，再用短 TTL 幂等 key 防重复提交，然后在 Service 层校验时间是否合法、是否存在用户同时间冲突、房源同时间冲突，并通过 Redisson 锁保护并发。

如果用户决定签约，会创建签约订单。下单时系统校验用户、房间、租期、支付方式，并对房间维度加 Redisson 锁，检查该房间是否已有待支付/已支付订单或有效租约。通过后创建 `PENDING_PAYMENT` 订单，并发布 `CREATED` 和 `TIMEOUT_CHECK` 两类领域事件。

订单事件不直接发 MQ，而是先写入 Outbox，本地事务提交后再发 RabbitMQ。超时检查消息进入延时队列，TTL 到期后死信转发到超时队列，消费者只在订单仍为 `PENDING_PAYMENT` 时把它改成 `TIMEOUT_CANCELED`。

**源码点：**

- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/RoomInfoServiceImpl.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/ViewAppointmentServiceImpl.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/LeaseOrderServiceImpl.java`
- `common/src/main/java/com/atguigu/lease/common/mq/publisher/LeaseOrderEventPublisher.java`
- `web/web-admin/src/main/java/com/atguigu/lease/web/admin/mq/LeaseOrderTimeoutListener.java`

**追问补刀：为什么订单要先下单再后台确认？**

用户端只负责表达签约意向和支付状态，后台需要确认房源、用户信息和租约状态，避免用户直接绕过运营审核生成正式租约。

---

## 2. Caffeine + Redis 两级缓存

**题目：房源详情为什么做 Caffeine + Redis 两级缓存？**

**答案：**

房源详情是典型读多写少接口，而且详情页需要聚合很多表，比如房间、公寓、图片、标签、配套、租期、支付方式。高并发下如果每次都打 DB，会造成很多重复查询。

所以读链路设计成：

1. 先查 Caffeine 本地缓存，命中时不需要网络 IO。
2. 本地未命中再查 Redis，共享多实例缓存。
3. Redis 未命中才回源 MySQL。
4. 回源后同时写 Redis 和 Caffeine。

Caffeine 的作用不是替代 Redis，而是在热点 key 已经进入 Redis 后进一步减少 Redis 网络开销和序列化开销，降低热点详情接口的尾延迟。

项目中压测过，引入 Caffeine 后，在 500 线程、60s 压测中，平均响应从约 879ms 降到 295ms，P99 从约 1663ms 降到 514ms，吞吐从约 541 req/s 提升到 1613 req/s。

**源码点：**

- `common/src/main/java/com/atguigu/lease/common/cache/HotDataCacheHelper.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/ApartmentInfoServiceImpl.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/RoomInfoServiceImpl.java`
- `doc/caffeine-benchmark.md`

**追问补刀：本地缓存会不会脏？**

会有这个风险，所以本地缓存 TTL 设置得比较短，默认 30 秒。后台更新公寓/房间时，事务提交后会主动删除 Redis 和本地缓存。单实例场景已经闭环；多实例场景还可以用 Redis Pub/Sub 或 MQ 广播做跨实例本地缓存失效。

---

## 3. 缓存穿透、击穿、雪崩

**题目：缓存穿透、击穿、雪崩你项目分别怎么处理？**

**答案：**

缓存穿透是请求的数据本身不存在，导致每次都打数据库。项目里对 `null` 或空集合做空值缓存，空值 TTL 比正常值短，避免不存在的数据长期占缓存。

缓存击穿是热点 key 过期瞬间，大量请求同时回源 DB。项目里用 Redisson 分布式锁，只有抢到锁的线程负责回源并重建缓存，其他线程短暂等待后重试读缓存。抢到锁后还会 double check，避免重复回源。

缓存雪崩是大量 key 同时过期。项目里 Redis TTL 会加随机抖动，让不同 key 的过期时间分散。

读链路可以概括为：

`Caffeine -> Redis -> Redisson lock -> DB -> write Redis with jitter -> write Caffeine`

**源码点：**

- `common/src/main/java/com/atguigu/lease/common/cache/HotDataCacheHelper.java`

**追问补刀：为什么空值缓存 TTL 要短？**

因为空值可能是短暂的数据未同步或刚被创建前的状态。如果空值 TTL 太长，真实数据创建后用户还可能读到空缓存。

---

## 4. 缓存一致性

**题目：管理端更新房源后，缓存一致性怎么保证？**

**答案：**

项目采用的是更新数据库后删除缓存的策略，而且删除缓存不是在事务中立即执行，而是注册 `afterCommit` 回调，等数据库事务真正提交成功后再删除缓存。

原因是：如果事务还没提交就删缓存，其他请求可能立刻回源读到旧数据并重新写入缓存；如果事务最后回滚，也会造成无意义的缓存删除。`afterCommit` 可以保证只有数据库变更成功后才清理 APP 端详情缓存。

房间更新时会删除房间详情缓存和所属公寓详情缓存；公寓更新时会删除公寓详情缓存，并删除该公寓下所有房间详情缓存，因为房间详情里包含公寓聚合信息。

**源码点：**

- `web/web-admin/src/main/java/com/atguigu/lease/web/admin/service/impl/ApartmentInfoServiceImpl.java`
- `web/web-admin/src/main/java/com/atguigu/lease/web/admin/service/impl/RoomInfoServiceImpl.java`
- `common/src/main/java/com/atguigu/lease/common/utils/TransactionUtils.java`

**追问补刀：为什么不是先删缓存再更新数据库？**

先删缓存再更新 DB 的窗口内，其他请求可能回源读旧 DB，再把旧数据写回缓存，导致脏缓存。更新 DB 后删缓存更常见，配合短 TTL 可以接受短暂不一致。

---

## 5. Redis 挂了怎么办？

**题目：Redis 挂了，你的系统还能用吗？**

**答案：**

要分模块看。

热点详情缓存里，读写缓存失败会记录日志，理论上可以降级到 DB。不过当前 `readCache` 直接读 Redis，如果 Redis 连接异常没有完全包裹在外层降级里，这块可以继续增强，改成 Redis 异常时直接视为 miss 并回源 DB。

限流模块里 Redis 异常默认放行，保证可用性优先，但会打 warn 日志，后续靠监控兜底。

分布式锁、登录验证码、JWT 黑名单这些能力强依赖 Redis。如果 Redis 不可用，会影响验证码登录、主动退出登录和并发保护。生产环境可以通过 Redis Sentinel/Cluster 提高可用性，同时对非核心能力做降级。

**源码点：**

- `common/src/main/java/com/atguigu/lease/common/cache/HotDataCacheHelper.java`
- `common/src/main/java/com/atguigu/lease/common/ratelimit/RedisRateLimiter.java`
- `common/src/main/java/com/atguigu/lease/common/security/TokenBlacklistService.java`

**追问补刀：怎么优化？**

缓存读写统一 try-catch，Redis 异常当作 miss；限流可以本地令牌桶兜底；分布式锁相关核心写链路可以在 Redis 不可用时直接失败，避免超卖或重复下单。

---

## 6. 下单并发控制

**题目：下单时如何防止两个人同时抢同一个房间？**

**答案：**

下单时先按房间 ID 加 Redisson 分布式锁：`lock:lease:order:room:{roomId}`。抢到锁后再查这个房间是否存在 `PENDING_PAYMENT` 或 `PAID` 订单，以及是否存在有效租约状态。如果没有冲突，才创建订单。

这样可以防止多个实例下同时对同一个房间创建订单。并且锁内部仍然会查 DB 状态，这是为了保证即使锁失效或极端并发下，也有业务状态校验兜底。

**源码点：**

- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/LeaseOrderServiceImpl.java`

**追问补刀：为什么不用 synchronized？**

`synchronized` 只在单 JVM 内有效，多实例部署下不能保护跨进程并发。Redisson 锁基于 Redis，可以保护多实例。

---

## 7. 分布式锁和数据库唯一索引

**题目：不用 Redisson，只用数据库唯一索引行不行？**

**答案：**

可以作为兜底，但不完全够。

如果业务规则只是“一个房间只能有一个有效订单”，可以设计冗余字段或状态表，用唯一索引保证。但这里还要同时判断订单状态、租约状态、房间发布状态、租期和支付方式合法性，逻辑不只是一个简单唯一约束。

Redisson 锁可以把同一个房间的下单流程串行化，减少 DB 冲突和异常回滚；数据库条件和索引则作为最终兜底。更稳的生产设计是：分布式锁 + DB 唯一约束/条件更新双保险。

**源码点：**

- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/LeaseOrderServiceImpl.java`
- `sql/20260418_add_lease_order.sql`

**追问补刀：分布式锁会死锁吗？**

项目里获取多个锁时会按锁名排序，释放时反向释放，降低死锁风险。单个锁设置等待时间和租约时间，避免长期阻塞。

---

## 8. 状态条件更新

**题目：订单状态为什么还要条件更新？只加锁够不够？**

**答案：**

只加锁不够。分布式锁主要控制并发进入临界区，但在 MQ 延时消息、用户支付、用户取消、后台确认等多入口场景下，状态变化可能来自不同服务和不同时间。

所以支付、取消、超时关单都采用条件更新，比如：

```sql
update lease_order
set status = ?
where id = ?
  and status = PENDING_PAYMENT
```

这样即使超时消息晚到，只要订单已经支付，状态就不是 `PENDING_PAYMENT`，更新会失败，不会误取消。

**源码点：**

- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/LeaseOrderServiceImpl.java`
- `web/web-admin/src/main/java/com/atguigu/lease/web/admin/mq/LeaseOrderTimeoutListener.java`

**追问补刀：这是乐观锁吗？**

可以理解为基于状态字段的乐观并发控制。它不依赖 version 字段，而是用当前状态作为更新前置条件。

---

## 9. 订单为什么发两类消息？

**题目：为什么下单后要发 `CREATED` 和 `TIMEOUT_CHECK` 两类消息？**

**答案：**

两类消息语义不同。

`CREATED` 是普通领域事件，表示订单已经创建成功，可以给审计、通知、风控、运营统计等下游消费。

`TIMEOUT_CHECK` 是未来动作，表示系统需要在一段时间后检查这笔订单是否仍未支付，如果仍是 `PENDING_PAYMENT`，就自动关单。

两类消息都走 Outbox，区别只在 routing key 不同：普通事件走 `lease.order.event`，超时检查走 `lease.order.timeout.delay`。

**源码点：**

- `common/src/main/java/com/atguigu/lease/common/mq/publisher/LeaseOrderEventPublisher.java`
- `common/src/main/java/com/atguigu/lease/common/mq/LeaseMqConstants.java`

**追问补刀：为什么不定时扫订单表？**

扫表也可以，但会有扫描延迟、DB 压力和调度复杂度。TTL + 死信队列可以把每笔订单的超时检查自然转成消息驱动，业务更解耦。

---

## 10. Outbox 模式

**题目：为什么要 Outbox？如果业务数据提交成功但 MQ 发送失败怎么办？**

**答案：**

因为本地数据库事务和 MQ 发送不是一个事务。如果直接在业务代码里发 MQ，会出现几种不一致：

- DB 提交失败，但 MQ 已经发出，产生脏消息。
- DB 提交成功，但 MQ 发送失败，下游永远不知道。

Outbox 的做法是：在业务事务里先把待发送消息写入 `outbox_message`，和业务数据一起提交。事务提交后再发送 MQ。如果发送失败，Outbox 记录会标记失败并设置下次重试时间，定时任务继续补偿。

这保证的是“业务数据提交成功后，消息最终可投递”，也就是最终一致性。

**源码点：**

- `common/src/main/java/com/atguigu/lease/common/mq/publisher/LeaseOrderEventPublisher.java`
- `common/src/main/java/com/atguigu/lease/common/mq/outbox/service/impl/OutboxServiceImpl.java`
- `web/web-admin/src/main/java/com/atguigu/lease/web/admin/schedule/OutboxRetryTasks.java`

**追问补刀：Outbox 会重复投递吗？**

可能会，所以消费者要按业务 id 做幂等。项目里超时关单消费者通过状态条件更新天然幂等，重复消费时状态已经不是期望状态，会跳过。

---

## 11. RabbitMQ 延时关单

**题目：RabbitMQ TTL + 死信队列怎么实现延时关单？**

**答案：**

项目里定义了一个 topic exchange：`lease.topic`。

下单后发送 `TIMEOUT_CHECK` 消息到 routing key：`lease.order.timeout.delay`，进入延时队列 `lease.order.timeout.delay.queue`。

这个延时队列配置了：

- `x-message-ttl`：消息存活时间，比如 15 分钟。
- `x-dead-letter-exchange`：过期后转发到 `lease.topic`。
- `x-dead-letter-routing-key`：过期后使用 `lease.order.timeout`。

TTL 到期后，消息变成死信，被转发到 `lease.order.timeout.queue`，最后由 `LeaseOrderTimeoutListener` 消费并执行条件更新。

**源码点：**

- `common/src/main/java/com/atguigu/lease/common/mq/RabbitMqConfiguration.java`
- `web/web-admin/src/main/java/com/atguigu/lease/web/admin/mq/LeaseOrderTimeoutListener.java`

**追问补刀：RabbitMQ 延时队列有什么缺点？**

队列级 TTL 在一些场景下可能受队头阻塞影响。如果要更精确的延时，可以用 RabbitMQ 延时插件、Redis ZSET 时间轮、DelayQueue 或调度中心。

---

## 12. 支付和超时并发

**题目：如果用户支付和超时取消同时发生，怎么避免误取消？**

**答案：**

靠状态条件更新。

支付时只允许把 `PENDING_PAYMENT` 改成 `PAID`；超时消费者只允许把 `PENDING_PAYMENT` 改成 `TIMEOUT_CANCELED`。谁先更新成功，另一个再执行时条件就不满足，会更新失败。

所以即使超时消息晚到或重复到，也不会把已经支付的订单取消。

**源码点：**

- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/LeaseOrderServiceImpl.java`
- `web/web-admin/src/main/java/com/atguigu/lease/web/admin/mq/LeaseOrderTimeoutListener.java`

**追问补刀：如果支付成功但发布状态变更消息失败呢？**

状态已经落库成功，消息走 Outbox。如果 MQ 临时失败，Outbox 重试任务会继续补发。

---

## 13. Redis 限流

**题目：项目里的限流怎么做？**

**答案：**

项目里封装了 `RedisRateLimiter`，支持固定窗口和滑动窗口。实际登录、短信、预约接口主要使用滑动窗口。

滑动窗口用 Redis ZSET 实现：

1. `ZREMRANGEBYSCORE` 删除窗口外的请求记录。
2. `ZCARD` 统计当前窗口内请求数。
3. 如果数量超过阈值，拒绝请求。
4. 没超过就 `ZADD` 当前请求，并设置 key 过期时间。

Lua 脚本保证这些操作原子执行，适合多实例部署。

**源码点：**

- `common/src/main/java/com/atguigu/lease/common/ratelimit/RedisRateLimiter.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/controller/login/LoginController.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/controller/appointment/ViewAppointmentController.java`

**追问补刀：滑动窗口和固定窗口区别？**

固定窗口实现简单，但窗口边界可能瞬时放过两倍流量；滑动窗口按最近一段时间统计，更平滑，但 Redis 操作和存储成本更高。

---

## 14. JWT 退出登录

**题目：JWT 退出登录怎么实现？为什么 Redis 黑名单 key 存 token 哈希？**

**答案：**

JWT 本身是无状态的，签发后服务端不保存 session，所以不能像传统 session 一样直接删除登录态。

项目里退出登录时会解析 token，拿到过期时间，把 token 的 SHA-256 哈希写入 Redis 黑名单，TTL 设置为 token 剩余有效期。之后认证过滤器每次解析 token 前，会先检查这个 token 是否在黑名单里。

Redis key 存 token 哈希而不是原始 token，是为了避免 Redis 中暴露完整敏感凭证。

**源码点：**

- `common/src/main/java/com/atguigu/lease/common/security/JwtAuthenticationFilter.java`
- `common/src/main/java/com/atguigu/lease/common/security/TokenBlacklistService.java`
- `common/src/main/java/com/atguigu/lease/common/security/LogoutService.java`

**追问补刀：这样是不是变成有状态了？**

是的，主动退出场景需要引入少量服务端状态。它只保存已失效 token 的哈希，并且 TTL 到期自动清理，成本可控。

---

## 15. 短信验证码原子校验

**题目：验证码登录怎么防止验证码被并发重复使用？**

**答案：**

验证码存 Redis，登录时不是先 `GET` 再 `DEL`，而是用 Lua 脚本完成“校验 + 删除”的原子操作。

脚本逻辑是：

- key 不存在，返回过期。
- key 存在但值不匹配，返回错误，不删除。
- 值匹配，删除 key 并返回成功。

这样可以避免两个并发登录请求同时读到正确验证码并都登录成功。

**源码点：**

- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/LoginServiceImpl.java`

**追问补刀：为什么错误验证码不删除？**

避免用户输错一次就必须重新获取验证码。真正的暴力尝试由登录接口的 IP 和手机号双维度限流控制。

---

## 16. AI 助手为什么拆 Supervisor 和 Specialist？

**题目：AI 助手为什么要拆 Supervisor 和 Specialist？不拆可以吗？**

**答案：**

不拆也可以，早期可以用一个大 ChatClient 挂所有工具。但工具和场景变多后，一个大 Agent 容易出现职责混乱、工具误调用、提示词过长和不好维护。

现在项目里拆成：

- `SupervisorAgent`：只负责理解用户问题，生成结构化执行计划。
- `HousingAdvisorSpecialistAgent`：负责找房、房源推荐、预约。
- `OrderServiceSpecialistAgent`：负责订单、支付、签约状态。
- `CustomerSupportSpecialistAgent`：负责规则说明、FAQ、知识库。

每个 Specialist 只挂自己需要的工具，降低误调用风险。Supervisor 最多调度 3 个 agent，并且有 `SupervisorPlanValidator` 做计划校验。

**源码点：**

- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/config/AssistantConfiguration.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/service/chat/SupervisorAgentAssistantService.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/service/agent/LlmSupervisorAgent.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/service/agent/SupervisorPlanValidator.java`

**追问补刀：多 Agent 有什么代价？**

会增加调用次数、延迟和 token 成本。所以项目限制最多 3 个 agent，并保留 fallback 到 legacy skills assistant。

---

## 17. Tools 层为什么复用业务 Service？

**题目：为什么不让 Agent 直接查数据库，而是走 Tools 调用业务 Service？**

**答案：**

因为 Agent 不应该绕过业务边界。

Tools 层只负责把模型参数转成业务调用，真实查询和写入仍然走现有 Service。这样可以复用原来的权限校验、参数校验、分布式锁、限流、事务和异常处理。

比如创建预约的 Tool 最终调用 `ViewAppointmentService.saveOrUpdateForCurrentUser`，创建签约订单的 Tool 最终调用 `LeaseOrderService.submit`。模型不会直接操作数据库，也不会绕过当前用户身份。

**源码点：**

- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/service/tool/AssistantRoomTools.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/service/tool/AssistantAppointmentTools.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/service/tool/AssistantLeaseOrderTools.java`

**追问补刀：Tool 怎么知道当前用户是谁？**

通过 `ToolContext` 传递 `CURRENT_USER_ID`、`CONVERSATION_ID` 和工具事件 emitter，避免模型自己伪造用户身份。

---

## 18. RAG 入库链路

**题目：RAG 入库链路是什么？MinIO、MySQL、Milvus 分别存什么？**

**答案：**

管理端上传知识文档后，系统先解析文件，目前支持 `md/txt/pdf`。原始文件上传到 MinIO；文档元数据、索引状态、版本、错误信息写 MySQL；解析出的文本会按长度和标点切 chunk，然后生成 embedding，最后写入 Milvus。

三类存储职责是：

- MinIO：保存原始文件，方便下载和重建索引。
- MySQL：保存知识文档元数据、状态、版本和 lastError。
- Milvus：保存 chunk 文本、metadata 和向量索引，用于相似度检索。

索引失败时会把状态改成 `FAILED` 并记录错误，后续可以 `reindexById` 从 MinIO 重新读取原文件并重建索引。

**源码点：**

- `web/web-admin/src/main/java/com/atguigu/lease/web/admin/service/impl/AssistantKnowledgeServiceImpl.java`
- `web/web-admin/src/main/java/com/atguigu/lease/web/admin/service/assistant/AssistantDocumentParseService.java`
- `web/web-admin/src/main/java/com/atguigu/lease/web/admin/service/assistant/AssistantDocumentChunkService.java`
- `web/web-admin/src/main/java/com/atguigu/lease/web/admin/service/assistant/AssistantKnowledgeIndexService.java`

**追问补刀：为什么 MySQL 不直接存全文？**

MySQL 更适合管理元数据和状态；原始文件放对象存储更自然；向量检索需要 Milvus 这种专门的向量数据库。

---

## 19. Milvus 检索链路

**题目：Milvus 检索链路是什么？TopK、embedding、snippet 怎么处理？**

**答案：**

用户问规则类问题时，Assistant 会调用 `searchKnowledge` Tool。这个 Tool 调用 `AssistantKnowledgeSearchService`。

检索流程是：

1. 对用户问题生成 embedding。
2. 构造 Milvus `SearchParam`，指定 collection、vector field、TopK、metric type、nprobe 和返回字段。
3. Milvus 返回相似 chunk。
4. 系统解析 title、content、metadata、score。
5. 对 content 做 snippet 截断，避免一次返回过长文本。

TopK 默认由配置控制，用户传入也最多限制到 10，避免返回内容过多导致 token 膨胀。

**源码点：**

- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/service/tool/AssistantKnowledgeTools.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/service/rag/MilvusAssistantKnowledgeSearchService.java`

**追问补刀：L2 和 cosine 有什么区别？**

L2 是欧氏距离，越小越近；cosine 看向量夹角，更关注方向。选择哪种要和 embedding 模型训练/推荐方式一致。

---

## 20. RAG 如何降低幻觉？

**题目：RAG 如何降低幻觉？如果检索不到内容怎么办？**

**答案：**

项目把问题分成两类：实时业务问题走 Tools，比如查订单、预约、房源；规则说明类问题走 RAG，比如预约规则、签约流程、押金说明。

RAG 降低幻觉的关键是让模型基于检索到的知识片段回答，而不是凭空编造。检索结果里包含 title、snippet、metadata、score，模型可以根据片段组织答案。

如果检索不到内容，`searchKnowledge` 会返回“未找到相关知识片段”，这时应该让模型明确说明没有查到平台规则，不要编造。后续可以加相似度阈值、引用来源和人工兜底。

**源码点：**

- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/service/rag/MilvusAssistantKnowledgeSearchService.java`
- `web/web-app/src/main/resources/skills/knowledge-qa/SKILL.md`

**追问补刀：RAG 还有哪些问题？**

有召回不足、chunk 切分不合理、embedding 模型不匹配、TopK 太小、噪声片段干扰、上下文过长等问题。可以通过 hybrid search、rerank、相似度阈值、引用约束来优化。

---

## 21. 多 Agent token 爆炸

**题目：多 Agent 会不会互相循环调用导致 token 爆炸？你怎么限制？**

**答案：**

会有这个风险。项目里通过几层约束控制：

- Supervisor 只输出结构化 JSON 计划，不直接回答业务细节。
- `SupervisorPlanValidator` 限制最多 3 个 agent。
- additionalAgents 不能重复 primaryAgent。
- 对订单敏感问题强制包含 `order-service`，避免路由错误。
- Specialist 之间不是自由互调，而是由 Supervisor 顺序调度，并通过 sharedContext 传递简短结果。
- 如果 Supervisor 多 Agent 执行失败，会 fallback 到 legacy skills assistant。

**源码点：**

- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/service/agent/LlmSupervisorAgent.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/service/agent/SupervisorPlanValidator.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/assistant/service/chat/SupervisorAgentAssistantService.java`

**追问补刀：还能怎么优化 token？**

可以压缩 sharedContext，只保留结构化字段；对历史消息做摘要；RAG snippet 设置上限；对工具结果分页；对多 Agent 增加最大调用次数。

---

## 22. MySQL 联合索引

**题目：结合订单表讲一下联合索引和最左匹配原则。**

**答案：**

订单表里有两个典型联合索引：

- `idx_lease_order_user_status(user_id, status)`
- `idx_lease_order_room_status(room_id, status)`

第一个适合查询某个用户的某类订单，比如“我的待支付订单”。第二个适合判断某个房间是否已经有待支付或已支付订单。

联合索引遵循最左匹配原则。比如 `(user_id, status)` 可以支持：

- `where user_id = ?`
- `where user_id = ? and status = ?`

但不能很好支持只按 `status` 查询，因为跳过了最左列 `user_id`。

**源码点：**

- `sql/20260418_add_lease_order.sql`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/LeaseOrderServiceImpl.java`

**追问补刀：为什么不用给所有字段都建索引？**

索引会占空间，降低写入和更新性能。要根据查询模式建索引，尤其是高频 where、join、order by 字段。

---

## 23. B+ 树为什么适合数据库索引

**题目：B+ 树为什么适合数据库索引？为什么不是 HashMap？**

**答案：**

B+ 树适合磁盘存储和范围查询。

它的特点是：

- 多路平衡树，树高低，磁盘 IO 次数少。
- 非叶子节点只存 key 和指针，一个页能放更多 key。
- 数据都在叶子节点，叶子节点之间有链表，适合范围扫描。
- 有序结构支持排序、范围查询、最左匹配。

HashMap 等哈希结构适合等值查询，但不支持有序范围查询，比如租金区间、时间范围、按创建时间排序等，所以不适合作为通用数据库索引。

**追问补刀：为什么不是红黑树？**

红黑树是二叉树，树高更高，磁盘 IO 次数更多；B+ 树一个节点对应一个页，更适合磁盘和数据库页缓存。

---

## 24. Java 对象创建和 TLAB

**题目：Java `new` 一个对象的过程？TLAB 是什么？**

**答案：**

对象创建大致流程：

1. 类加载检查，确认类元信息已加载。
2. 分配内存，通常在堆上。
3. 初始化零值。
4. 设置对象头，包括 Mark Word、Class Pointer 等。
5. 执行构造方法，把字段设置成业务值。

TLAB 是 Thread Local Allocation Buffer，线程本地分配缓冲区。为了减少多线程在堆上分配对象时竞争锁，每个线程会在 Eden 区先拿一小块私有空间，小对象优先在 TLAB 里分配。TLAB 不够时再走慢路径分配。

**追问补刀：对象一定在堆上吗？**

语义上对象在堆上，但 JIT 逃逸分析后，如果对象没有逃出方法，可能标量替换或栈上分配，减少 GC 压力。

---

## 25. ThreadLocal 内存泄漏

**题目：ThreadLocal 为什么可能内存泄漏？怎么避免？**

**答案：**

`ThreadLocalMap` 的 key 是弱引用，value 是强引用。如果 ThreadLocal 对象被回收，key 会变成 null，但 value 还挂在线程的 ThreadLocalMap 里。

在线程池场景下，线程长期存活，如果没有手动 remove，这些 value 可能一直无法释放，造成内存泄漏。

避免方式：

- 使用完 ThreadLocal 后在 finally 中 `remove()`。
- 不要在线程池任务里长期保存大对象。
- 框架级上下文要确保请求结束时清理。

**项目联系：**

项目里登录用户上下文类似这种场景，请求结束后要由过滤器清理，避免线程复用时用户信息串号。

**追问补刀：弱引用 key 为什么还会泄漏？**

弱引用只解决 key 的回收，value 仍然被 ThreadLocalMap Entry 强引用着，线程不结束就可能一直存在。

---

## 26. HashMap 和 ConcurrentHashMap

**题目：HashMap 和 ConcurrentHashMap 区别？**

**答案：**

HashMap 线程不安全，多线程并发 put 可能出现数据覆盖、链表/红黑树结构异常等问题。

ConcurrentHashMap 是线程安全的。JDK 8 中主要通过 CAS + synchronized 锁桶头节点实现并发控制，读操作大多无锁，写操作只锁局部桶，降低锁粒度。

HashMap 扩容时容量翻倍，链表长度超过阈值并且数组足够大时会树化为红黑树；ConcurrentHashMap 也有类似链表转树逻辑，但并发扩容会有多线程协助迁移。

**追问补刀：为什么 ConcurrentHashMap 不允许 null？**

因为并发场景下 `get(key) == null` 无法区分是 key 不存在，还是 value 本身就是 null，会造成语义歧义。

---

## 27. 线程池参数

**题目：线程池核心参数怎么理解？你项目里哪里用了线程池？**

**答案：**

线程池核心参数包括：

- corePoolSize：核心线程数。
- maximumPoolSize：最大线程数。
- queueCapacity：任务队列容量。
- keepAliveTime：非核心线程空闲存活时间。
- RejectedExecutionHandler：拒绝策略。

项目里浏览历史保存使用异步线程池。用户看房间详情时，浏览历史不是主流程强依赖，所以用 `@Async("browsingHistoryTaskExecutor")` 异步保存，降低详情接口延迟。

拒绝策略用 `CallerRunsPolicy`，当队列满时由调用线程执行，保证任务不丢，但会反向给入口施加压力。

**源码点：**

- `web/web-app/src/main/java/com/atguigu/lease/web/app/custom/config/AsyncConfiguration.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/BrowsingHistoryServiceImpl.java`

**追问补刀：为什么不无限队列？**

无限队列可能在流量突增时堆积大量任务，导致内存上涨甚至 OOM。有限队列 + 拒绝策略更可控。

---

## 28. 手撕：K 个一组翻转链表

**题目：K 个一组翻转链表。**

**答案思路：**

使用 dummy 节点，每次从 groupPrev 往后找第 k 个节点。如果不足 k 个，结束。找到后记录 groupNext，然后在 `[groupPrev.next, groupNext)` 区间内原地翻转。翻转后接回前后链表。

**Java 代码：**

```java
class Solution {
    public ListNode reverseKGroup(ListNode head, int k) {
        ListNode dummy = new ListNode(0);
        dummy.next = head;
        ListNode groupPrev = dummy;

        while (true) {
            ListNode kth = getKth(groupPrev, k);
            if (kth == null) {
                break;
            }
            ListNode groupNext = kth.next;

            ListNode prev = groupNext;
            ListNode cur = groupPrev.next;
            while (cur != groupNext) {
                ListNode next = cur.next;
                cur.next = prev;
                prev = cur;
                cur = next;
            }

            ListNode oldHead = groupPrev.next;
            groupPrev.next = kth;
            groupPrev = oldHead;
        }
        return dummy.next;
    }

    private ListNode getKth(ListNode cur, int k) {
        while (cur != null && k > 0) {
            cur = cur.next;
            k--;
        }
        return cur;
    }
}
```

**复杂度：**

时间 `O(n)`，空间 `O(1)`。

---

## 29. 手撕：合并两个有序链表

**题目：合并两个有序链表。**

**答案思路：**

用 dummy 节点和 tail 指针，谁小接谁，最后把剩余链表接到 tail 后面。

**Java 代码：**

```java
class Solution {
    public ListNode mergeTwoLists(ListNode list1, ListNode list2) {
        ListNode dummy = new ListNode(0);
        ListNode tail = dummy;
        while (list1 != null && list2 != null) {
            if (list1.val <= list2.val) {
                tail.next = list1;
                list1 = list1.next;
            } else {
                tail.next = list2;
                list2 = list2.next;
            }
            tail = tail.next;
        }
        tail.next = list1 != null ? list1 : list2;
        return dummy.next;
    }
}
```

**复杂度：**

时间 `O(m+n)`，空间 `O(1)`。

---

## 30. 手撕：最长连续序列

**题目：最长连续序列。**

**答案思路：**

把所有数字放入 HashSet。只从“连续序列起点”开始枚举，也就是 `num - 1` 不存在时才往后数。这样每个元素最多被访问一次。

**Java 代码：**

```java
class Solution {
    public int longestConsecutive(int[] nums) {
        Set<Integer> set = new HashSet<>();
        for (int num : nums) {
            set.add(num);
        }

        int ans = 0;
        for (int num : set) {
            if (set.contains(num - 1)) {
                continue;
            }
            int cur = num;
            int len = 1;
            while (set.contains(cur + 1)) {
                cur++;
                len++;
            }
            ans = Math.max(ans, len);
        }
        return ans;
    }
}
```

**复杂度：**

平均时间 `O(n)`，空间 `O(n)`。

---

## 31. 手撕：合并区间

**题目：合并区间。**

**答案思路：**

先按左端点排序，再维护当前合并区间。如果新区间左端点大于当前右端点，说明不重叠，加入答案并开启新区间；否则更新右端点。

**Java 代码：**

```java
class Solution {
    public int[][] merge(int[][] intervals) {
        Arrays.sort(intervals, (a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> ans = new ArrayList<>();

        int[] cur = intervals[0];
        for (int i = 1; i < intervals.length; i++) {
            if (intervals[i][0] > cur[1]) {
                ans.add(cur);
                cur = intervals[i];
            } else {
                cur[1] = Math.max(cur[1], intervals[i][1]);
            }
        }
        ans.add(cur);
        return ans.toArray(new int[ans.size()][]);
    }
}
```

**复杂度：**

时间 `O(n log n)`，空间 `O(n)`。

---

## 32. 手撕：最长回文子串

**题目：最长回文子串。**

**答案思路：**

中心扩展。每个字符可以作为奇数长度中心，每两个字符中间可以作为偶数长度中心。向两边扩展，记录最长范围。

**Java 代码：**

```java
class Solution {
    public String longestPalindrome(String s) {
        int start = 0;
        int end = 0;
        for (int i = 0; i < s.length(); i++) {
            int len1 = expand(s, i, i);
            int len2 = expand(s, i, i + 1);
            int len = Math.max(len1, len2);
            if (len > end - start + 1) {
                start = i - (len - 1) / 2;
                end = i + len / 2;
            }
        }
        return s.substring(start, end + 1);
    }

    private int expand(String s, int left, int right) {
        while (left >= 0 && right < s.length()
                && s.charAt(left) == s.charAt(right)) {
            left--;
            right++;
        }
        return right - left - 1;
    }
}
```

**复杂度：**

时间 `O(n^2)`，空间 `O(1)`。

---

## 33. 临场反问准备

**题目：你有什么想问我的吗？**

**答案：**

可以问：

- 团队里 Java 后端实习生主要参与业务迭代，还是基础架构/工程效率也会参与？
- 快手这边对实习生的代码质量和上线流程要求是什么？
- 如果业务里有 AI Agent 或 RAG 方向，后端同学一般会参与到哪些层：工具编排、检索链路、模型调用，还是平台化能力？

不要问薪资、转正难度、加班强度这种一上来容易减分的问题。

---

## 34. 明天复习优先级

1. 先背项目 60 秒介绍。
2. 重点背缓存、订单 MQ、Redisson、Outbox、RAG/Agent。
3. Java 基础重点看：HashMap、ConcurrentHashMap、线程池、ThreadLocal、JVM 对象创建、GC。
4. MySQL 重点看：B+ 树、联合索引、索引失效、事务隔离级别、MVCC。
5. Redis 重点看：缓存三大问题、分布式锁、ZSET 滑动窗口、持久化、主从/哨兵。
6. 算法至少练熟链表和区间题。

---

## 35. 快手面经检索到的高频方向

整理自公开面经和岗位信息，主要高频点：

- 项目深挖：项目流程、技术选型、难点、性能优化。
- Java 基础：集合、并发、线程池、JVM、GC、ThreadLocal。
- MySQL：索引、B+ 树、事务、锁、慢 SQL。
- Redis：缓存三大问题、分布式锁、限流、持久化、集群。
- MQ：为什么用 MQ、可靠投递、重复消费、延时消息。
- AI/RAG/Agent：Agent 执行链路、Tools、Milvus、token 控制、RAG 幻觉。
- 算法：链表、数组、哈希、区间、字符串、动态规划入门。

参考来源：

- https://www.nowcoder.com/feed/main/detail/97334752983c47c3b965f317ed9400cb
- https://www.nowcoder.com/feed/main/detail/5c6465cbcca54107a18841302a8292a0
- https://www.nowcoder.com/feed/main/detail/55457f004542483d9e1df4825222bb0e
- https://mj.mianlingai.com/interview/kuaishou-backend-first-round-2818381/
- https://www.nowcoder.com/feed/main/detail/cae9e555b8ab452da0f8df48395d5f56
