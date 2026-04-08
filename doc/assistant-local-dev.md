# 租房智能助手本地开发说明

## 1. 功能范围

当前这版助手是最小可用版本，放在 `web-app` 模块中，主要能力包括：

- 查询房源列表
- 查看房间详情
- 查看我的预约
- 查看我的租约
- 简单闲聊和常识问答
- 通过“小区名 + 房号”继续追问房间详情

当前不包含：

- RAG
- 多轮记忆
- 流式输出
- 外部天气/搜索工具

## 2. 关键入口

- 接口文档：`http://localhost:8081/doc.html`
- 本地聊天页：`http://localhost:8081/assistant.html`
- 助手接口：`POST /app/assistant/chat`

## 3. 本地配置

`web/web-app/src/main/resources/application.yml` 中已经预留了配置项：

```yaml
app:
  ai:
    assistant:
      enabled: ${APP_AI_ASSISTANT_ENABLED:true}
      provider: ${APP_AI_ASSISTANT_PROVIDER:openai-compatible}
      base-url: ${APP_AI_ASSISTANT_BASE_URL:...}
      model-name: ${APP_AI_ASSISTANT_MODEL:...}
      api-key: ${APP_AI_ASSISTANT_API_KEY:...}
      temperature: ${APP_AI_ASSISTANT_TEMPERATURE:0.2}
      timeout: ${APP_AI_ASSISTANT_TIMEOUT:60s}
      max-search-results: ${APP_AI_ASSISTANT_MAX_SEARCH_RESULTS:5}
```

建议本地通过环境变量覆盖：

- `APP_AI_ASSISTANT_ENABLED`
- `APP_AI_ASSISTANT_BASE_URL`
- `APP_AI_ASSISTANT_MODEL`
- `APP_AI_ASSISTANT_API_KEY`

## 4. 本地测试方式

### 4.1 直接用文档页

打开 `doc.html`，在“智能助手”分组里测试：

```json
{"message":"你可以帮我做什么？"}
```

```json
{"message":"帮我查一下北京市3000以内的房源"}
```

```json
{"message":"帮我看看我有哪些预约"}
```

```json
{"message":"温都水城社区101介绍一下"}
```

### 4.2 用聊天页联调

打开 `assistant.html`：

- 可以直接输入问题
- 如果要查“我的预约/我的租约”，需要先登录并填入 `access-token`
- 页面会自动保存 token 到浏览器本地

## 5. 短信说明

短信发送当前是本地 mock，默认开启：

```yaml
aliyun:
  sms:
    mock-enabled: ${ALIYUN_SMS_MOCK_ENABLED:true}
```

行为如下：

- 调用 `/app/login/getCode` 时不会真实发送短信
- 控制台会打印 `TODO(local-dev)` 日志
- 日志里会直接输出本次验证码 `code`
- 验证码仍会写入 Redis，登录流程可继续测试

## 6. 当前实现结构

助手主代码位于：

- `web/web-app/src/main/java/com/atguigu/lease/web/app/chat/config`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/chat/controller`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/chat/dto`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/chat/service`
- `web/web-app/src/main/java/com/atguigu/lease/web/app/chat/tool`

页面文件位于：

- `web/web-app/src/main/resources/static/assistant.html`

## 7. 已知限制

- 大模型兼容层是否稳定，取决于本地实际配置的上游网关
- `doc.html` 中展示的是 JSON，换行会以转义形式出现；更适合在 `assistant.html` 中观察最终排版
- 当前房源/预约/租约回复仍主要是“文本对话”，不是完整业务卡片

## 8. 下一步建议

- 增加流式输出
- 增加多轮上下文
- 增加结构化 `payload` 返回，便于前端渲染房源卡片
- 增加关键链路集成测试
