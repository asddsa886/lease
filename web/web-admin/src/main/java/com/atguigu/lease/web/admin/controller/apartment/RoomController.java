package com.atguigu.lease.web.admin.controller.apartment;


import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.model.entity.RoomInfo;
import com.atguigu.lease.model.enums.ReleaseStatus;
import com.atguigu.lease.web.admin.service.RoomInfoService;
import com.atguigu.lease.web.admin.service.RoomMetricsService;
import com.atguigu.lease.web.admin.vo.metrics.RoomFunnelVo;
import com.atguigu.lease.web.admin.vo.metrics.RoomQualityScoreVo;
import com.atguigu.lease.web.admin.vo.room.RoomDetailVo;
import com.atguigu.lease.web.admin.vo.room.RoomItemVo;
import com.atguigu.lease.web.admin.vo.room.RoomQueryVo;
import com.atguigu.lease.web.admin.vo.room.RoomSubmitVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.atguigu.lease.common.utils.PageParamUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "房间信息管理")
@RestController
@RequestMapping("/admin/room")
public class RoomController {
    @Autowired
    private RoomInfoService service;

    @Autowired
    private RoomMetricsService roomMetricsService;

    @Operation(summary = "保存或更新房间信息")
    @PostMapping("saveOrUpdate")
    public Result saveOrUpdate(@RequestBody RoomSubmitVo roomSubmitVo) {
        service.saveOrUpdateRoom(roomSubmitVo);
        return Result.ok();
    }

    @Operation(summary = "根据条件分页查询房间列表")
    @GetMapping("pageItem")
    public Result<IPage<RoomItemVo>> pageItem(@RequestParam(required = false) Long current,
                                              @RequestParam(required = false) Long size,
                                              RoomQueryVo queryVo) {
        IPage<RoomItemVo> page = new Page<>(PageParamUtils.current(current), PageParamUtils.size(size));
        IPage<RoomItemVo> result = service.pageRoomItemByQuery(page,queryVo);
        return Result.ok(result);
    }

    @Operation(summary = "根据id获取房间详细信息")
    @GetMapping("getDetailById")
    public Result<RoomDetailVo> getDetailById(@RequestParam Long id) {
        RoomDetailVo roomInfo = service.getRoomDetailById(id);
        return Result.ok(roomInfo);
    }

    @Operation(summary = "根据id删除房间信息")
    @DeleteMapping("removeById")
    public Result removeById(@RequestParam Long id) {
        service.removeRoomById(id);
        return Result.ok();
    }

    @Operation(summary = "根据id修改房间发布状态")
    @PostMapping("updateReleaseStatusById")
    public Result updateReleaseStatusById(@RequestParam Long id, @RequestParam ReleaseStatus status) {
        LambdaUpdateWrapper<RoomInfo> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(RoomInfo::getId, id);
        updateWrapper.set(RoomInfo::getIsRelease, status);
        service.update(updateWrapper);
        return Result.ok();
    }

    @GetMapping("listBasicByApartmentId")
    @Operation(summary = "根据公寓id查询房间列表")
    public Result<List<RoomInfo>> listBasicByApartmentId(@RequestParam Long id) {
        LambdaQueryWrapper<RoomInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(RoomInfo::getApartmentId, id);
        queryWrapper.eq(RoomInfo::getIsRelease, ReleaseStatus.RELEASED);
        List<RoomInfo> list = service.list(queryWrapper);


        return Result.ok(list);
    }

    @Operation(summary = "分页查询房源质量分")
    @GetMapping("metrics/quality/page")
    public Result<IPage<RoomQualityScoreVo>> pageQualityScore(@RequestParam(required = false) Long current,
                                                              @RequestParam(required = false) Long size,
                                                              @RequestParam(required = false) Long apartmentId) {
        Page<RoomQualityScoreVo> page = new Page<>(PageParamUtils.current(current), PageParamUtils.size(size));
        return Result.ok(roomMetricsService.pageQualityScore(page, apartmentId));
    }

    @Operation(summary = "查询房源转化漏斗")
    @GetMapping("metrics/funnel")
    public Result<RoomFunnelVo> getFunnel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long apartmentId) {
        return Result.ok(roomMetricsService.getFunnel(startDate, endDate, apartmentId));
    }
}


















