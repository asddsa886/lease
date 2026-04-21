package com.atguigu.lease.web.admin.vo.assistant;

import com.atguigu.lease.model.enums.AssistantKnowledgeScope;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
@Schema(description = "知识文档上传参数")
public class AssistantKnowledgeUploadVo {

    @Schema(description = "知识文件", type = "string", format = "binary")
    private MultipartFile file;

    @Schema(description = "文档标题，不传时默认取文件名")
    private String title;

    @Schema(description = "知识范围，当前支持 GLOBAL、APARTMENT")
    private AssistantKnowledgeScope scope;

    @Schema(description = "业务对象 id，scope=APARTMENT 时传公寓 id")
    private Long bizId;

    @Schema(description = "备注")
    private String remark;
}
