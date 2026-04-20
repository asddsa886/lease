package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import org.springframework.ai.chat.model.ToolContext;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.function.Supplier;

public abstract class AbstractAssistantTools {

    private static final ZoneId ASSISTANT_ZONE = AssistantDateTimeParser.DEFAULT_ZONE;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    protected Long currentUserId(ToolContext toolContext) {
        return AssistantToolContextSupport.currentUserId(toolContext);
    }

    protected AssistantToolResult executeTool(String toolName,
                                              ToolContext toolContext,
                                              String successMessage,
                                              Supplier<Object> supplier) {
        AssistantToolEventEmitter emitter = AssistantToolContextSupport.eventEmitter(toolContext);
        emitter.emit("tool_call", toolName, "正在调用" + toolName);
        try {
            Object data = supplier.get();
            emitter.emit("tool_result", toolName, successMessage);
            return AssistantToolResult.ok(successMessage, data);
        } catch (LeaseException e) {
            emitter.emit("tool_result", toolName, e.getMessage());
            return AssistantToolResult.fail(e.getMessage());
        } catch (Exception e) {
            emitter.emit("tool_result", toolName, ResultCodeEnum.SERVICE_ERROR.getMessage());
            return AssistantToolResult.fail(ResultCodeEnum.SERVICE_ERROR.getMessage());
        }
    }

    protected Date parseDateTime(String value) {
        return AssistantDateTimeParser.parseDateTime(value);
    }

    protected Date parseDate(String value) {
        return AssistantDateTimeParser.parseDate(value);
    }

    protected String formatDateTime(Date value) {
        if (value == null) {
            return null;
        }
        return value.toInstant()
                .atZone(ASSISTANT_ZONE)
                .toLocalDateTime()
                .format(DATE_TIME_FORMATTER);
    }

    protected String formatDate(Date value) {
        if (value == null) {
            return null;
        }
        return value.toInstant()
                .atZone(ASSISTANT_ZONE)
                .toLocalDate()
                .format(DATE_FORMATTER);
    }

    protected long safePageNumber(Integer pageNumber) {
        return pageNumber == null || pageNumber < 1 ? 1L : pageNumber;
    }

    protected long safePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 20);
    }
}
