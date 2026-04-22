package com.atguigu.lease.web.ops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "问题详情")
public class OpsIssueDetail {

    @Schema(description = "问题ID")
    private Long id;

    @Schema(description = "所属扫描ID")
    private Long scanId;

    @Schema(description = "问题分类")
    private String category;

    @Schema(description = "问题类型")
    private String issueType;

    @Schema(description = "严重级别")
    private String severity;

    @Schema(description = "问题指纹")
    private String fingerprint;

    @Schema(description = "问题标题")
    private String title;

    @Schema(description = "根因描述")
    private String rootCause;

    @Schema(description = "排查建议")
    private String suggestion;

    @Schema(description = "日志器名称")
    private String loggerName;

    @Schema(description = "出现次数")
    private Integer occurrenceCount;

    @Schema(description = "首次出现时间")
    private Date firstSeenAt;

    @Schema(description = "最近出现时间")
    private Date lastSeenAt;

    @Schema(description = "证据条数")
    private Integer evidenceCount;

    @Schema(description = "证据片段")
    private List<OpsEvidenceItem> evidences;
}
