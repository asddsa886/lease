package com.atguigu.lease.web.ops.controller;

import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.web.ops.dto.OpsIssueSummary;
import com.atguigu.lease.web.ops.dto.OpsLogHistorySnapshot;
import com.atguigu.lease.web.ops.dto.OpsLogScanReport;
import com.atguigu.lease.web.ops.service.log.OpsLogScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "运维日志分析")
@RestController
@RequestMapping("/ops/log-scan")
public class OpsLogScanController {

    private final OpsLogScanService logScanService;

    public OpsLogScanController(OpsLogScanService logScanService) {
        this.logScanService = logScanService;
    }

    @Operation(summary = "手动触发一次日志扫描")
    @PostMapping("run")
    public Result<OpsLogScanReport> run() {
        return Result.ok(logScanService.runScan("MANUAL"));
    }

    @Operation(summary = "查看当前最新扫描报告")
    @GetMapping("latest")
    public Result<OpsLogScanReport> latest() {
        return Result.ok(logScanService.getLatestReport());
    }

    @Operation(summary = "查看当前最新扫描的问题分组")
    @GetMapping("latest/findings")
    public Result<List<OpsIssueSummary>> latestFindings(@RequestParam(required = false) String category) {
        return Result.ok(logScanService.listLatestFindings(category));
    }

    @Operation(summary = "查看历史分析列表")
    @GetMapping("history")
    public Result<List<OpsLogScanReport>> history(@RequestParam(required = false) Integer limit) {
        return Result.ok(logScanService.listHistoryReports(limit));
    }

    @Operation(summary = "查看某次历史分析详情")
    @GetMapping("history/{scanId}")
    public Result<OpsLogHistorySnapshot> historyDetail(@PathVariable Long scanId) {
        return Result.ok(logScanService.getHistoryDetail(scanId));
    }

    @Operation(summary = "按条件搜索历史分析")
    @GetMapping("history/search")
    public Result<List<Map<String, Object>>> searchHistory(@RequestParam(required = false) String keyword,
                                                           @RequestParam(required = false) String category,
                                                           @RequestParam(required = false) String issueType,
                                                           @RequestParam(required = false) String fromTime,
                                                           @RequestParam(required = false) String toTime,
                                                           @RequestParam(required = false) Integer limit) {
        return Result.ok(logScanService.searchHistory(keyword, category, issueType, fromTime, toTime, limit));
    }
}
