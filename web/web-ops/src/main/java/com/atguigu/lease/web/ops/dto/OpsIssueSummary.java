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
@Schema(description = "问题摘要")
public class OpsIssueSummary {

    @Schema(description = "问题ID")
    private Long id;

    @Schema(description = "问题分类")
    private String category;

    @Schema(description = "问题类型")
    private String issueType;

    @Schema(description = "严重级别")
    private String severity;

    @Schema(description = "问题标题")
    private String title;

    @Schema(description = "根因描述")
    private String rootCause;

    @Schema(description = "出现次数")
    private Integer occurrenceCount;

    @Schema(description = "排查建议")
    private String suggestion;

    @Schema(description = "最近出现时间")
    private Date lastSeenAt;
}
