package com.atguigu.lease.web.admin.service;

import com.atguigu.lease.model.entity.AssistantKnowledgeDocument;
import com.atguigu.lease.web.admin.vo.assistant.AssistantKnowledgeQueryVo;
import com.atguigu.lease.web.admin.vo.assistant.AssistantKnowledgeUploadVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

public interface AssistantKnowledgeService extends IService<AssistantKnowledgeDocument> {

    IPage<AssistantKnowledgeDocument> pageDocument(Page<AssistantKnowledgeDocument> page,
                                                   AssistantKnowledgeQueryVo queryVo);

    AssistantKnowledgeDocument getDocumentById(Long id);

    AssistantKnowledgeDocument upload(AssistantKnowledgeUploadVo uploadVo);

    AssistantKnowledgeDocument reindexById(Long id);

    void removeKnowledgeById(Long id);
}
