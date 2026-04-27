package com.atguigu.lease.web.app.controller.history;

import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.utils.PageParamUtils;
import com.atguigu.lease.web.app.service.BrowsingHistoryService;
import com.atguigu.lease.web.app.vo.history.HistoryItemVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "浏览历史管理")
@RequestMapping("/app/history")
public class BrowsingHistoryController {
    @Autowired
    private BrowsingHistoryService browsingHistoryService;

    @Operation(summary = "获取浏览历史")
    @GetMapping("pageItem")
    public Result<IPage<HistoryItemVo>> page(@RequestParam @Min(value = 1, message = "current必须>=1") long current,
                                            @RequestParam @Min(value = 1, message = "size必须>=1") long size) {
        Page<HistoryItemVo> page = PageParamUtils.page(current, size);
        Long userId = LoginUserHolder.get().getId();
        IPage<HistoryItemVo> result = browsingHistoryService.pageItem(page, userId);
        return Result.ok(result);
    }
}
