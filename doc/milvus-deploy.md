# Milvus 本地部署说明

这份编排文件位于 [docker/lease-rag-milvus.yml](F:\code\java\lease\docker\lease-rag-milvus.yml)，专门服务当前 `lease` 项目。

## 设计目标

- 容器、卷、网络统一使用 `lease-rag-*` 命名，和当前项目保持一致。
- 使用 Docker named volume，避免把 `etcd` 和 `milvus` 数据挂到 Windows 桌面目录，降低 `slow fdatasync` 导致的超时退出风险。
- 为 `etcd`、`minio`、`milvus` 加上 `restart: unless-stopped`，容器异常退出后可自动拉起。
- `milvus` 依赖 `etcd` 和 `minio` 的健康检查结果启动，减少依赖未就绪导致的异常。

## 默认端口

- Milvus gRPC: `19530`
- Milvus health: `19091`
- MinIO API: `19000`
- MinIO Console: `19001`
- Attu: `18000`，仅在启用 `tools` profile 时启动

## 启动命令

启动基础服务：

```powershell
docker compose -f .\docker\lease-rag-milvus.yml up -d
```

连 Attu 一起启动：

```powershell
docker compose -f .\docker\lease-rag-milvus.yml --profile tools up -d
```

查看状态：

```powershell
docker compose -f .\docker\lease-rag-milvus.yml ps
```

查看 Milvus 日志：

```powershell
docker compose -f .\docker\lease-rag-milvus.yml logs -f milvus
```

查看 etcd 日志：

```powershell
docker compose -f .\docker\lease-rag-milvus.yml logs -f etcd
```

## 关闭与重建

停止服务但保留数据：

```powershell
docker compose -f .\docker\lease-rag-milvus.yml down
```

彻底清理并重建：

```powershell
docker compose -f .\docker\lease-rag-milvus.yml down -v
```

## 接入建议

- Spring AI 或 Java 客户端连 Milvus 时，优先使用 `localhost:19530`。
- 本地开发阶段先确认 `etcd` 和 `milvus` 都稳定运行，再接入文档切分、embedding、向量写入和检索链路。
- 如果后续压测仍出现 `request timed out` 或 `slow fdatasync`，优先检查 Docker Desktop 分配的 CPU、内存和磁盘位置，而不是先怀疑业务代码。
