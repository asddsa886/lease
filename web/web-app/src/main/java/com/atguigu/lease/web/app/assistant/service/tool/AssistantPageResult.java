package com.atguigu.lease.web.app.assistant.service.tool;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantPageResult<T> {

    private long current;

    private long size;

    private long total;

    private List<T> items;

    public static <T> AssistantPageResult<T> from(IPage<T> page) {
        return AssistantPageResult.<T>builder()
                .current(page.getCurrent())
                .size(page.getSize())
                .total(page.getTotal())
                .items(page.getRecords())
                .build();
    }
}
