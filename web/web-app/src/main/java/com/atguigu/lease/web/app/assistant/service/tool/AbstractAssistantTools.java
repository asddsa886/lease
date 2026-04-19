package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

public abstract class AbstractAssistantTools {

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ISO_LOCAL_DATE
    );

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
        if (value == null || value.isBlank()) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                LocalDateTime dateTime = LocalDateTime.parse(value.trim(), formatter);
                return Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "时间格式不正确，请使用 yyyy-MM-dd HH:mm:ss");
    }

    protected Date parseDate(String value) {
        if (value == null || value.isBlank()) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDate date = LocalDate.parse(value.trim(), formatter);
                return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
            } catch (DateTimeParseException ignored) {
            }
        }
        throw new LeaseException(ResultCodeEnum.PARAM_ERROR.getCode(), "日期格式不正确，请使用 yyyy-MM-dd");
    }

    protected long safePageNumber(Integer pageNumber) {
        return pageNumber == null || pageNumber < 1 ? 1L : pageNumber;
    }

    protected long safePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 20);
    }
}
