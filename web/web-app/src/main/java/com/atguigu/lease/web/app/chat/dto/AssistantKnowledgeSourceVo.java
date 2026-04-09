package com.atguigu.lease.web.app.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "知识片段预览")
public class AssistantKnowledgeSourceVo {

    @Schema(description = "知识片段内容预览")
    private String contentPreview;
}
