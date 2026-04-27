package com.atguigu.lease.web.app.controller.room;


import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.utils.PageParamUtils;
import com.atguigu.lease.web.app.service.RoomInfoService;
import com.atguigu.lease.web.app.vo.room.RoomDetailVo;
import com.atguigu.lease.web.app.vo.room.RoomItemVo;
import com.atguigu.lease.web.app.vo.room.RoomQueryVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "房间信息")
@RestController
@RequestMapping("/app/room")
@Validated
public class RoomController {
    @Autowired
    private RoomInfoService roomInfoService;

    @Operation(summary = "分页查询房间列表")
    @GetMapping("pageItem")
    public Result<IPage<RoomItemVo>> pageItem(
            @RequestParam @Min(value = 1, message = "current必须>=1") long current,
            @RequestParam @Min(value = 1, message = "size必须>=1") long size,
            RoomQueryVo queryVo
    ) {
        Page<RoomItemVo> page = PageParamUtils.page(current, size);
        IPage<RoomItemVo> result = roomInfoService.pageItem(page, queryVo);
        return Result.ok(result);
    }

    @Operation(summary = "根据id获取房间的详细信息")
    @GetMapping("getDetailById")
    public Result<RoomDetailVo> getDetailById(@RequestParam @NotNull(message = "id不能为空") Long id) {
        RoomDetailVo roomInfo = roomInfoService.getDetailById(id);
        return Result.ok(roomInfo);
    }

    @Operation(summary = "根据公寓id分页查询房间列表")
    @GetMapping("pageItemByApartmentId")
    public Result<IPage<RoomItemVo>> pageItemByApartmentId(
            @RequestParam @Min(value = 1, message = "current必须>=1") long current,
            @RequestParam @Min(value = 1, message = "size必须>=1") long size,
            @RequestParam @NotNull(message = "id不能为空") Long id
    ) {
        Page<RoomItemVo> page = PageParamUtils.page(current, size);
        IPage<RoomItemVo> result = roomInfoService.pageItemByApartmentId(page, id);
        return Result.ok(result);
    }
}
