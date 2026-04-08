package com.atguigu.lease.web.app.chat.service;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantChatService {

    private final AssistantProperties assistantProperties;
    private final ObjectProvider<RentalAssistant> rentalAssistantProvider;

    public AssistantChatResponseVo chat(String message) {
        String question = normalizeQuestion(message);
        if (!assistantProperties.isEnabled()) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手未启用");
        }

        RentalAssistant rentalAssistant = rentalAssistantProvider.getIfAvailable();
        if (rentalAssistant == null) {
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手尚未完成配置");
        }

        try {
            String reply = rentalAssistant.chat(question);
            String formattedReply = formatReply(reply);
            return new AssistantChatResponseVo(formattedReply, splitParagraphs(formattedReply));
        } catch (LeaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("Assistant chat failed", e);
            throw new LeaseException(ResultCodeEnum.SERVICE_ERROR.getCode(), "智能助手调用失败，请稍后重试");
        }
    }

    private String normalizeQuestion(String message) {
        if (!StringUtils.hasText(message)) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }
        return message.trim();
    }

    private String formatReply(String reply) {
        if (!StringUtils.hasText(reply)) {
            return "我当前没有生成有效回复，请换个问法再试。";
        }

        String normalized = reply
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceFirst("^```[a-zA-Z]*\\n?", "")
                .replaceFirst("\\n?```$", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ");

        return normalized.trim();
    }

    private List<String> splitParagraphs(String reply) {
        return Arrays.stream(reply.split("\\n\\s*\\n"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }
}
