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
@Schema(description = "扫描历史快照")
public class OpsLogHistorySnapshot {

    @Schema(description = "扫描ID")
    private Long scanId;

    @Schema(description = "任务状态")
    private String status;

    @Schema(description = "触发方式")
    private String triggerType;

    @Schema(description = "扫描目录")
    private List<String> directories;

    @Schema(description = "扫描到的文件列表")
    private List<String> scannedFiles;

    @Schema(description = "回看小时数")
    private Long lookbackHours;

    @Schema(description = "最大文件数")
    private Integer maxFiles;

    @Schema(description = "单文件最大读取字节数")
    private Long maxBytesPerFile;

    @Schema(description = "文件数量")
    private Integer fileCount;

    @Schema(description = "问题分组数量")
    private Integer issueGroupCount;

    @Schema(description = "诊断摘要")
    private String summary;

    @Schema(description = "分类统计")
    private Map<String, Long> categoryCounts;

    @Schema(description = "开始时间")
    private Date startedAt;

    @Schema(description = "结束时间")
    private Date finishedAt;

    @Schema(description = "是否写入历史")
    private Boolean storedInHistory;

    @Schema(description = "历史写入时间")
    private Date historyStoredAt;

    @Schema(description = "分析指纹")
    private String analysisFingerprint;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "完整问题列表")
    private List<OpsIssueDetail> findings;
}
