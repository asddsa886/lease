package com.atguigu.lease.web.admin.service.assistant;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.AssistantKnowledgeDocument;
import com.atguigu.lease.web.admin.assistant.config.AssistantRagProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class AssistantDocumentChunkService {

    private final AssistantRagProperties properties;

    public AssistantDocumentChunkService(AssistantRagProperties properties) {
        this.properties = properties;
    }

    public List<AssistantKnowledgeChunk> split(AssistantKnowledgeDocument document, String parsedText) {
        String normalized = normalize(parsedText);
        if (!StringUtils.hasText(normalized)) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "知识文档解析后没有可用内容");
        }

        int maxLength = Math.max(properties.getChunkMaxLength(), 200);
        int overlap = Math.max(Math.min(properties.getChunkOverlap(), maxLength / 3), 0);
        String title = resolveTitle(document, normalized);

        List<AssistantKnowledgeChunk> chunks = new ArrayList<>();
        int start = 0;
        int chunkIndex = 0;
        while (start < normalized.length()) {
            int end = chooseSplitEnd(normalized, start, maxLength);
            String content = normalized.substring(start, end).trim();
            if (StringUtils.hasText(content)) {
                AssistantKnowledgeChunk chunk = new AssistantKnowledgeChunk();
                chunk.setChunkIndex(chunkIndex++);
                chunk.setTitle(title);
                chunk.setContent(content);
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            int nextStart = Math.max(end - overlap, start + 1);
            start = skipLeadingWhitespace(normalized, nextStart);
        }

        if (chunks.isEmpty()) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "知识文档切片结果为空");
        }
        return chunks;
    }

    private String resolveTitle(AssistantKnowledgeDocument document, String normalized) {
        if (document != null && StringUtils.hasText(document.getTitle())) {
            return truncate(document.getTitle().trim(), properties.getTitleMaxLength());
        }
        for (String line : normalized.split("\n")) {
            String candidate = line.trim();
            if (candidate.startsWith("#")) {
                candidate = candidate.replaceFirst("^#+\\s*", "");
            }
            if (StringUtils.hasText(candidate)) {
                return truncate(candidate, properties.getTitleMaxLength());
            }
        }
        return "知识文档";
    }

    private String normalize(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u3000', ' ');
        normalized = normalized.replaceAll("[\\t\\x0B\\f]+", " ");
        normalized = normalized.replaceAll("(?m)[ ]+$", "");
        normalized = normalized.replaceAll("\n{3,}", "\n\n");
        return normalized.trim();
    }

    private int chooseSplitEnd(String content, int start, int maxLength) {
        int tentativeEnd = Math.min(start + maxLength, content.length());
        if (tentativeEnd >= content.length()) {
            return content.length();
        }
        int lowerBound = start + maxLength / 2;
        int split = findSplitPosition(content, tentativeEnd, lowerBound);
        return split > lowerBound ? split : tentativeEnd;
    }

    private int findSplitPosition(String content, int tentativeEnd, int lowerBound) {
        String delimiters = "\n。！？；;.!?";
        for (int index = tentativeEnd - 1; index > lowerBound; index--) {
            if (delimiters.indexOf(content.charAt(index)) >= 0) {
                return index + 1;
            }
        }
        return -1;
    }

    private int skipLeadingWhitespace(String content, int index) {
        int current = index;
        while (current < content.length() && Character.isWhitespace(content.charAt(current))) {
            current++;
        }
        return current;
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(maxLength, 1));
    }
}
