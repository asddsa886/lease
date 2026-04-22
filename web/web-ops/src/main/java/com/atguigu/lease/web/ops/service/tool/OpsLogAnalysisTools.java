package com.atguigu.lease.web.ops.service.tool;

import com.atguigu.lease.web.ops.service.log.OpsLogScanService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class OpsLogAnalysisTools {

    private final OpsLogScanService logScanService;

    public OpsLogAnalysisTools(OpsLogScanService logScanService) {
        this.logScanService = logScanService;
    }

    @Tool(description = "触发一次最近窗口日志扫描。默认用于聊天助手自动补扫；如果用户明确要求保留这次分析记录，可把 forceStoreHistory 设为 true。")
    public Map<String, Object> runLogScan(
            @ToolParam(description = "是否强制保存为历史分析，用户明确要求重扫并保留记录时传 true", required = false)
            Boolean forceStoreHistory) {
        String triggerType = Boolean.TRUE.equals(forceStoreHistory) ? "MANUAL" : "ASSISTANT_AUTO";
        return execute("runLogScan", "日志扫描完成",
                () -> Map.of("message", "日志扫描完成", "report", logScanService.runScan(triggerType)));
    }

    @Tool(description = "查询当前最新扫描报告，适合先了解当前日志窗口的总体状态、问题数量和分类概况。")
    public Map<String, Object> getLatestScanReport() {
        return execute("getLatestScanReport", "已获取当前扫描报告",
                () -> Map.of("report", logScanService.getLatestReport()));
    }

    @Tool(description = "查询当前最新扫描中的问题分组，可按分类过滤。分类仅支持 APP、INFRA、PERFORMANCE_DB。")
    public Map<String, Object> listIssueGroups(
            @ToolParam(description = "问题分类，可为空；可选值为 APP、INFRA、PERFORMANCE_DB", required = false) String category,
            @ToolParam(description = "返回条数，默认 10，最大 20", required = false) Integer limit) {
        return execute("listIssueGroups", "已获取当前问题分组",
                () -> Map.of("issues", logScanService.listIssueGroups(category, limit)));
    }

    @Tool(description = "根据问题ID查询问题详情和证据片段；若 scanId 为空则默认查看当前最新扫描。")
    public Map<String, Object> getIssueDetail(
            @ToolParam(description = "问题ID", required = true) Long issueGroupId,
            @ToolParam(description = "扫描ID，可为空；为空时默认查看当前最新扫描", required = false) Long scanId) {
        return execute("getIssueDetail", "已获取问题详情",
                () -> logScanService.getIssueDetail(issueGroupId, scanId));
    }

    @Tool(description = "查询当前扫描里的性能和数据库问题，例如慢 SQL、高耗时请求、连接池耗尽、锁等待。")
    public Map<String, Object> listSlowSqlFindings(
            @ToolParam(description = "返回条数，默认 5，最大 20", required = false) Integer limit) {
        return execute("listSlowSqlFindings", "已获取性能与数据库问题",
                () -> Map.of("issues", logScanService.listSlowSqlFindings(limit)));
    }

    @Tool(description = "查询当前扫描里的依赖与基础设施问题，例如 Redis、RabbitMQ、MySQL、MinIO、Milvus 连接失败或超时。")
    public Map<String, Object> listDependencyFailures(
            @ToolParam(description = "返回条数，默认 5，最大 20", required = false) Integer limit) {
        return execute("listDependencyFailures", "已获取依赖异常问题",
                () -> Map.of("issues", logScanService.listDependencyFailures(limit)));
    }

    @Tool(description = "按关键字检索日志证据，可结合 scanId 或问题ID 缩小范围；如果 scanId 为空则默认在当前扫描内检索。")
    public Map<String, Object> searchLogEvidence(
            @ToolParam(description = "需要检索的关键字，可为空", required = false) String keyword,
            @ToolParam(description = "扫描ID，可为空；为空时默认使用当前扫描", required = false) Long scanId,
            @ToolParam(description = "问题ID，可为空", required = false) Long issueGroupId,
            @ToolParam(description = "返回条数，默认 5，最大 20", required = false) Integer limit) {
        return execute("searchLogEvidence", "已获取日志证据",
                () -> Map.of("evidences", logScanService.searchLogEvidence(keyword, scanId, issueGroupId, limit)));
    }

    @Tool(description = "查看最近的历史分析记录，适合回答“最近 3 次为什么挂了”这类问题。")
    public Map<String, Object> listHistoryScans(
            @ToolParam(description = "返回条数，默认 10，最大 50", required = false) Integer limit) {
        return execute("listHistoryScans", "已获取历史分析列表",
                () -> Map.of("history", logScanService.listHistoryReports(limit)));
    }

    @Tool(description = "根据扫描ID查看某次历史分析的完整快照详情，包括问题列表和证据片段。")
    public Map<String, Object> getHistoryScanDetail(
            @ToolParam(description = "扫描ID", required = true) Long scanId) {
        return execute("getHistoryScanDetail", "已获取历史分析详情",
                () -> Map.of("history", logScanService.getHistoryDetail(scanId)));
    }

    @Tool(description = "按关键字、分类、问题类型和时间范围搜索历史分析，适合回答“昨天那次是不是 Redis 问题”这类问题。时间请优先使用 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss。")
    public Map<String, Object> searchHistoryScans(
            @ToolParam(description = "关键字，可为空，例如 Redis、OOM、慢 SQL", required = false) String keyword,
            @ToolParam(description = "问题分类，可为空；可选值为 APP、INFRA、PERFORMANCE_DB", required = false) String category,
            @ToolParam(description = "问题类型，可为空，例如 STARTUP_FAILURE、DEPENDENCY_CONNECTION_FAILURE", required = false) String issueType,
            @ToolParam(description = "开始时间，可为空；建议格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss", required = false) String fromTime,
            @ToolParam(description = "结束时间，可为空；建议格式 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss", required = false) String toTime,
            @ToolParam(description = "返回条数，默认 10，最大 50", required = false) Integer limit) {
        return execute("searchHistoryScans", "已完成历史分析检索",
                () -> Map.of("history", logScanService.searchHistory(keyword, category, issueType, fromTime, toTime, limit)));
    }

    private Map<String, Object> execute(String toolName, String successMessage, Supplier<Map<String, Object>> supplier) {
        OpsToolEventEmitter emitter = OpsToolEventContext.currentEmitter();
        emitter.emit("tool_call", toolName, "正在调用 " + toolName);
        try {
            Map<String, Object> result = supplier.get();
            emitter.emit("tool_result", toolName, successMessage);
            return result;
        } catch (Exception e) {
            emitter.emit("tool_result", toolName, e.getMessage());
            return Map.of(
                    "message", e.getMessage(),
                    "issues", List.of(),
                    "history", List.of(),
                    "evidences", List.of()
            );
        }
    }
}
