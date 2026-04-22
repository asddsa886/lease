package com.atguigu.lease.web.ops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "扫描报告")
public class OpsLogScanReport {

    @Schema(description = "扫描ID")
    private Long scanTaskId;

    @Schema(description = "任务状态")
    private String status;

    @Schema(description = "触发方式")
    private String triggerType;

    @Schema(description = "扫描文件数")
    private Integer fileCount;

    @Schema(description = "问题分组数")
    private Integer issueGroupCount;

    @Schema(description = "诊断摘要")
    private String summary;

    @Schema(description = "开始时间")
    private Date startedAt;

    @Schema(description = "结束时间")
    private Date finishedAt;

    @Schema(description = "分类统计")
    private Map<String, Long> categoryCounts;

    @Schema(description = "重点问题")
    private List<OpsIssueSummary> topFindings;
}
