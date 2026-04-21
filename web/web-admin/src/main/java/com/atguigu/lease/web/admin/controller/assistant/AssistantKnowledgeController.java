package com.atguigu.lease.web.admin.controller.assistant;

import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.model.entity.AssistantKnowledgeDocument;
import com.atguigu.lease.web.admin.service.AssistantKnowledgeService;
import com.atguigu.lease.web.admin.vo.assistant.AssistantKnowledgeQueryVo;
import com.atguigu.lease.web.admin.vo.assistant.AssistantKnowledgeUploadVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "助手知识库管理")
@RestController
@RequestMapping("/admin/assistant/knowledge")
public class AssistantKnowledgeController {

    private final AssistantKnowledgeService assistantKnowledgeService;

    public AssistantKnowledgeController(AssistantKnowledgeService assistantKnowledgeService) {
        this.assistantKnowledgeService = assistantKnowledgeService;
    }

    @Operation(summary = "分页查询知识文档")
    @GetMapping("page")
    public Result<IPage<AssistantKnowledgeDocument>> page(@RequestParam long current,
                                                          @RequestParam long size,
                                                          AssistantKnowledgeQueryVo queryVo) {
        return Result.ok(assistantKnowledgeService.pageDocument(new Page<>(current, size), queryVo));
    }

    @Operation(summary = "根据 id 查询知识文档")
    @GetMapping("getById")
    public Result<AssistantKnowledgeDocument> getById(@RequestParam Long id) {
        return Result.ok(assistantKnowledgeService.getDocumentById(id));
    }

    @Operation(summary = "上传知识文档并建立索引")
    @PostMapping(value = "upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<AssistantKnowledgeDocument> upload(@ModelAttribute AssistantKnowledgeUploadVo uploadVo) {
        return Result.ok(assistantKnowledgeService.upload(uploadVo));
    }

    @Operation(summary = "根据 id 重建知识索引")
    @PostMapping("reindexById")
    public Result<AssistantKnowledgeDocument> reindexById(@RequestParam Long id) {
        return Result.ok(assistantKnowledgeService.reindexById(id));
    }

    @Operation(summary = "根据 id 删除知识文档")
    @PostMapping("removeById")
    public Result<Void> removeById(@RequestParam Long id) {
        assistantKnowledgeService.removeKnowledgeById(id);
        return Result.ok();
    }
}
