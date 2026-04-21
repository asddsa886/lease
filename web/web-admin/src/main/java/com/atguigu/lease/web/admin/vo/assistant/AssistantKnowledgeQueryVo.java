package com.atguigu.lease.web.admin.vo.assistant;

import com.atguigu.lease.model.enums.AssistantKnowledgeScope;
import com.atguigu.lease.model.enums.AssistantKnowledgeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "知识文档查询条件")
public class AssistantKnowledgeQueryVo {

    @Schema(description = "文档标题")
    private String title;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "知识范围")
    private AssistantKnowledgeScope scope;

    @Schema(description = "索引状态")
    private AssistantKnowledgeStatus status;

    @Schema(description = "业务对象 id")
    private Long bizId;
}
