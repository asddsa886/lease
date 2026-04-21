package com.atguigu.lease.model.entity;

import com.atguigu.lease.model.enums.AssistantKnowledgeScope;
import com.atguigu.lease.model.enums.AssistantKnowledgeStatus;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

@Schema(description = "助手知识文档表")
@TableName("assistant_knowledge_document")
@Data
@EqualsAndHashCode(callSuper = true)
public class AssistantKnowledgeDocument extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "文档标题")
    @TableField("title")
    private String title;

    @Schema(description = "原始文件名")
    @TableField("file_name")
    private String fileName;

    @Schema(description = "MinIO 桶名")
    @TableField("bucket")
    private String bucket;

    @Schema(description = "MinIO 对象键")
    @TableField("object_key")
    private String objectKey;

    @Schema(description = "知识范围")
    @TableField("scope")
    private AssistantKnowledgeScope scope;

    @Schema(description = "业务对象 id，GLOBAL 可为空")
    @TableField("biz_id")
    private Long bizId;

    @Schema(description = "索引状态")
    @TableField("status")
    private AssistantKnowledgeStatus status;

    @Schema(description = "文件内容类型")
    @TableField("content_type")
    private String contentType;

    @Schema(description = "文件大小，单位字节")
    @TableField("file_size")
    private Long fileSize;

    @Schema(description = "切片数量")
    @TableField("chunk_count")
    private Integer chunkCount;

    @Schema(description = "索引版本")
    @TableField("version")
    private Integer version;

    @Schema(description = "最后索引时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("last_index_time")
    private Date lastIndexTime;

    @Schema(description = "备注")
    @TableField("remark")
    private String remark;

    @Schema(description = "最近一次索引失败原因")
    @TableField("last_error")
    private String lastError;
}
