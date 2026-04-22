package com.atguigu.lease.web.ops.service.history;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.ops.config.OpsHistoryProperties;
import com.atguigu.lease.web.ops.dto.OpsLogHistorySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class OpsLogHistoryService {

    private static final String CURRENT_FILE_NAME = "current-scan.json";

    private final ObjectMapper objectMapper;
    private final OpsHistoryProperties historyProperties;

    public OpsLogHistoryService(ObjectMapper objectMapper, OpsHistoryProperties historyProperties) {
        this.objectMapper = objectMapper;
        this.historyProperties = historyProperties;
    }

    public Optional<OpsLogHistorySnapshot> loadCurrent() {
        Path currentFile = currentFile();
        if (!Files.exists(currentFile)) {
            return Optional.empty();
        }
        return Optional.of(readSnapshot(currentFile));
    }

    public void saveCurrent(OpsLogHistorySnapshot snapshot) {
        writeSnapshot(currentFile(), snapshot);
    }

    public boolean saveHistory(OpsLogHistorySnapshot snapshot, boolean forceStore) {
        if (!forceStore) {
            Optional<OpsLogHistorySnapshot> latestHistory = latestHistory();
            if (latestHistory.isPresent()
                    && Objects.equals(latestHistory.get().getAnalysisFingerprint(), snapshot.getAnalysisFingerprint())) {
                snapshot.setStoredInHistory(false);
                snapshot.setHistoryStoredAt(null);
                return false;
            }
        }
        snapshot.setStoredInHistory(true);
        snapshot.setHistoryStoredAt(new Date());
        writeSnapshot(historyFile(snapshot.getScanId()), snapshot);
        trimHistory();
        return true;
    }

    public Optional<OpsLogHistorySnapshot> latestHistory() {
        return listHistorySnapshots(historyProperties.getMaxScans()).stream().findFirst();
    }

    public List<OpsLogHistorySnapshot> listHistorySnapshots(int limit) {
        ensureDirectories();
        int normalizedLimit = Math.max(1, Math.min(limit, historyProperties.getMaxScans()));
        try (Stream<Path> stream = Files.list(historyDir())) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readSnapshot)
                    .sorted(Comparator.comparing(OpsLogHistorySnapshot::getHistoryStoredAt,
                            Comparator.nullsLast(Date::compareTo)).reversed())
                    .limit(normalizedLimit)
                    .toList();
        } catch (IOException e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "读取日志历史失败: " + e.getMessage());
        }
    }

    public Optional<OpsLogHistorySnapshot> loadHistory(Long scanId) {
        if (scanId == null) {
            return Optional.empty();
        }
        Path file = historyFile(scanId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(readSnapshot(file));
    }

    public List<OpsLogHistorySnapshot> searchHistory(String keyword,
                                                     String category,
                                                     String issueType,
                                                     Date from,
                                                     Date to,
                                                     int limit) {
        String normalizedKeyword = normalizeKeyword(keyword);
        return listHistorySnapshots(historyProperties.getMaxScans()).stream()
                .filter(snapshot -> matchesTimeRange(snapshot, from, to))
                .filter(snapshot -> matchesIssue(snapshot, category, issueType))
                .filter(snapshot -> matchesKeyword(snapshot, normalizedKeyword))
                .limit(Math.max(1, Math.min(limit, historyProperties.getMaxScans())))
                .toList();
    }

    private boolean matchesTimeRange(OpsLogHistorySnapshot snapshot, Date from, Date to) {
        Date startedAt = snapshot.getStartedAt();
        if (startedAt == null) {
            return from == null && to == null;
        }
        if (from != null && startedAt.before(from)) {
            return false;
        }
        return to == null || !startedAt.after(to);
    }

    private boolean matchesIssue(OpsLogHistorySnapshot snapshot, String category, String issueType) {
        if (!StringUtils.hasText(category) && !StringUtils.hasText(issueType)) {
            return true;
        }
        if (snapshot.getFindings() == null) {
            return false;
        }
        return snapshot.getFindings().stream().anyMatch(issue ->
                (!StringUtils.hasText(category) || category.equalsIgnoreCase(issue.getCategory()))
                        && (!StringUtils.hasText(issueType) || issueType.equalsIgnoreCase(issue.getIssueType())));
    }

    private boolean matchesKeyword(OpsLogHistorySnapshot snapshot, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        if (containsIgnoreCase(snapshot.getSummary(), keyword) || containsIgnoreCase(snapshot.getErrorMessage(), keyword)) {
            return true;
        }
        if (snapshot.getFindings() == null) {
            return false;
        }
        return snapshot.getFindings().stream().anyMatch(issue ->
                containsIgnoreCase(issue.getTitle(), keyword)
                        || containsIgnoreCase(issue.getRootCause(), keyword)
                        || containsIgnoreCase(issue.getSuggestion(), keyword)
                        || (issue.getEvidences() != null && issue.getEvidences().stream()
                        .anyMatch(evidence -> containsIgnoreCase(evidence.getSnippet(), keyword))));
    }

    private String normalizeKeyword(String keyword) {
        return StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : null;
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        return StringUtils.hasText(text) && StringUtils.hasText(keyword) && text.toLowerCase().contains(keyword);
    }

    private void trimHistory() {
        List<Path> historyFiles;
        try (Stream<Path> stream = Files.list(historyDir())) {
            historyFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(this::lastModifiedSafe).reversed())
                    .toList();
        } catch (IOException e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "清理日志历史失败: " + e.getMessage());
        }
        for (int index = historyProperties.getMaxScans(); index < historyFiles.size(); index++) {
            try {
                Files.deleteIfExists(historyFiles.get(index));
            } catch (IOException ignored) {
            }
        }
    }

    private long lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private OpsLogHistorySnapshot readSnapshot(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), OpsLogHistorySnapshot.class);
        } catch (IOException e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "读取日志快照失败: " + e.getMessage());
        }
    }

    private void writeSnapshot(Path path, OpsLogHistorySnapshot snapshot) {
        ensureDirectories();
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snapshot);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "写入日志快照失败: " + e.getMessage());
        }
    }

    private void ensureDirectories() {
        try {
            Files.createDirectories(rootDir());
            Files.createDirectories(historyDir());
        } catch (IOException e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "初始化日志历史目录失败: " + e.getMessage());
        }
    }

    private Path rootDir() {
        return Paths.get(historyProperties.getDir());
    }

    private Path historyDir() {
        return rootDir().resolve("history");
    }

    private Path currentFile() {
        return rootDir().resolve(CURRENT_FILE_NAME);
    }

    private Path historyFile(Long scanId) {
        return historyDir().resolve(scanId + ".json");
    }
}
