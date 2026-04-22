package com.atguigu.lease.web.ops.service.log;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.ops.config.OpsLogScanProperties;
import com.atguigu.lease.web.ops.dto.OpsEvidenceItem;
import com.atguigu.lease.web.ops.dto.OpsIssueDetail;
import com.atguigu.lease.web.ops.dto.OpsIssueSummary;
import com.atguigu.lease.web.ops.dto.OpsLogHistorySnapshot;
import com.atguigu.lease.web.ops.dto.OpsLogScanReport;
import com.atguigu.lease.web.ops.service.history.OpsLogHistoryService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

@Slf4j
@Service
public class OpsLogScanService {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );
    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ISO_LOCAL_DATE
    );

    private final OpsLogScanProperties properties;
    private final OpsLogHistoryService historyService;
    private final OpsLogAnalyzer logAnalyzer;
    private final AtomicLong scanIdGenerator = new AtomicLong(System.currentTimeMillis());

    public OpsLogScanService(OpsLogScanProperties properties,
                             OpsLogHistoryService historyService,
                             OpsLogAnalyzer logAnalyzer) {
        this.properties = properties;
        this.historyService = historyService;
        this.logAnalyzer = logAnalyzer;
    }

    public OpsLogScanReport runScan(String triggerType) {
        long scanId = nextScanId();
        Date startedAt = new Date();
        try {
            List<Path> files = discoverFiles();
            List<OpsLogAnalyzer.LogSource> sources = loadSources(files);
            OpsLogAnalyzer.AnalysisResult analysisResult = logAnalyzer.analyze(sources, properties.getSlowSqlThresholdMs());
            OpsLogHistorySnapshot snapshot = buildSuccessSnapshot(scanId, triggerType, startedAt, new Date(), files, analysisResult.issues());
            historyService.saveCurrent(snapshot);
            if (shouldWriteHistory(triggerType)) {
                boolean forceStore = "MANUAL".equalsIgnoreCase(triggerType);
                historyService.saveHistory(snapshot, forceStore);
                historyService.saveCurrent(snapshot);
            }
            return buildReport(snapshot);
        } catch (Exception e) {
            log.error("Ops log scan failed, scanId={}", scanId, e);
            OpsLogHistorySnapshot failedSnapshot = buildFailureSnapshot(scanId, triggerType, startedAt, e);
            historyService.saveCurrent(failedSnapshot);
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "日志扫描失败: " + e.getMessage());
        }
    }

    public OpsLogScanReport getLatestReport() {
        return historyService.loadCurrent()
                .map(this::buildReport)
                .orElseGet(this::buildEmptyReport);
    }

    public List<OpsIssueSummary> listLatestFindings(String category) {
        return historyService.loadCurrent()
                .map(snapshot -> toSummaries(filterByCategory(snapshot.getFindings(), category), 20))
                .orElse(List.of());
    }

    public List<OpsIssueSummary> listIssueGroups(String category, Integer limit) {
        return historyService.loadCurrent()
                .map(snapshot -> toSummaries(filterByCategory(snapshot.getFindings(), category), normalizeLimit(limit, 10, 20)))
                .orElse(List.of());
    }

    public Map<String, Object> getIssueDetail(Long issueGroupId, Long scanId) {
        Optional<OpsIssueDetail> issueDetail = loadSnapshot(scanId)
                .flatMap(snapshot -> snapshot.getFindings().stream()
                        .filter(issue -> Objects.equals(issue.getId(), issueGroupId))
                        .findFirst());
        if (issueDetail.isEmpty()) {
            return Map.of("message", "问题不存在");
        }
        return Map.of("issue", toSummary(issueDetail.get()), "evidences", issueDetail.get().getEvidences());
    }

    public List<Map<String, Object>> listSlowSqlFindings(Integer limit) {
        return loadByIssueTypes(List.of("HIGH_REQUEST_LATENCY", "DB_POOL_EXHAUSTED", "DB_LOCK_CONTENTION", "DB_TIMEOUT"),
                normalizeLimit(limit, 5, 20));
    }

    public List<Map<String, Object>> listDependencyFailures(Integer limit) {
        return loadByCategory("INFRA", normalizeLimit(limit, 5, 20));
    }

    public List<Map<String, Object>> searchLogEvidence(String keyword, Long scanId, Long issueGroupId, Integer limit) {
        return loadSnapshot(scanId)
                .map(snapshot -> snapshot.getFindings().stream()
                        .filter(issue -> issueGroupId == null || Objects.equals(issue.getId(), issueGroupId))
                        .flatMap(issue -> issue.getEvidences().stream())
                        .filter(evidence -> !StringUtils.hasText(keyword)
                                || containsIgnoreCase(evidence.getSnippet(), keyword)
                                || containsIgnoreCase(evidence.getFileName(), keyword))
                        .sorted(Comparator.comparing(OpsEvidenceItem::getEventTime, Comparator.nullsLast(Date::compareTo)).reversed())
                        .limit(normalizeLimit(limit, 5, 20))
                        .map(evidence -> Map.<String, Object>of(
                                "id", evidence.getId(),
                                "scanId", evidence.getScanId(),
                                "issueId", evidence.getIssueId(),
                                "fileName", Optional.ofNullable(evidence.getFileName()).orElse(""),
                                "level", Optional.ofNullable(evidence.getLevel()).orElse("UNKNOWN"),
                                "lineStart", Optional.ofNullable(evidence.getLineStart()).orElse(0),
                                "lineEnd", Optional.ofNullable(evidence.getLineEnd()).orElse(0),
                                "eventTime", Optional.ofNullable(evidence.getEventTime()).orElse(new Date(0)),
                                "snippet", Optional.ofNullable(evidence.getSnippet()).orElse("")
                        ))
                        .toList())
                .orElse(List.of());
    }

    public List<OpsLogScanReport> listHistoryReports(Integer limit) {
        return historyService.listHistorySnapshots(normalizeLimit(limit, 10, 50)).stream()
                .map(this::buildReport)
                .toList();
    }

    public OpsLogHistorySnapshot getHistoryDetail(Long scanId) {
        return historyService.loadHistory(scanId)
                .orElseThrow(() -> new LeaseException(ResultCodeEnum.DATA_ERROR.getCode(), "扫描历史不存在"));
    }

    public List<Map<String, Object>> searchHistory(String keyword,
                                                   String category,
                                                   String issueType,
                                                   String fromTime,
                                                   String toTime,
                                                   Integer limit) {
        Date from = parseDate(fromTime, false);
        Date to = parseDate(toTime, true);
        return historyService.searchHistory(keyword, category, issueType, from, to, normalizeLimit(limit, 10, 50)).stream()
                .map(snapshot -> Map.<String, Object>of(
                        "scanId", snapshot.getScanId(),
                        "triggerType", snapshot.getTriggerType(),
                        "summary", snapshot.getSummary(),
                        "startedAt", snapshot.getStartedAt(),
                        "finishedAt", snapshot.getFinishedAt(),
                        "issueGroupCount", snapshot.getIssueGroupCount(),
                        "categoryCounts", snapshot.getCategoryCounts(),
                        "topFindings", toSummaries(snapshot.getFindings(), 3)
                ))
                .toList();
    }

    private List<Map<String, Object>> loadByCategory(String category, int limit) {
        return historyService.loadCurrent()
                .map(snapshot -> toSummaries(filterByCategory(snapshot.getFindings(), category), limit).stream()
                        .map(summary -> Map.<String, Object>of(
                                "id", summary.getId(),
                                "category", summary.getCategory(),
                                "issueType", summary.getIssueType(),
                                "severity", summary.getSeverity(),
                                "title", summary.getTitle(),
                                "rootCause", Optional.ofNullable(summary.getRootCause()).orElse(""),
                                "occurrenceCount", Optional.ofNullable(summary.getOccurrenceCount()).orElse(0),
                                "suggestion", Optional.ofNullable(summary.getSuggestion()).orElse("")
                        ))
                        .toList())
                .orElse(List.of());
    }

    private List<Map<String, Object>> loadByIssueTypes(List<String> issueTypes, int limit) {
        return historyService.loadCurrent()
                .map(snapshot -> snapshot.getFindings().stream()
                        .filter(issue -> issueTypes.contains(issue.getIssueType()))
                        .sorted(this::compareIssueDetail)
                        .limit(limit)
                        .map(issue -> Map.<String, Object>of(
                                "id", issue.getId(),
                                "category", issue.getCategory(),
                                "issueType", issue.getIssueType(),
                                "severity", issue.getSeverity(),
                                "title", issue.getTitle(),
                                "rootCause", Optional.ofNullable(issue.getRootCause()).orElse(""),
                                "occurrenceCount", Optional.ofNullable(issue.getOccurrenceCount()).orElse(0),
                                "suggestion", Optional.ofNullable(issue.getSuggestion()).orElse("")
                        ))
                        .toList())
                .orElse(List.of());
    }

    private List<OpsIssueDetail> filterByCategory(List<OpsIssueDetail> issues, String category) {
        if (issues == null) {
            return List.of();
        }
        return issues.stream()
                .filter(issue -> !StringUtils.hasText(category) || category.equalsIgnoreCase(issue.getCategory()))
                .sorted(this::compareIssueDetail)
                .toList();
    }

    private OpsLogHistorySnapshot buildSuccessSnapshot(Long scanId,
                                                       String triggerType,
                                                       Date startedAt,
                                                       Date finishedAt,
                                                       List<Path> files,
                                                       List<OpsLogAnalyzer.DetectedIssue> issues) {
        List<OpsIssueDetail> findings = buildFindings(scanId, issues);
        return OpsLogHistorySnapshot.builder()
                .scanId(scanId)
                .status("SUCCESS")
                .triggerType(triggerType)
                .directories(List.copyOf(properties.getDirectories()))
                .scannedFiles(files.stream().map(Path::toString).toList())
                .lookbackHours(properties.getLookbackHours())
                .maxFiles(properties.getMaxFiles())
                .maxBytesPerFile(properties.getMaxBytesPerFile())
                .fileCount(files.size())
                .issueGroupCount(findings.size())
                .summary(buildSummary(findings, files.size()))
                .categoryCounts(buildCategoryCounts(findings))
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .storedInHistory(false)
                .analysisFingerprint(buildAnalysisFingerprint(findings))
                .findings(findings)
                .build();
    }

    private OpsLogHistorySnapshot buildFailureSnapshot(Long scanId, String triggerType, Date startedAt, Exception e) {
        String message = truncate(Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()), 500);
        return OpsLogHistorySnapshot.builder()
                .scanId(scanId)
                .status("FAILED")
                .triggerType(triggerType)
                .directories(List.copyOf(properties.getDirectories()))
                .scannedFiles(List.of())
                .lookbackHours(properties.getLookbackHours())
                .maxFiles(properties.getMaxFiles())
                .maxBytesPerFile(properties.getMaxBytesPerFile())
                .fileCount(0)
                .issueGroupCount(0)
                .summary("日志扫描失败，请检查扫描目录、文件权限或日志格式")
                .categoryCounts(buildCategoryCounts(List.of()))
                .startedAt(startedAt)
                .finishedAt(new Date())
                .storedInHistory(false)
                .analysisFingerprint("FAILED:" + DigestUtils.md5Hex(message))
                .errorMessage(message)
                .findings(List.of())
                .build();
    }

    private List<OpsIssueDetail> buildFindings(Long scanId, List<OpsLogAnalyzer.DetectedIssue> issues) {
        List<OpsIssueDetail> findings = new ArrayList<>();
        long issueIndex = 1;
        for (OpsLogAnalyzer.DetectedIssue issue : issues) {
            long issueId = scanId * 1000 + issueIndex++;
            List<OpsEvidenceItem> evidences = new ArrayList<>();
            long evidenceIndex = 1;
            for (OpsLogAnalyzer.DetectedEvidence evidence : issue.getEvidences()) {
                evidences.add(OpsEvidenceItem.builder()
                        .id(issueId * 100 + evidenceIndex++)
                        .scanId(scanId)
                        .issueId(issueId)
                        .fileName(evidence.fileName())
                        .level(evidence.level())
                        .keyword(evidence.keyword())
                        .lineStart(evidence.lineStart())
                        .lineEnd(evidence.lineEnd())
                        .eventTime(evidence.eventTime())
                        .snippet(evidence.snippet())
                        .build());
            }
            findings.add(OpsIssueDetail.builder()
                    .id(issueId)
                    .scanId(scanId)
                    .category(issue.getCategory())
                    .issueType(issue.getIssueType())
                    .severity(issue.getSeverity())
                    .fingerprint(issue.getFingerprint())
                    .title(issue.getTitle())
                    .rootCause(issue.getRootCause())
                    .suggestion(issue.getSuggestion())
                    .loggerName(issue.getLoggerName())
                    .occurrenceCount(issue.getOccurrenceCount())
                    .firstSeenAt(issue.getFirstSeenAt())
                    .lastSeenAt(issue.getLastSeenAt())
                    .evidenceCount(evidences.size())
                    .evidences(evidences)
                    .build());
        }
        return findings.stream().sorted(this::compareIssueDetail).toList();
    }

    private String buildAnalysisFingerprint(List<OpsIssueDetail> findings) {
        if (findings == null || findings.isEmpty()) {
            return "EMPTY";
        }
        String value = findings.stream()
                .sorted(Comparator.comparing(OpsIssueDetail::getFingerprint, Comparator.nullsLast(String::compareTo)))
                .map(issue -> issue.getFingerprint()
                        + "|"
                        + issue.getOccurrenceCount()
                        + "|"
                        + Optional.ofNullable(issue.getLastSeenAt()).map(Date::getTime).orElse(0L)
                        + "|"
                        + issue.getEvidences().stream()
                        .map(OpsEvidenceItem::getSnippet)
                        .filter(StringUtils::hasText)
                        .limit(2)
                        .reduce((left, right) -> left + "#" + right)
                        .orElse(""))
                .reduce((left, right) -> left + "||" + right)
                .orElse("EMPTY");
        return DigestUtils.md5Hex(value);
    }

    private OpsLogScanReport buildReport(OpsLogHistorySnapshot snapshot) {
        return OpsLogScanReport.builder()
                .scanTaskId(snapshot.getScanId())
                .status(snapshot.getStatus())
                .triggerType(snapshot.getTriggerType())
                .fileCount(snapshot.getFileCount())
                .issueGroupCount(snapshot.getIssueGroupCount())
                .summary(snapshot.getSummary())
                .startedAt(snapshot.getStartedAt())
                .finishedAt(snapshot.getFinishedAt())
                .categoryCounts(Optional.ofNullable(snapshot.getCategoryCounts()).orElse(buildCategoryCounts(List.of())))
                .topFindings(toSummaries(snapshot.getFindings(), 5))
                .build();
    }

    private OpsLogScanReport buildEmptyReport() {
        return OpsLogScanReport.builder()
                .status("EMPTY")
                .triggerType("NONE")
                .fileCount(0)
                .issueGroupCount(0)
                .summary("当前还没有日志分析结果，聊天助手会在需要时自动触发最近窗口扫描。")
                .categoryCounts(buildCategoryCounts(List.of()))
                .topFindings(List.of())
                .build();
    }

    private List<OpsIssueSummary> toSummaries(List<OpsIssueDetail> issues, int limit) {
        if (issues == null) {
            return List.of();
        }
        return issues.stream()
                .sorted(this::compareIssueDetail)
                .limit(limit)
                .map(this::toSummary)
                .toList();
    }

    private OpsIssueSummary toSummary(OpsIssueDetail issue) {
        return OpsIssueSummary.builder()
                .id(issue.getId())
                .category(issue.getCategory())
                .issueType(issue.getIssueType())
                .severity(issue.getSeverity())
                .title(issue.getTitle())
                .rootCause(issue.getRootCause())
                .occurrenceCount(issue.getOccurrenceCount())
                .suggestion(issue.getSuggestion())
                .lastSeenAt(issue.getLastSeenAt())
                .build();
    }

    private Map<String, Long> buildCategoryCounts(List<OpsIssueDetail> issues) {
        List<OpsIssueDetail> safeIssues = Optional.ofNullable(issues).orElse(List.of());
        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        categoryCounts.put("APP", safeIssues.stream().filter(issue -> "APP".equals(issue.getCategory())).count());
        categoryCounts.put("INFRA", safeIssues.stream().filter(issue -> "INFRA".equals(issue.getCategory())).count());
        categoryCounts.put("PERFORMANCE_DB", safeIssues.stream().filter(issue -> "PERFORMANCE_DB".equals(issue.getCategory())).count());
        return categoryCounts;
    }

    private int compareIssueDetail(OpsIssueDetail left, OpsIssueDetail right) {
        int severityCompare = Integer.compare(severityRank(right.getSeverity()), severityRank(left.getSeverity()));
        if (severityCompare != 0) {
            return severityCompare;
        }
        int countCompare = Integer.compare(Optional.ofNullable(right.getOccurrenceCount()).orElse(0),
                Optional.ofNullable(left.getOccurrenceCount()).orElse(0));
        if (countCompare != 0) {
            return countCompare;
        }
        Date rightTime = right.getLastSeenAt();
        Date leftTime = left.getLastSeenAt();
        if (leftTime == null && rightTime == null) {
            return 0;
        }
        if (leftTime == null) {
            return 1;
        }
        if (rightTime == null) {
            return -1;
        }
        return rightTime.compareTo(leftTime);
    }

    private int severityRank(String severity) {
        return switch (severity) {
            case "P0" -> 3;
            case "P1" -> 2;
            default -> 1;
        };
    }

    private String buildSummary(List<OpsIssueDetail> issues, int fileCount) {
        if (issues.isEmpty()) {
            return "本次扫描共检查 " + fileCount + " 个日志文件，未发现明显异常信号。";
        }
        Map<String, Long> categoryCounts = buildCategoryCounts(issues);
        return "本次扫描共检查 " + fileCount + " 个日志文件，识别到 " + issues.size()
                + " 个问题分组，其中应用异常 " + categoryCounts.get("APP")
                + " 个，依赖异常 " + categoryCounts.get("INFRA")
                + " 个，性能/数据库问题 " + categoryCounts.get("PERFORMANCE_DB") + " 个。";
    }

    private boolean shouldWriteHistory(String triggerType) {
        return !"STARTUP".equalsIgnoreCase(triggerType);
    }

    private List<Path> discoverFiles() {
        Instant cutoff = Instant.now().minus(properties.getLookbackHours(), ChronoUnit.HOURS);
        List<PathMatcher> matchers = properties.getFilePatterns().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
        List<Path> files = new ArrayList<>();
        for (String directory : properties.getDirectories()) {
            if (!StringUtils.hasText(directory)) {
                continue;
            }
            Path dir = Paths.get(directory.trim());
            if (!Files.exists(dir)) {
                continue;
            }
            try (var stream = Files.walk(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> matches(matchers, path))
                        .filter(path -> isAfterCutoff(path, cutoff))
                        .forEach(files::add);
            } catch (IOException e) {
                log.warn("Failed to scan directory {}", dir, e);
            }
        }
        return files.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(this::lastModifiedSafe).reversed())
                .limit(properties.getMaxFiles())
                .toList();
    }

    private boolean matches(List<PathMatcher> matchers, Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(fileName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAfterCutoff(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isAfter(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private Instant lastModifiedSafe(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private List<OpsLogAnalyzer.LogSource> loadSources(List<Path> files) {
        List<OpsLogAnalyzer.LogSource> sources = new ArrayList<>();
        for (Path file : files) {
            try {
                sources.add(new OpsLogAnalyzer.LogSource(file.getFileName().toString(), readFileContent(file)));
            } catch (IOException e) {
                log.warn("Failed to read log file {}", file, e);
            }
        }
        return sources;
    }

    private String readFileContent(Path file) throws IOException {
        int maxBytes = normalizedMaxBytesPerFile();
        if (isGzip(file)) {
            try (InputStream inputStream = new GZIPInputStream(Files.newInputStream(file))) {
                TailBytes tailBytes = readTailBytes(inputStream, maxBytes);
                return decodeTailBytes(tailBytes.bytes(), tailBytes.truncated());
            }
        }
        long size = Files.size(file);
        int bytesToRead = (int) Math.min(size, maxBytes);
        long start = Math.max(0L, size - bytesToRead);
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            ByteBuffer buffer = ByteBuffer.allocate(bytesToRead);
            channel.position(start);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // keep reading
            }
            return decodeTailBytes(buffer.array(), start > 0);
        }
    }

    private TailBytes readTailBytes(InputStream inputStream, int maxBytes) throws IOException {
        byte[] ring = new byte[maxBytes];
        byte[] buffer = new byte[Math.min(8192, maxBytes)];
        int writePosition = 0;
        int totalKept = 0;
        boolean truncated = false;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            if (read >= maxBytes) {
                System.arraycopy(buffer, read - maxBytes, ring, 0, maxBytes);
                writePosition = 0;
                totalKept = maxBytes;
                truncated = true;
                continue;
            }
            int firstPart = Math.min(read, maxBytes - writePosition);
            System.arraycopy(buffer, 0, ring, writePosition, firstPart);
            if (read > firstPart) {
                System.arraycopy(buffer, firstPart, ring, 0, read - firstPart);
            }
            writePosition = (writePosition + read) % maxBytes;
            if (totalKept + read > maxBytes) {
                truncated = true;
            }
            totalKept = Math.min(maxBytes, totalKept + read);
        }
        if (totalKept < maxBytes) {
            return new TailBytes(Arrays.copyOf(ring, totalKept), truncated);
        }
        byte[] result = new byte[maxBytes];
        int tailLength = maxBytes - writePosition;
        System.arraycopy(ring, writePosition, result, 0, tailLength);
        System.arraycopy(ring, 0, result, tailLength, writePosition);
        return new TailBytes(result, truncated);
    }

    private String decodeTailBytes(byte[] bytes, boolean truncated) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (!truncated) {
            return content;
        }
        int newLineIndex = content.indexOf('\n');
        if (newLineIndex >= 0 && newLineIndex + 1 < content.length()) {
            return content.substring(newLineIndex + 1);
        }
        return content;
    }

    private boolean isGzip(Path file) {
        return file.getFileName() != null && file.getFileName().toString().endsWith(".gz");
    }

    private int normalizedMaxBytesPerFile() {
        long configuredValue = properties.getMaxBytesPerFile();
        long safeValue = Math.max(4096L, Math.min(configuredValue, 2_000_000L));
        return (int) safeValue;
    }

    private Optional<OpsLogHistorySnapshot> loadSnapshot(Long scanId) {
        if (scanId == null) {
            return historyService.loadCurrent();
        }
        Optional<OpsLogHistorySnapshot> currentSnapshot = historyService.loadCurrent()
                .filter(snapshot -> Objects.equals(snapshot.getScanId(), scanId));
        return currentSnapshot.isPresent() ? currentSnapshot : historyService.loadHistory(scanId);
    }

    private Date parseDate(String value, boolean endOfDay) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.trim().replace('T', ' ');
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                LocalDateTime dateTime = LocalDateTime.parse(text, formatter);
                return Date.from(dateTime.atZone(ZONE_ID).toInstant());
            } catch (Exception ignored) {
            }
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDate date = LocalDate.parse(text, formatter);
                LocalDateTime dateTime = endOfDay ? date.atTime(23, 59, 59) : date.atStartOfDay();
                return Date.from(dateTime.atZone(ZONE_ID).toInstant());
            } catch (Exception ignored) {
            }
        }
        throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "时间格式不正确，请使用 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
    }

    private int normalizeLimit(Integer limit, int defaultValue, int maxValue) {
        if (limit == null || limit < 1) {
            return defaultValue;
        }
        return Math.min(limit, maxValue);
    }

    private boolean containsIgnoreCase(String text, String keyword) {
        return StringUtils.hasText(text) && StringUtils.hasText(keyword)
                && text.toLowerCase().contains(keyword.toLowerCase());
    }

    private long nextScanId() {
        long now = System.currentTimeMillis();
        return scanIdGenerator.updateAndGet(current -> Math.max(now, current + 1));
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record TailBytes(byte[] bytes, boolean truncated) {
    }
}
