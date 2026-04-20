package com.atguigu.lease.web.app.assistant.service.rag;

import com.atguigu.lease.web.app.assistant.config.AssistantRagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MilvusAssistantKnowledgeSearchService implements AssistantKnowledgeSearchService {

    private final MilvusServiceClient milvusServiceClient;
    private final EmbeddingModel embeddingModel;
    private final AssistantRagProperties properties;

    public MilvusAssistantKnowledgeSearchService(MilvusServiceClient milvusServiceClient,
                                                 EmbeddingModel embeddingModel,
                                                 AssistantRagProperties properties) {
        this.milvusServiceClient = milvusServiceClient;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    @Override
    public AssistantKnowledgeSearchResponse search(AssistantKnowledgeSearchRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuestion())) {
            return new AssistantKnowledgeSearchResponse(true, "知识检索问题为空", List.of());
        }

        float[] embedding = embeddingModel.embed(request.getQuestion().trim());
        if (embedding == null || embedding.length == 0) {
            return new AssistantKnowledgeSearchResponse(false, "Embedding 模型没有返回有效向量", List.of());
        }

        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(properties.getCollectionName())
                .withVectorFieldName(properties.getVectorFieldName())
                .withVectors(Collections.singletonList(toVector(embedding)))
                .withTopK(resolveTopK(request))
                .withMetricType(resolveMetricType())
                .withOutFields(List.of(
                        properties.getIdFieldName(),
                        properties.getTitleFieldName(),
                        properties.getContentFieldName(),
                        properties.getMetadataFieldName()
                ))
                .withParams("{\"nprobe\":" + properties.getNprobe() + "}")
                .build();

        R<SearchResults> response = milvusServiceClient.search(searchParam);
        if (response.getStatus() != 0) {
            return new AssistantKnowledgeSearchResponse(false,
                    "知识库查询失败: " + response.getMessage(), List.of());
        }

        List<AssistantKnowledgeSearchResult> results = parseResults(response.getData());
        String message = results.isEmpty() ? "未找到相关知识片段" : "知识检索完成";
        return new AssistantKnowledgeSearchResponse(true, message, results);
    }

    private MetricType resolveMetricType() {
        try {
            return MetricType.valueOf(properties.getMetricType().toUpperCase());
        } catch (Exception ignored) {
            return MetricType.L2;
        }
    }

    private int resolveTopK(AssistantKnowledgeSearchRequest request) {
        Integer topK = request.getTopK();
        if (topK == null || topK < 1) {
            return properties.getTopK();
        }
        return Math.min(topK, 10);
    }

    private List<Float> toVector(float[] values) {
        List<Float> vector = new ArrayList<>(values.length);
        for (float value : values) {
            vector.add(value);
        }
        return vector;
    }

    private List<AssistantKnowledgeSearchResult> parseResults(SearchResults searchResults) {
        if (searchResults == null) {
            return List.of();
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResults.getResults());
        int rowCount = wrapper.getIDScore(0).size();
        if (rowCount == 0) {
            return List.of();
        }

        List<AssistantKnowledgeSearchResult> results = new ArrayList<>(rowCount);
        for (int index = 0; index < rowCount; index++) {
            AssistantKnowledgeSearchResult result = new AssistantKnowledgeSearchResult();
            result.setId(readId(wrapper, index));
            result.setTitle(readField(wrapper, properties.getTitleFieldName(), index));
            result.setContent(readField(wrapper, properties.getContentFieldName(), index));
            result.setSnippet(buildSnippet(result.getContent()));
            result.setMetadata(readField(wrapper, properties.getMetadataFieldName(), index));
            result.setScore(wrapper.getIDScore(0).get(index).getScore());
            results.add(result);
        }
        return results;
    }

    private String readId(SearchResultsWrapper wrapper, int index) {
        String id = readField(wrapper, properties.getIdFieldName(), index);
        if (StringUtils.hasText(id)) {
            return id;
        }
        Object scoreId = wrapper.getIDScore(0).get(index).get("id");
        return scoreId == null ? String.valueOf(index + 1) : scoreId.toString();
    }

    private String readField(SearchResultsWrapper wrapper, String fieldName, int index) {
        try {
            List<?> data = wrapper.getFieldData(fieldName, 0);
            if (data == null || index >= data.size()) {
                return null;
            }
            Object value = data.get(index);
            return value == null ? null : value.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildSnippet(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        int maxLength = Math.max(properties.getMaxSnippetLength(), 80);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }
}
