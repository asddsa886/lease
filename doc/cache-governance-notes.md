# 缓存治理记录

## 1. 目标

当前项目的缓存治理，主要解决三类典型问题：

- 缓存穿透：请求的数据本身不存在，导致每次都打到数据库
- 缓存击穿：热点 key 过期瞬间，大量并发同时回源数据库
- 缓存雪崩：多个 key 在相近时间集中失效，导致后端压力骤增

本项目对应的落地手段如下：

- 空值缓存：防缓存穿透
- TTL 抖动：防缓存雪崩
- Redisson 分布式锁：防热点 key 击穿

通用能力封装在：

- `common/src/main/java/com/atguigu/lease/common/cache/HotDataCacheHelper.java`

## 2. 实现说明

### 2.1 空值缓存

`HotDataCacheHelper` 已支持对空结果进行缓存：

- 当加载结果为 `null` 或空集合时，判定为“空值”
- 空值会写入 Redis
- 空值使用更短的 TTL，避免脏空值长期驻留

关键位置：

- `getOrLoad()`：统一读取与回源
- `getOrLoadWithLock()`：热点 key 加锁回源
- `writeCacheSafely()`：统一写缓存与 TTL 处理
- `isNullLike()`：判定 `null`/空集合

### 2.2 业务层改造

之前详情接口虽然使用了缓存 helper，但在查不到数据时直接抛异常，导致空值根本没有机会写入 Redis。

现在已调整为：

1. 内部加载方法在查不到数据时返回 `null`
2. `HotDataCacheHelper` 负责缓存这次空结果
3. 外层 service 在拿到 `null` 后，再继续抛 `LeaseException(DATA_ERROR)`

这样做的好处是：

- 对前端接口语义不变，仍然返回“数据异常”
- 对数据库更友好，不会因为不存在的数据被反复访问而持续穿透

当前已接入的详情接口：

- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/ApartmentInfoServiceImpl.java`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/service/impl/RoomInfoServiceImpl.java`

### 2.3 Redisson 分布式锁

`HotDataCacheHelper#getOrLoadWithLock()` 用于处理热点 key 场景。

执行逻辑：

1. 先查 Redis
2. 未命中时，对 `lock:<cacheKey>` 尝试加锁
3. 抢到锁的线程负责回源数据库并重建缓存
4. 没抢到锁的线程短暂等待后再次读取 Redis
5. 抢到锁后做一次 double check，防止重复回源

这样可以避免在热点 key 失效时，多线程或多实例同时打数据库。

### 2.4 看门狗机制

这里的 Redisson 锁采用的是无固定 `leaseTime` 的 `tryLock()` 模式。

这意味着：

- 成功持锁后，Redisson 会自动续期
- 只要持锁线程还存活，锁不会因为业务执行稍慢而提前释放
- 业务完成后再主动释放锁

因此，这里的热点缓存重建锁属于“带看门狗机制”的分布式锁用法。

## 3. 已完成验证

### 3.1 空值缓存已验证

验证接口：

- `GET /app/apartment/getDetailById?id=9999`

验证现象：

1. 第一次请求时，日志中出现数据库查询：
   - `select ... from apartment_info where id = 9999`
2. 第一次请求后，Redis 中出现 key：
   - `app:apartment:detail:9999`
3. 第二次请求仍然返回“数据异常”
4. 第二次请求时，没有再次看到相同的 `selectById id=9999`

结论：

- 空值缓存已生效
- 项目对外仍返回统一错误响应
- 对同一个不存在的数据，不会每次都穿透数据库

### 3.2 缓存性能收益已验证

已有一版缓存压测记录，见：

- `doc/cache-benchmark.md`

该记录主要说明：

- 打开热点数据缓存后，详情接口平均响应时间明显下降
- 吞吐量提升明显
- 在本地环境下已能观察到缓存带来的直接收益

## 4. 建议优先测试项

下面这些测试最值得补齐，因为它们能直接支撑项目亮点描述。

### 4.1 优先级 P1：空值缓存防穿透

测试目标：

- 证明不存在的数据不会反复打数据库

建议接口：

- `GET /app/apartment/getDetailById?id=9999`
- `GET /app/room/getDetailById?id=9999`

建议步骤：

1. 先删除对应 Redis key
2. 连续请求两次相同不存在 id
3. 观察应用日志中的 SQL
4. 检查 Redis 中是否生成对应 key
5. 检查 TTL 是否为较短时间

重点证据：

- 第一次请求有 SQL
- 第二次请求无 SQL
- Redis 中存在对应空值缓存 key

### 4.2 优先级 P1：热点缓存收益对比

测试目标：

- 证明热点缓存能降低响应时间、提高吞吐量

建议接口：

- `GET /app/apartment/getDetailById?id=9`
- `GET /app/room/getDetailById?id=9`

建议做 A/B 对比：

- 关闭缓存：`lease.cache.hot-data-enabled=false`
- 开启缓存：`lease.cache.hot-data-enabled=true`

建议观察指标：

- Avg
- P95
- P99
- Throughput
- Error %

重点证据：

- 开启缓存后，平均响应时间与尾延迟明显下降
- 吞吐量明显提升

### 4.3 优先级 P1：分布式锁防击穿

测试目标：

- 证明多实例下，热点 key 失效瞬间不会一起打爆数据库

建议环境：

- 启动两个应用实例，例如 `8081`、`8082`
- 使用 Nginx 统一转发，例如 `8090`
- 压测入口改为 Nginx 地址

建议步骤：

1. 预热某个热点详情 key，例如 `id=9`
2. 手动删除 Redis 中对应缓存 key
3. 立刻对同一个 id 发起高并发请求
4. 同时观察两个实例的访问日志和 SQL 日志

重点证据：

- 请求被 Nginx 分发到两个实例
- 缓存失效瞬间，并不是所有请求都回源数据库
- 只有少量线程触发真实 SQL
- 其余请求在等待后命中已重建的缓存

如果想把结果写得更扎实，建议记录：

- 删除 key 的时间点
- 两个实例各自的命中情况
- 同一时间窗内真正触发的 SQL 次数

### 4.4 优先级 P2：TTL 抖动验证

测试目标：

- 证明缓存 key 不会在同一时刻集中失效

建议方法：

1. 连续请求多个详情 key，让它们写入缓存
2. 分别查看这些 key 的 TTL
3. 对比是否存在离散分布，而不是完全相同

重点证据：

- 不同 key 的 TTL 存在随机偏移
- 说明过期时间被打散

### 4.5 优先级 P2：Redis 异常降级验证

测试目标：

- 观察 Redis 不可用时，接口是否仍可正常回源数据库

建议说明：

- 这个测试更偏稳定性验证
- 如果当前阶段主要是准备项目亮点，可放在后面做

## 5. 当前最推荐补的测试

如果只选三项，建议按下面顺序补：

1. 空值缓存防穿透
2. 多实例下的分布式锁防击穿
3. 热点缓存开关 A/B 对比

原因：

- 这三项最容易和你的项目亮点一一对应
- 这三项最容易在面试里讲清楚“做了什么、为什么做、怎么证明有效”
- 这三项既有技术深度，也有可展示的结果

## 6. 对外表述建议

这一块后续可以这样概括：

“封装通用缓存组件，结合空值缓存、TTL 抖动和 Redisson 分布式锁，分别应对缓存穿透、缓存雪崩和热点 key 击穿问题；并通过空值缓存验证、缓存 A/B 压测及双实例压测验证了优化效果。”

