package com.atguigu.lease.web.admin.service.assistant;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class AssistantDocumentParseService {

    public String parse(String fileName, String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "上传的知识文档不能为空");
        }

        String extension = resolveExtension(fileName);
        if ("pdf".equals(extension) || isPdfContentType(contentType)) {
            return readPdf(bytes);
        }
        if ("md".equals(extension) || "markdown".equals(extension) || "txt".equals(extension) || isTextContentType(contentType)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "当前仅支持 md、txt、pdf 文档");
    }

    private String readPdf(byte[] bytes) {
        try (PDDocument document = PDDocument.load(bytes)) {
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "PDF 文档解析失败");
        }
    }

    private String resolveExtension(String fileName) {
        if (!StringUtils.hasText(fileName) || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isPdfContentType(String contentType) {
        return StringUtils.hasText(contentType) && contentType.toLowerCase(Locale.ROOT).contains("pdf");
    }

    private boolean isTextContentType(String contentType) {
        return StringUtils.hasText(contentType) && contentType.toLowerCase(Locale.ROOT).startsWith("text/");
    }
}
