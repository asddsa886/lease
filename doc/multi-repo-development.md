# 多仓开发说明

当前项目采用“1 个后端主仓 + 3 个前端并列仓”的结构：

- `F:\code\java\lease`
  - 后端主仓
  - 包含 `common`、`model`、`web`、`sql`、`doc`、`docker`
- `F:\code\java\lease-admin-front`
  - 管理端前端仓
- `F:\code\java\lease-h5-front`
  - C 端 H5 前端仓
- `F:\code\java\lease-ops-front`
  - 运维日志助手前端仓

## 本地启动顺序

### 1. 后端主仓

按实际需要启动下面服务：

- `web-admin`
- `web-app`
- `web-ops`

其中运维前端只依赖 `web-ops`，默认地址为：

- `http://localhost:8083`

### 2. 三套前端仓

各自进入对应目录执行：

```bash
npm install
npm run dev
```

## 仓库职责

### 后端主仓 `lease`

- 统一维护 Java 后端代码
- 维护数据库脚本、Docker 配置、项目文档
- 不再托管任何前端源码

### 管理端仓 `lease-admin-front`

- 只维护后台管理前端
- 独立提交、独立发版

### H5 仓 `lease-h5-front`

- 只维护用户侧 H5 前端
- 独立提交、独立发版

### 运维仓 `lease-ops-front`

- 只维护 `web-ops` 的独立运维工作台
- 默认通过 `VITE_APP_OPS_BASE_URL` 对接 `web-ops`

## 运维前端联调

`lease-ops-front` 默认使用：

```bash
VITE_APP_OPS_BASE_URL=http://localhost:8083
```

运维前端核心能力：

- 当前扫描摘要
- 历史记录列表
- 问题详情抽屉
- 普通聊天 / 流式聊天
- 工具事件展示
