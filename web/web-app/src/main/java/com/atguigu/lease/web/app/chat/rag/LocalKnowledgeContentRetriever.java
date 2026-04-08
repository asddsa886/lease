package com.atguigu.lease.web.app.chat.rag;

import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
public class LocalKnowledgeContentRetriever implements ContentRetriever {

    private final AssistantProperties assistantProperties;
    private final ResourcePatternResolver resourcePatternResolver;
    private volatile List<AssistantKnowledgeDocument> documents = List.of();

    public LocalKnowledgeContentRetriever(AssistantProperties assistantProperties,
                                          ResourcePatternResolver resourcePatternResolver) {
        this.assistantProperties = assistantProperties;
        this.resourcePatternResolver = resourcePatternResolver;
        this.documents = loadDocuments();
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (!assistantProperties.isRagEnabled() || !StringUtils.hasText(query.text())) {
            return List.of();
        }

        String question = query.text().trim();
        List<AssistantKnowledgeDocument> currentDocs = documents;
        if (currentDocs.isEmpty()) {
            currentDocs = loadDocuments();
            documents = currentDocs;
        }

        int maxMatches = assistantProperties.getMaxKnowledgeMatches() == null
                ? 2
                : assistantProperties.getMaxKnowledgeMatches();

        return currentDocs.stream()
                .map(document -> new ScoredDocument(document, score(document, question)))
                .filter(scoredDocument -> scoredDocument.score() > 0)
                .sorted(Comparator.comparingInt(ScoredDocument::score).reversed())
                .limit(Math.max(1, maxMatches))
                .map(scoredDocument -> Content.from(buildSnippet(scoredDocument.document())))
                .toList();
    }

    private List<AssistantKnowledgeDocument> loadDocuments() {
        try {
            Resource[] resources = resourcePatternResolver.getResources(assistantProperties.getKnowledgeLocation());
            List<AssistantKnowledgeDocument> loaded = new ArrayList<>();
            for (Resource resource : resources) {
                if (!resource.exists() || !resource.isReadable()) {
                    continue;
                }
                try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                    String text = FileCopyUtils.copyToString(reader);
                    AssistantKnowledgeDocument document = parseDocument(text, resource.getFilename());
                    if (document != null) {
                        loaded.add(document);
                    }
                }
            }
            return loaded;
        } catch (Exception e) {
            log.warn("Assistant knowledge loading failed", e);
            return List.of();
        }
    }

    private AssistantKnowledgeDocument parseDocument(String text, String fallbackName) {
        if (!StringUtils.hasText(text)) {
            return null;
        }

        String title = StringUtils.hasText(fallbackName) ? fallbackName : "未命名知识";
        List<String> keywords = new ArrayList<>();
        StringBuilder contentBuilder = new StringBuilder();

        String normalizedText = text.replace("\r\n", "\n").replace('\r', '\n');
        for (String rawLine : normalizedText.split("\n")) {
            String line = rawLine.trim();
            if (!StringUtils.hasText(line)) {
                contentBuilder.append('\n');
                continue;
            }
            if (line.startsWith("# ")) {
                title = line.substring(2).trim();
                continue;
            }
            if (line.startsWith("关键词：") || line.startsWith("关键词:")) {
                String rawKeywords = line.substring(line.indexOf('：') > -1 ? line.indexOf('：') + 1 : line.indexOf(':') + 1);
                for (String keyword : rawKeywords.split("[,，、]")) {
                    if (StringUtils.hasText(keyword)) {
                        keywords.add(keyword.trim());
                    }
                }
                continue;
            }
            contentBuilder.append(line).append('\n');
        }

        String content = contentBuilder.toString().trim();
        if (!StringUtils.hasText(content)) {
            return null;
        }
        return new AssistantKnowledgeDocument(title, keywords, content);
    }

    private int score(AssistantKnowledgeDocument document, String question) {
        String normalizedQuestion = question.toLowerCase(Locale.ROOT);
        int score = 0;

        if (document.title().toLowerCase(Locale.ROOT).contains(normalizedQuestion)) {
            score += 8;
        }
        if (document.content().toLowerCase(Locale.ROOT).contains(normalizedQuestion)) {
            score += 6;
        }
        for (String keyword : document.keywords()) {
            String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
            if (normalizedQuestion.contains(normalizedKeyword)) {
                score += 5;
            }
        }
        if ((normalizedQuestion.contains("什么意思")
                || normalizedQuestion.contains("是什么")
                || normalizedQuestion.contains("流程"))
                && !document.keywords().isEmpty()) {
            score += 1;
        }
        return score;
    }

    private String buildSnippet(AssistantKnowledgeDocument document) {
        int maxChars = assistantProperties.getMaxKnowledgeChars() == null
                ? 1200
                : assistantProperties.getMaxKnowledgeChars();
        maxChars = Math.max(maxChars, 400);
        String content = document.content();
        if (content.length() > maxChars) {
            content = content.substring(0, maxChars) + "...";
        }
        return "知识库参考《%s》：\n%s".formatted(document.title(), content);
    }

    private record ScoredDocument(AssistantKnowledgeDocument document, int score) {
    }
}
