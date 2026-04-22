package com.atguigu.lease.web.ops.service.log;

import lombok.Getter;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class OpsLogAnalyzer {

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:[+-]\\d{2}:\\d{2}|Z)?)"
    );
    private static final Pattern LEVEL_PATTERN = Pattern.compile("\\b(INFO|WARN|ERROR|DEBUG|TRACE)\\b");
    private static final Pattern LOGGER_PATTERN = Pattern.compile("\\] ([\\w.$]+)\\s*:");
    private static final Pattern COST_MS_PATTERN = Pattern.compile("costMs=(\\d+)");
    private static final Pattern CAUSED_BY_PATTERN = Pattern.compile("Caused by:\\s*([^\\r\\n]+)");
    private static final Pattern EXCEPTION_LINE_PATTERN = Pattern.compile("([\\w.$]+(?:Exception|Error))(?::\\s*([^\\r\\n]+))?");
    private static final DateTimeFormatter LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public AnalysisResult analyze(List<LogSource> sources, long slowSqlThresholdMs) {
        List<ParsedEvent> events = new ArrayList<>();
        for (LogSource source : sources) {
            events.addAll(parseEvents(source));
        }

        Map<String, MutableIssue> issueMap = new LinkedHashMap<>();
        for (ParsedEvent event : events) {
            DetectedSignal signal = classify(event, slowSqlThresholdMs);
            if (signal == null) {
                continue;
            }
            String rootCause = resolveRootCause(event);
            String fingerprint = DigestUtils.md5Hex(signal.category + "|" + signal.issueType + "|" + normalize(rootCause));
            MutableIssue issue = issueMap.computeIfAbsent(fingerprint,
                    key -> new MutableIssue(signal, fingerprint, rootCause, event.loggerName));
            issue.accept(event);
        }

        List<DetectedIssue> issues = issueMap.values().stream()
                .map(MutableIssue::toDetectedIssue)
                .sorted(Comparator.comparingInt((DetectedIssue issue) -> severityRank(issue.severity)).thenComparing(DetectedIssue::getOccurrenceCount).reversed())
                .toList();
        return new AnalysisResult(issues);
    }

    private List<ParsedEvent> parseEvents(LogSource source) {
        List<ParsedEvent> events = new ArrayList<>();
        String[] lines = source.content.split("\\R", -1);
        List<String> currentBlock = new ArrayList<>();
        int startLine = 1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean startsNewBlock = isBlockStart(line);
            if (startsNewBlock && !currentBlock.isEmpty()) {
                events.add(buildEvent(source.fileName, currentBlock, startLine, i));
                currentBlock = new ArrayList<>();
                startLine = i + 1;
            }
            if (startsNewBlock || !currentBlock.isEmpty()) {
                currentBlock.add(line);
            }
        }

        if (!currentBlock.isEmpty()) {
            events.add(buildEvent(source.fileName, currentBlock, startLine, lines.length));
        }
        return events;
    }

    private boolean isBlockStart(String line) {
        if (!StringUtils.hasText(line)) {
            return false;
        }
        return TIMESTAMP_PATTERN.matcher(line).find() || line.startsWith("==>") || line.startsWith("<==");
    }

    private ParsedEvent buildEvent(String fileName, List<String> block, int lineStart, int lineEnd) {
        String content = String.join(System.lineSeparator(), block).trim();
        String firstLine = block.get(0);
        String level = matchGroup(LEVEL_PATTERN, firstLine, 1);
        String loggerName = matchGroup(LOGGER_PATTERN, firstLine, 1);
        Date eventTime = parseEventTime(firstLine);
        Long costMs = parseCostMs(content);
        return new ParsedEvent(fileName, lineStart, lineEnd, firstLine, content, level, loggerName, eventTime, costMs);
    }

    private DetectedSignal classify(ParsedEvent event, long slowSqlThresholdMs) {
        String upper = event.content.toUpperCase(Locale.ROOT);

        if (upper.contains("OUTOFMEMORYERROR")) {
            return new DetectedSignal("APP", "OUT_OF_MEMORY", "P0", "发现 OOM 异常", "检查堆内存配置、对象堆积与大查询/大对象加载。", "OOM");
        }
        if (containsAny(event.content, "UnsatisfiedDependencyException", "BeanCreationException", "Application run failed")) {
            return new DetectedSignal("APP", "STARTUP_FAILURE", "P0", "发现应用启动失败", "优先检查启动阶段依赖注入、配置缺失和 Bean 初始化异常。", "STARTUP");
        }
        if (containsAny(event.content, "RejectedExecutionException")) {
            return new DetectedSignal("APP", "THREAD_POOL_REJECTED", "P1", "发现线程池拒绝任务", "检查线程池容量、队列长度和突发流量。", "THREAD_POOL");
        }
        if (containsAny(event.content, "No qualifying bean", "Missing bean", "NoSuchBeanDefinitionException")) {
            return new DetectedSignal("APP", "BEAN_MISSING", "P1", "发现 Bean 缺失或装配异常", "检查自动装配条件、配置开关和组件扫描范围。", "BEAN");
        }
        if (containsAny(event.content, "Communications link failure", "Connection refused", "UnknownHostException",
                "No route to host", "RedisConnectionFailureException", "JedisConnectionException",
                "AmqpConnectException", "ConnectException")) {
            return new DetectedSignal("INFRA", "DEPENDENCY_CONNECTION_FAILURE", "P0", "发现依赖连接失败", "优先检查目标服务是否存活、端口是否可达、账号密码是否正确。", "CONNECTION");
        }
        if (containsAny(event.content, "Access denied", "Authentication failed", "invalid credentials")) {
            return new DetectedSignal("INFRA", "DEPENDENCY_AUTH_FAILURE", "P1", "发现依赖认证失败", "检查账号密码、token、密钥以及对应环境配置。", "AUTH");
        }
        if (containsAny(event.content, "timeout", "Read timed out", "SocketTimeoutException", "TimeoutException")
                && containsAny(event.content, "Redis", "RabbitMQ", "MySQL", "Minio", "Milvus", "HTTP", "Feign", "OkHttp")) {
            return new DetectedSignal("INFRA", "DEPENDENCY_TIMEOUT", "P1", "发现依赖调用超时", "检查目标服务响应耗时、网络链路和重试配置。", "TIMEOUT");
        }
        if (event.costMs != null && event.costMs >= slowSqlThresholdMs) {
            return new DetectedSignal("PERFORMANCE_DB", "HIGH_REQUEST_LATENCY", "P2", "发现高耗时请求", "优先结合 trace、SQL 和下游调用链排查耗时瓶颈。", "LATENCY");
        }
        if (containsAny(event.content, "HikariPool", "Connection is not available", "Could not get JDBC Connection", "CannotGetJdbcConnectionException")) {
            return new DetectedSignal("PERFORMANCE_DB", "DB_POOL_EXHAUSTED", "P1", "发现数据库连接池异常", "检查连接池容量、慢 SQL 和连接未及时释放的问题。", "HIKARI");
        }
        if (containsAny(event.content, "Lock wait timeout exceeded", "Deadlock found")) {
            return new DetectedSignal("PERFORMANCE_DB", "DB_LOCK_CONTENTION", "P1", "发现数据库锁冲突", "检查事务范围、索引命中和并发更新热点。", "LOCK");
        }
        if (containsAny(event.content, "slow sql", "SQL timeout", "QueryTimeoutException", "Statement cancelled")) {
            return new DetectedSignal("PERFORMANCE_DB", "DB_TIMEOUT", "P1", "发现数据库执行超时", "检查 SQL 执行计划、索引和数据库负载。", "SQL_TIMEOUT");
        }
        if ("ERROR".equalsIgnoreCase(event.level) || containsAny(event.content, "Exception", "Error")) {
            return new DetectedSignal("APP", "APP_EXCEPTION", "P2", "发现应用异常", "结合异常栈顶部和 Caused by 链路继续定位具体代码路径。", "EXCEPTION");
        }
        return null;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text != null && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String resolveRootCause(ParsedEvent event) {
        Matcher causedByMatcher = CAUSED_BY_PATTERN.matcher(event.content);
        String rootCause = null;
        while (causedByMatcher.find()) {
            rootCause = causedByMatcher.group(1);
        }
        if (StringUtils.hasText(rootCause)) {
            return rootCause.trim();
        }
        String exceptionLine = findExceptionLine(event.content);
        return StringUtils.hasText(exceptionLine) ? exceptionLine : event.firstLine;
    }

    private String findExceptionLine(String content) {
        for (String line : content.split("\\R")) {
            Matcher matcher = EXCEPTION_LINE_PATTERN.matcher(line);
            if (matcher.find()) {
                return line.trim();
            }
        }
        return null;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value
                .replaceAll("\\d+", "#")
                .replaceAll("[0-9a-fA-F]{8,}", "*")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String matchGroup(Pattern pattern, String value, int group) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(group) : null;
    }

    private Long parseCostMs(String content) {
        Matcher matcher = COST_MS_PATTERN.matcher(content);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }

    private Date parseEventTime(String firstLine) {
        Matcher matcher = TIMESTAMP_PATTERN.matcher(firstLine);
        if (!matcher.find()) {
            return null;
        }
        String raw = matcher.group(1);
        try {
            return Date.from(OffsetDateTime.parse(raw.replace(' ', 'T')).toInstant());
        } catch (Exception ignored) {
        }
        try {
            LocalDateTime localDateTime = LocalDateTime.parse(raw.replace('T', ' ').substring(0, 23), LOCAL_DATE_TIME);
            return Date.from(localDateTime.atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        } catch (Exception ignored) {
            return null;
        }
    }

    private int severityRank(String severity) {
        return switch (severity) {
            case "P0" -> 3;
            case "P1" -> 2;
            default -> 1;
        };
    }

    public record LogSource(String fileName, String content) {
    }

    public record AnalysisResult(List<DetectedIssue> issues) {
    }

    @Getter
    public static class DetectedIssue {
        private final String category;
        private final String issueType;
        private final String severity;
        private final String fingerprint;
        private final String title;
        private final String rootCause;
        private final String suggestion;
        private final String loggerName;
        private final int occurrenceCount;
        private final Date firstSeenAt;
        private final Date lastSeenAt;
        private final List<DetectedEvidence> evidences;

        public DetectedIssue(String category,
                             String issueType,
                             String severity,
                             String fingerprint,
                             String title,
                             String rootCause,
                             String suggestion,
                             String loggerName,
                             int occurrenceCount,
                             Date firstSeenAt,
                             Date lastSeenAt,
                             List<DetectedEvidence> evidences) {
            this.category = category;
            this.issueType = issueType;
            this.severity = severity;
            this.fingerprint = fingerprint;
            this.title = title;
            this.rootCause = rootCause;
            this.suggestion = suggestion;
            this.loggerName = loggerName;
            this.occurrenceCount = occurrenceCount;
            this.firstSeenAt = firstSeenAt;
            this.lastSeenAt = lastSeenAt;
            this.evidences = evidences;
        }
    }

    public record DetectedEvidence(String fileName,
                                   String level,
                                   String keyword,
                                   Integer lineStart,
                                   Integer lineEnd,
                                   Date eventTime,
                                   String snippet) {
    }

    private record ParsedEvent(String fileName,
                               int lineStart,
                               int lineEnd,
                               String firstLine,
                               String content,
                               String level,
                               String loggerName,
                               Date eventTime,
                               Long costMs) {
    }

    private record DetectedSignal(String category,
                                  String issueType,
                                  String severity,
                                  String title,
                                  String suggestion,
                                  String keyword) {
    }

    private static class MutableIssue {

        private final DetectedSignal signal;
        private final String fingerprint;
        private final String rootCause;
        private final String loggerName;
        private int occurrenceCount;
        private Date firstSeenAt;
        private Date lastSeenAt;
        private final List<DetectedEvidence> evidences = new ArrayList<>();

        private MutableIssue(DetectedSignal signal, String fingerprint, String rootCause, String loggerName) {
            this.signal = signal;
            this.fingerprint = fingerprint;
            this.rootCause = rootCause;
            this.loggerName = loggerName;
        }

        private void accept(ParsedEvent event) {
            occurrenceCount++;
            if (event.eventTime != null) {
                firstSeenAt = firstSeenAt == null || event.eventTime.before(firstSeenAt) ? event.eventTime : firstSeenAt;
                lastSeenAt = lastSeenAt == null || event.eventTime.after(lastSeenAt) ? event.eventTime : lastSeenAt;
            }
            evidences.add(new DetectedEvidence(
                    event.fileName,
                    event.level,
                    signal.keyword,
                    event.lineStart,
                    event.lineEnd,
                    event.eventTime,
                    truncate(event.content, 2000)
            ));
        }

        private DetectedIssue toDetectedIssue() {
            List<DetectedEvidence> sortedEvidences = evidences.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(DetectedEvidence::eventTime, Comparator.nullsLast(Date::compareTo)).reversed())
                    .limit(5)
                    .toList();
            return new DetectedIssue(
                    signal.category,
                    signal.issueType,
                    signal.severity,
                    fingerprint,
                    signal.title,
                    rootCause,
                    signal.suggestion,
                    loggerName,
                    occurrenceCount,
                    firstSeenAt,
                    lastSeenAt,
                    sortedEvidences
            );
        }

        private String truncate(String value, int maxLength) {
            if (!StringUtils.hasText(value) || value.length() <= maxLength) {
                return value;
            }
            return value.substring(0, maxLength);
        }
    }
}
