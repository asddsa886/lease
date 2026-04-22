package com.atguigu.lease.web.ops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "日志证据片段")
public class OpsEvidenceItem {

    @Schema(description = "证据ID")
    private Long id;

    @Schema(description = "所属扫描ID")
    private Long scanId;

    @Schema(description = "所属问题ID")
    private Long issueId;

    @Schema(description = "日志文件名")
    private String fileName;

    @Schema(description = "日志级别")
    private String level;

    @Schema(description = "命中的关键信号")
    private String keyword;

    @Schema(description = "起始行号")
    private Integer lineStart;

    @Schema(description = "结束行号")
    private Integer lineEnd;

    @Schema(description = "事件时间")
    private Date eventTime;

    @Schema(description = "日志片段")
    private String snippet;
}
