package com.atguigu.lease.web.admin.service.impl;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.minio.MinioProperties;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.model.entity.AssistantKnowledgeDocument;
import com.atguigu.lease.model.enums.AssistantKnowledgeScope;
import com.atguigu.lease.model.enums.AssistantKnowledgeStatus;
import com.atguigu.lease.web.admin.service.AssistantKnowledgeService;
import com.atguigu.lease.web.admin.service.assistant.AssistantDocumentChunkService;
import com.atguigu.lease.web.admin.service.assistant.AssistantDocumentParseService;
import com.atguigu.lease.web.admin.service.assistant.AssistantKnowledgeChunk;
import com.atguigu.lease.web.admin.service.assistant.AssistantKnowledgeIndexService;
import com.atguigu.lease.web.admin.vo.assistant.AssistantKnowledgeQueryVo;
import com.atguigu.lease.web.admin.vo.assistant.AssistantKnowledgeUploadVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.SetBucketPolicyArgs;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class AssistantKnowledgeServiceImpl extends ServiceImpl<com.atguigu.lease.web.admin.mapper.AssistantKnowledgeDocumentMapper, AssistantKnowledgeDocument>
        implements AssistantKnowledgeService {

    private final AssistantDocumentParseService documentParseService;
    private final AssistantDocumentChunkService documentChunkService;
    private final AssistantKnowledgeIndexService knowledgeIndexService;
    private final ObjectProvider<MinioClient> minioClientProvider;
    private final ObjectProvider<MinioProperties> minioPropertiesProvider;

    public AssistantKnowledgeServiceImpl(AssistantDocumentParseService documentParseService,
                                         AssistantDocumentChunkService documentChunkService,
                                         AssistantKnowledgeIndexService knowledgeIndexService,
                                         ObjectProvider<MinioClient> minioClientProvider,
                                         ObjectProvider<MinioProperties> minioPropertiesProvider) {
        this.documentParseService = documentParseService;
        this.documentChunkService = documentChunkService;
        this.knowledgeIndexService = knowledgeIndexService;
        this.minioClientProvider = minioClientProvider;
        this.minioPropertiesProvider = minioPropertiesProvider;
    }

    @Override
    public IPage<AssistantKnowledgeDocument> pageDocument(Page<AssistantKnowledgeDocument> page,
                                                          AssistantKnowledgeQueryVo queryVo) {
        LambdaQueryWrapper<AssistantKnowledgeDocument> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.orderByDesc(AssistantKnowledgeDocument::getCreateTime, AssistantKnowledgeDocument::getId);
        if (queryVo != null) {
            queryWrapper.like(StringUtils.hasText(queryVo.getTitle()),
                    AssistantKnowledgeDocument::getTitle, queryVo.getTitle());
            queryWrapper.like(StringUtils.hasText(queryVo.getFileName()),
                    AssistantKnowledgeDocument::getFileName, queryVo.getFileName());
            queryWrapper.eq(queryVo.getScope() != null, AssistantKnowledgeDocument::getScope, queryVo.getScope());
            queryWrapper.eq(queryVo.getStatus() != null, AssistantKnowledgeDocument::getStatus, queryVo.getStatus());
            queryWrapper.eq(queryVo.getBizId() != null, AssistantKnowledgeDocument::getBizId, queryVo.getBizId());
        }
        return this.page(page, queryWrapper);
    }

    @Override
    public AssistantKnowledgeDocument getDocumentById(Long id) {
        AssistantKnowledgeDocument document = this.getById(id);
        if (document == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR.getCode(), "知识文档不存在");
        }
        return document;
    }

    @Override
    public AssistantKnowledgeDocument upload(AssistantKnowledgeUploadVo uploadVo) {
        validateUpload(uploadVo);
        MultipartFile file = uploadVo.getFile();
        String fileName = requireFileName(file);
        byte[] bytes = readFileBytes(file);
        String contentType = resolveContentType(file.getContentType());
        String parsedText = documentParseService.parse(fileName, contentType, bytes);

        MinioClient minioClient = requireMinioClient();
        MinioProperties minioProperties = requireMinioProperties();
        ensureBucketReady(minioClient, minioProperties);
        String objectKey = uploadToMinio(minioClient, minioProperties, fileName, contentType, bytes);

        AssistantKnowledgeDocument document = new AssistantKnowledgeDocument();
        document.setTitle(resolveTitle(uploadVo.getTitle(), fileName));
        document.setFileName(fileName);
        document.setBucket(minioProperties.getBucketName());
        document.setObjectKey(objectKey);
        document.setScope(uploadVo.getScope());
        document.setBizId(uploadVo.getScope() == AssistantKnowledgeScope.GLOBAL ? null : uploadVo.getBizId());
        document.setStatus(AssistantKnowledgeStatus.UPLOADED);
        document.setContentType(contentType);
        document.setFileSize(file.getSize());
        document.setChunkCount(0);
        document.setVersion(1);
        document.setRemark(uploadVo.getRemark());
        document.setLastError(null);
        if (!this.save(document)) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "知识文档元数据保存失败");
        }

        return rebuildIndex(document, parsedText, document.getVersion());
    }

    @Override
    public AssistantKnowledgeDocument reindexById(Long id) {
        AssistantKnowledgeDocument document = getDocumentById(id);
        MinioClient minioClient = requireMinioClient();
        byte[] bytes = downloadFromMinio(minioClient, document.getBucket(), document.getObjectKey());
        String parsedText = documentParseService.parse(document.getFileName(), document.getContentType(), bytes);
        int nextVersion = Math.max(document.getVersion() == null ? 1 : document.getVersion(), 1) + 1;
        return rebuildIndex(document, parsedText, nextVersion);
    }

    @Override
    public void removeKnowledgeById(Long id) {
        AssistantKnowledgeDocument document = getDocumentById(id);
        knowledgeIndexService.deleteDocumentIndex(document.getId());

        MinioClient minioClient = requireMinioClient();
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(document.getBucket())
                    .object(document.getObjectKey())
                    .build());
        } catch (Exception e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "删除 MinIO 知识文件失败: " + e.getMessage());
        }

        if (!this.removeById(id)) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "删除知识文档失败");
        }
    }

    private AssistantKnowledgeDocument rebuildIndex(AssistantKnowledgeDocument document, String parsedText, int version) {
        document.setStatus(AssistantKnowledgeStatus.INDEXING);
        document.setVersion(version);
        document.setLastError(null);
        this.updateById(document);

        List<AssistantKnowledgeChunk> chunks = documentChunkService.split(document, parsedText);
        try {
            knowledgeIndexService.rebuildDocument(document, chunks);
            document.setStatus(AssistantKnowledgeStatus.INDEXED);
            document.setChunkCount(chunks.size());
            document.setLastIndexTime(new Date());
            document.setLastError(null);
            this.updateById(document);
            return getDocumentById(document.getId());
        } catch (RuntimeException e) {
            document.setStatus(AssistantKnowledgeStatus.FAILED);
            document.setLastError(truncate(e.getMessage(), 500));
            this.updateById(document);
            throw e;
        }
    }

    private void validateUpload(AssistantKnowledgeUploadVo uploadVo) {
        if (uploadVo == null || uploadVo.getFile() == null || uploadVo.getFile().isEmpty()) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "请先上传知识文档");
        }
        if (uploadVo.getScope() == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "请先选择知识范围");
        }
        if (uploadVo.getScope() == AssistantKnowledgeScope.APARTMENT && uploadVo.getBizId() == null) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "公寓知识文档必须传入公寓 id");
        }
    }

    private String requireFileName(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (!StringUtils.hasText(fileName)) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "知识文档文件名不能为空");
        }
        return fileName;
    }

    private byte[] readFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "读取知识文档失败");
        }
    }

    private String resolveTitle(String title, String fileName) {
        if (StringUtils.hasText(title)) {
            return title.trim();
        }
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private String resolveContentType(String contentType) {
        return StringUtils.hasText(contentType) ? contentType : "application/octet-stream";
    }

    private MinioClient requireMinioClient() {
        MinioClient minioClient = minioClientProvider.getIfAvailable();
        if (minioClient == null) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "MinIO 未配置，请先设置 minio.endpoint 等参数");
        }
        return minioClient;
    }

    private MinioProperties requireMinioProperties() {
        MinioProperties minioProperties = minioPropertiesProvider.getIfAvailable();
        if (minioProperties == null || !StringUtils.hasText(minioProperties.getBucketName())) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "MinIO bucket 未配置，请检查 minio.bucket-name");
        }
        return minioProperties;
    }

    private void ensureBucketReady(MinioClient minioClient, MinioProperties minioProperties) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .build());
                minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .config(createBucketPolicyConfig(minioProperties.getBucketName()))
                        .build());
            }
        } catch (Exception e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "初始化 MinIO Bucket 失败: " + e.getMessage());
        }
    }

    private String uploadToMinio(MinioClient minioClient,
                                 MinioProperties minioProperties,
                                 String fileName,
                                 String contentType,
                                 byte[] bytes) {
        String objectKey = "assistant-knowledge/"
                + new SimpleDateFormat("yyyyMMdd").format(new Date())
                + "/"
                + UUID.randomUUID()
                + "-"
                + fileName;
        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .stream(inputStream, bytes.length, -1)
                    .contentType(contentType)
                    .build());
            return objectKey;
        } catch (Exception e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "上传知识文档到 MinIO 失败: " + e.getMessage());
        }
    }

    private byte[] downloadFromMinio(MinioClient minioClient, String bucket, String objectKey) {
        try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return inputStream.readAllBytes();
        } catch (Exception e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "从 MinIO 读取知识文档失败: " + e.getMessage());
        }
    }

    private String createBucketPolicyConfig(String bucketName) {
        return """
                {
                  "Statement" : [ {
                    "Action" : "s3:GetObject",
                    "Effect" : "Allow",
                    "Principal" : "*",
                    "Resource" : "arn:aws:s3:::%s/*"
                  } ],
                  "Version" : "2012-10-17"
                }
                """.formatted(bucketName);
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(maxLength, 1));
    }
}
