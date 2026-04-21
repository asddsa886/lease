# 智慧公寓项目时序图（面试版）

这份文档按项目的 6 个核心能力拆分，每个模块包含：
1. 一张精简但可讲清楚的时序图
2. 一段面试讲解口径（30~60 秒）

---

## 1. 认证鉴权（Spring Security + JWT + Redis）

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant LC as LoginController
    participant LS as LoginService
    participant R as Redis
    participant DB as MySQL
    participant JWT as JwtUtil
    participant F as JwtAuthenticationFilter
    participant BL as TokenBlacklistService
    participant API as 业务接口

    U->>LC: 登录(phone/code)
    LC->>LS: login()
    LS->>R: 校验验证码(成功后原子删除)
    LS->>DB: 查询/创建用户
    LS->>JWT: 生成JWT
    JWT-->>U: 返回token

    U->>API: 携带token访问接口
    API->>F: 进入JWT过滤器
    F->>BL: 检查黑名单
    BL->>R: 查询token黑名单Key
    R-->>BL: 是/否
    alt 黑名单或token非法
        F-->>U: 401 未认证
    else 合法
        F-->>API: 注入登录上下文
        API-->>U: 返回业务数据
    end

    U->>LC: 退出登录
    LC->>BL: blacklist(token)
    BL->>R: 写黑名单(TTL=token剩余有效期)
    LC-->>U: 退出成功
```

**讲解口径**
登录阶段用短信验证码换 JWT，访问阶段通过过滤器做 token 解析和黑名单校验。退出登录时不改 JWT 本身，而是把 token 哈希写入 Redis 黑名单并设置剩余 TTL，这样实现无状态登录下的主动失效控制。

---

## 2. 访问防护（限流 + 幂等防重复）

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant C as Controller
    participant RL as RedisRateLimiter
    participant R as Redis
    participant S as Service
    participant DB as MySQL

    U->>C: 请求登录/验证码/预约提交
    C->>RL: 按维度限流(IP、手机号、userId)
    RL->>R: Lua滑动窗口计数
    R-->>RL: allow/deny
    alt deny
        C-->>U: 请求过于频繁
    else allow
        opt 预约提交场景
            C->>R: setIfAbsent(幂等key,短TTL)
            alt 已存在
                C-->>U: 重复提交
            else 首次请求
                C->>S: 执行业务
                S->>DB: 写入数据
                DB-->>C: 成功
                C-->>U: 返回成功
            end
        end
    end
```

**讲解口径**
入口层先做 Redis 滑动窗口限流，预约提交再补一层短窗口幂等键，防止双击和重试造成重复写入。这样能同时覆盖恶意高频请求和正常用户误操作两类风险。

---

## 3. 缓存治理（Caffeine + Redis + 空值缓存 + TTL抖动 + Redisson锁）

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant S as 热点查询Service
    participant H as HotDataCacheHelper
    participant C as Caffeine
    participant R as Redis
    participant L as Redisson锁
    participant DB as MySQL

    U->>S: 查询热点详情(id)
    S->>H: getOrLoadWithLock(key)
    H->>C: 读本地缓存
    alt 本地命中
        C-->>H: value
    else 本地未命中
        H->>R: 读Redis
        alt Redis命中
            R-->>H: value/null占位
            H->>C: 回填本地缓存
        else Redis未命中
            H->>L: 获取分布式锁
            alt 获取成功
                H->>R: Double Check
                alt 仍未命中
                    H->>DB: 回源查询
                    DB-->>H: value或null
                    H->>R: 写缓存(正常TTL+抖动/空值短TTL)
                    H->>C: 写本地缓存
                end
                H->>L: 释放锁
            else 获取失败
                H->>R: 短暂等待后重读
            end
        end
    end
    H-->>S: 返回数据
    S-->>U: 返回结果
```

**讲解口径**
这块是典型“多层缓存 + 并发保护”方案：Caffeine 抗短时热点、Redis 做共享缓存、空值缓存防穿透、TTL 抖动防雪崩、Redisson 锁防击穿。核心是把高并发时的 DB 回源收敛到极少量请求。

---

## 4. 可靠消息（RabbitMQ + Outbox + TTL + 死信）

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant App as web-app下单服务
    participant DB as MySQL
    participant Pub as EventPublisher
    participant Out as OutboxService
    participant MQ as RabbitMQ
    participant Audit as 审计监听
    participant DelayQ as 延时队列
    participant Timeout as 超时监听
    participant Admin as web-admin服务
    participant Retry as Outbox重试任务

    U->>App: 提交签约订单
    App->>DB: 本地事务写订单(PENDING_PAYMENT)
    App->>Pub: publishCreated + publishTimeoutCheck
    Pub->>DB: 写outbox_message(NEW)
    Pub->>Out: 事务提交后 sendOne(id)
    Out->>MQ: 发送消息
    Out->>DB: 更新outbox状态(SENT/ACKED或FAILED)

    par 审计支线
        MQ-->>Audit: order.event
    and 超时取消支线
        MQ-->>DelayQ: timeout.delay
        DelayQ-->>Timeout: TTL到期后死信转发
        Timeout->>Admin: 条件更新 PENDING_PAYMENT -> TIMEOUT_CANCELED
        Admin->>Pub: publishStatusChanged
    end

    opt 发送失败补偿
        Retry->>DB: 扫描NEW/FAILED
        Retry->>Out: 重试sendOne
    end
```

**讲解口径**
消息不直接发 MQ，而是先落 Outbox，事务提交后异步投递，保证“业务写库成功后消息最终可达”。TTL + 死信用于超时自动取消订单，避免待支付订单长期占用资源。

---

## 5. 对象存储与知识入库（MinIO + 解析切分 + Embedding + Milvus）

```mermaid
sequenceDiagram
    autonumber
    participant A as 管理员
    participant KC as AssistantKnowledgeController
    participant KS as AssistantKnowledgeService
    participant P as 解析服务
    participant CH as 切分服务
    participant M as MinIO
    participant DB as knowledge_doc表
    participant E as EmbeddingModel
    participant V as Milvus

    A->>KC: 上传知识文档
    KC->>KS: upload()
    KS->>P: parse(file)
    P-->>KS: text
    KS->>M: 上传原文件
    KS->>DB: 保存文档元数据(status=UPLOADED)
    KS->>DB: 更新status=INDEXING
    KS->>CH: split(text)
    CH-->>KS: chunks
    loop 每个chunk
        KS->>E: 生成向量
    end
    KS->>V: 删除旧向量并写入新向量
    alt 成功
        KS->>DB: 更新status=INDEXED,chunkCount
        KS-->>KC: 返回成功
    else 失败
        KS->>DB: 更新status=FAILED,lastError
        KS-->>KC: 返回失败
    end
```

**讲解口径**
这条链路把“文档上传”和“向量入库”打通：原文件入 MinIO，结构化状态落 MySQL，向量落 Milvus。索引失败会记录 `lastError`，支持后续按文档重建索引，不需要重新上传文件。

---

## 6. 智能助手（Spring AI Skills + 工具 + RAG + SSE）

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant C as AssistantController
    participant S as OfficialSkillsAssistantService
    participant CS as 会话记忆Redis
    participant LM as 长期偏好Redis
    participant CC as ChatClient
    participant SA as SkillPromptAugmentAdvisor
    participant SR as SkillRegistry
    participant T as 业务工具
    participant K as AssistantKnowledgeTools
    participant VS as KnowledgeSearchService
    participant V as Milvus
    participant E as SseEmitter

    U->>C: /app/assistant/chat/stream
    C->>S: streamChat()
    S->>CS: 读取历史会话
    S->>LM: 提取偏好并构建 memory prompt
    S-->>E: start 事件
    S->>CC: prompt(messages + memory + toolContext)
    CC->>SA: 注入 Skills 规则
    SA->>SR: 读取对应 SKILL.md

    opt 触发业务工具
        CC->>T: 调用房源/预约/订单工具
        T-->>E: tool_call/tool_result 事件
    end

    opt 触发知识检索
        CC->>K: searchKnowledge(question)
        K->>VS: 向量检索请求
        VS->>V: TopK 查询
        V-->>VS: 知识片段
        VS-->>K: 检索结果
        K-->>E: tool_call/tool_result 事件
    end

    loop 流式生成
        CC-->>S: chunk
        S-->>E: delta 事件
    end

    S->>CS: 回写会话
    S-->>E: complete 事件(reply,nextActions)
```

**讲解口径**
当前助手不再依赖手写多 Agent 路由，而是用 `ChatClient + SkillPromptAugmentAdvisor + Skills` 选择场景规则。实时业务问题走工具，规则说明类问题走知识检索，前端通过 SSE 接收 `start / delta / tool_call / tool_result / complete` 事件。这样既保留了业务可解释性，也把代码量控制在比较轻的范围内。

---

## 面试建议（怎么用这份图）

1. 先选 2 个你最强的模块重点讲，比如“缓存治理 + 可靠消息”。  
2. 其余模块用“目标-方案-结果”三句话过。  
3. 图只讲主干路径，异常分支挑一个最关键的说（如索引失败可重试、MQ失败有Outbox补偿）。
