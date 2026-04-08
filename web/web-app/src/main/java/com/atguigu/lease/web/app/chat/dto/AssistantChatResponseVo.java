package com.atguigu.lease.web.app.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "智能助手响应体")
public class AssistantChatResponseVo {

    @Schema(description = "助手自然语言回复")
    private String reply;

    @Schema(description = "按段落拆分后的回复，便于前端展示")
    private List<String> paragraphs;
}
