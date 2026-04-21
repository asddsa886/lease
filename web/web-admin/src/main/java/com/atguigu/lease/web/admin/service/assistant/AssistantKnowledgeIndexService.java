package com.atguigu.lease.web.admin.service.assistant;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.AssistantKnowledgeDocument;
import com.atguigu.lease.web.admin.assistant.config.AssistantRagProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.index.CreateIndexParam;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class AssistantKnowledgeIndexService {

    private static final String DOCUMENT_ID_FIELD = "document_id";
    private static final String CHUNK_INDEX_FIELD = "chunk_index";
    private static final String SCOPE_FIELD = "scope";
    private static final String BIZ_ID_FIELD = "biz_id";

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final AssistantRagProperties properties;
    private final ObjectMapper objectMapper;

    public AssistantKnowledgeIndexService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                          AssistantRagProperties properties,
                                          ObjectMapper objectMapper) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void rebuildDocument(AssistantKnowledgeDocument document, List<AssistantKnowledgeChunk> chunks) {
        validateAvailability();
        if (document == null || document.getId() == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "知识文档不存在，无法建立索引");
        }
        if (chunks == null || chunks.isEmpty()) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "知识文档切片为空，无法建立索引");
        }

        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "EmbeddingModel 未就绪，请检查 Ollama Embedding 配置");
        }

        MilvusServiceClient client = null;
        try {
            List<String> ids = new ArrayList<>(chunks.size());
            List<Long> documentIds = new ArrayList<>(chunks.size());
            List<Long> chunkIndexes = new ArrayList<>(chunks.size());
            List<String> scopes = new ArrayList<>(chunks.size());
            List<Long> bizIds = new ArrayList<>(chunks.size());
            List<String> titles = new ArrayList<>(chunks.size());
            List<String> contents = new ArrayList<>(chunks.size());
            List<String> metadatas = new ArrayList<>(chunks.size());
            List<List<Float>> vectors = new ArrayList<>(chunks.size());

            for (AssistantKnowledgeChunk chunk : chunks) {
                float[] embedding = embeddingModel.embed(chunk.getContent());
                if (embedding == null || embedding.length == 0) {
                    throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "Embedding 模型没有返回有效向量");
                }
                ids.add(document.getId() + "_" + chunk.getChunkIndex());
                documentIds.add(document.getId());
                chunkIndexes.add((long) chunk.getChunkIndex());
                scopes.add(document.getScope().name());
                bizIds.add(document.getBizId() == null ? 0L : document.getBizId());
                titles.add(truncate(chunk.getTitle(), properties.getTitleMaxLength()));
                contents.add(truncate(chunk.getContent(), properties.getContentMaxLength()));
                metadatas.add(buildMetadata(document, chunk));
                vectors.add(toVector(embedding));
            }

            client = openClient();
            ensureCollection(client, vectors.get(0).size());
            deleteDocumentInternal(client, document.getId());

            List<InsertParam.Field> fields = List.of(
                    InsertParam.Field.builder().name(properties.getIdFieldName()).values(ids).build(),
                    InsertParam.Field.builder().name(DOCUMENT_ID_FIELD).values(documentIds).build(),
                    InsertParam.Field.builder().name(CHUNK_INDEX_FIELD).values(chunkIndexes).build(),
                    InsertParam.Field.builder().name(SCOPE_FIELD).values(scopes).build(),
                    InsertParam.Field.builder().name(BIZ_ID_FIELD).values(bizIds).build(),
                    InsertParam.Field.builder().name(properties.getTitleFieldName()).values(titles).build(),
                    InsertParam.Field.builder().name(properties.getContentFieldName()).values(contents).build(),
                    InsertParam.Field.builder().name(properties.getMetadataFieldName()).values(metadatas).build(),
                    InsertParam.Field.builder().name(properties.getVectorFieldName()).values(vectors).build()
            );

            checkStatus(client.insert(InsertParam.newBuilder()
                    .withCollectionName(properties.getCollectionName())
                    .withFields(fields)
                    .build()), "写入知识切片到 Milvus 失败");

            flushCollection(client);
            loadCollection(client);
        } catch (LeaseException e) {
            throw e;
        } catch (Exception e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "知识文档索引失败: " + e.getMessage());
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    public void deleteDocumentIndex(Long documentId) {
        if (!properties.isEnabled() || documentId == null) {
            return;
        }
        MilvusServiceClient client = null;
        try {
            client = openClient();
            deleteDocumentInternal(client, documentId);
            flushCollection(client);
        } catch (LeaseException e) {
            throw e;
        } catch (Exception e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "删除知识文档向量索引失败: " + e.getMessage());
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private void validateAvailability() {
        if (!properties.isEnabled()) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "助手知识库索引未启用，请先打开 lease.assistant.rag.enabled");
        }
        if (!StringUtils.hasText(properties.getHost())) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "Milvus 地址未配置，请检查 lease.assistant.rag.host");
        }
    }

    private MilvusServiceClient openClient() {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(properties.getHost())
                .withPort(properties.getPort())
                .withConnectTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
        return new MilvusServiceClient(connectParam);
    }

    private void ensureCollection(MilvusServiceClient client, int vectorDimension) {
        R<Boolean> hasCollectionResponse = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .build());
        checkStatus(hasCollectionResponse, "检查 Milvus 集合失败");
        if (Boolean.TRUE.equals(hasCollectionResponse.getData())) {
            loadCollection(client);
            return;
        }

        FieldType idField = FieldType.newBuilder()
                .withName(properties.getIdFieldName())
                .withDataType(DataType.VarChar)
                .withPrimaryKey(true)
                .withAutoID(false)
                .withMaxLength(96)
                .build();
        FieldType documentIdField = FieldType.newBuilder()
                .withName(DOCUMENT_ID_FIELD)
                .withDataType(DataType.Int64)
                .build();
        FieldType chunkIndexField = FieldType.newBuilder()
                .withName(CHUNK_INDEX_FIELD)
                .withDataType(DataType.Int64)
                .build();
        FieldType scopeField = FieldType.newBuilder()
                .withName(SCOPE_FIELD)
                .withDataType(DataType.VarChar)
                .withMaxLength(32)
                .build();
        FieldType bizIdField = FieldType.newBuilder()
                .withName(BIZ_ID_FIELD)
                .withDataType(DataType.Int64)
                .build();
        FieldType titleField = FieldType.newBuilder()
                .withName(properties.getTitleFieldName())
                .withDataType(DataType.VarChar)
                .withMaxLength(properties.getTitleMaxLength())
                .build();
        FieldType contentField = FieldType.newBuilder()
                .withName(properties.getContentFieldName())
                .withDataType(DataType.VarChar)
                .withMaxLength(properties.getContentMaxLength())
                .build();
        FieldType metadataField = FieldType.newBuilder()
                .withName(properties.getMetadataFieldName())
                .withDataType(DataType.VarChar)
                .withMaxLength(properties.getMetadataMaxLength())
                .build();
        FieldType vectorField = FieldType.newBuilder()
                .withName(properties.getVectorFieldName())
                .withDataType(DataType.FloatVector)
                .withDimension(vectorDimension)
                .build();

        CreateCollectionParam createCollectionParam = CreateCollectionParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withDescription("lease 助手知识库")
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .withFieldTypes(List.of(
                        idField,
                        documentIdField,
                        chunkIndexField,
                        scopeField,
                        bizIdField,
                        titleField,
                        contentField,
                        metadataField,
                        vectorField
                ))
                .build();
        checkStatus(client.createCollection(createCollectionParam), "创建 Milvus 集合失败");

        CreateIndexParam createIndexParam = CreateIndexParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withFieldName(properties.getVectorFieldName())
                .withIndexName(properties.getVectorFieldName() + "_idx")
                .withIndexType(resolveIndexType())
                .withMetricType(resolveMetricType())
                .withExtraParam("{\"nlist\":" + properties.getNlist() + "}")
                .withSyncMode(Boolean.TRUE)
                .withSyncWaitingTimeout(30L)
                .build();
        checkStatus(client.createIndex(createIndexParam), "创建 Milvus 向量索引失败");

        loadCollection(client);
    }

    private void deleteDocumentInternal(MilvusServiceClient client, Long documentId) {
        R<Boolean> hasCollectionResponse = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .build());
        checkStatus(hasCollectionResponse, "检查 Milvus 集合失败");
        if (!Boolean.TRUE.equals(hasCollectionResponse.getData())) {
            return;
        }

        checkStatus(client.delete(DeleteParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withExpr(DOCUMENT_ID_FIELD + " == " + documentId)
                .build()), "删除旧知识切片失败");
    }

    private void loadCollection(MilvusServiceClient client) {
        checkStatus(client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withSyncLoad(Boolean.TRUE)
                .withSyncLoadWaitingTimeout(30L)
                .build()), "加载 Milvus 集合失败");
    }

    private void flushCollection(MilvusServiceClient client) {
        checkStatus(client.flush(FlushParam.newBuilder()
                .addCollectionName(properties.getCollectionName())
                .withSyncFlush(Boolean.TRUE)
                .withSyncFlushWaitingTimeout(30L)
                .build()), "刷新 Milvus 集合失败");
    }

    private IndexType resolveIndexType() {
        try {
            return IndexType.valueOf(properties.getIndexType().toUpperCase());
        } catch (Exception ignored) {
            return IndexType.IVF_FLAT;
        }
    }

    private MetricType resolveMetricType() {
        try {
            return MetricType.valueOf(properties.getMetricType().toUpperCase());
        } catch (Exception ignored) {
            return MetricType.L2;
        }
    }

    private void checkStatus(R<?> response, String message) {
        if (response == null || response.getStatus() != 0) {
            String detail = response == null ? "" : response.getMessage();
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), message + (StringUtils.hasText(detail) ? ": " + detail : ""));
        }
    }

    private List<Float> toVector(float[] values) {
        List<Float> vector = new ArrayList<>(values.length);
        for (float value : values) {
            vector.add(value);
        }
        return vector;
    }

    private String buildMetadata(AssistantKnowledgeDocument document, AssistantKnowledgeChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", document.getId());
        metadata.put("scope", document.getScope().name());
        metadata.put("bizId", document.getBizId());
        metadata.put("fileName", document.getFileName());
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("version", document.getVersion());
        metadata.put("indexedAt", new Date());
        try {
            return truncate(objectMapper.writeValueAsString(metadata), properties.getMetadataMaxLength());
        } catch (JsonProcessingException e) {
            return truncate(metadata.toString(), properties.getMetadataMaxLength());
        }
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(maxLength, 1));
    }
}
