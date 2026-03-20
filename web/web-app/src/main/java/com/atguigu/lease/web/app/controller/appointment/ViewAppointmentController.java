package com.atguigu.lease.web.app.controller.appointment;
 
import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.common.ratelimit.RedisRateLimiter;
import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.utils.IpUtil;
import com.atguigu.lease.model.entity.ViewAppointment;
import com.atguigu.lease.web.app.service.ApartmentInfoService;
import com.atguigu.lease.web.app.service.ViewAppointmentService;
import com.atguigu.lease.web.app.vo.apartment.ApartmentItemVo;
import com.atguigu.lease.web.app.vo.appointment.AppointmentDetailVo;
import com.atguigu.lease.web.app.vo.appointment.AppointmentItemVo;
import com.atguigu.lease.web.app.vo.appointment.AppointmentSubmitVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
 
import java.time.Duration;
import java.util.List;
 
@Tag(name = "看房预约信息")
@RestController
@Validated
@RequestMapping("/app/appointment")
public class ViewAppointmentController {
 
    @Autowired
    private ViewAppointmentService viewAppointmentService;
 
    @Autowired
    private ApartmentInfoService apartmentInfoService;

    @Autowired
    private RedisRateLimiter redisRateLimiter;
 
    @Operation(summary = "保存或更新看房预约")
    @PostMapping("/saveOrUpdate")
    public Result saveOrUpdate(@RequestBody @Valid AppointmentSubmitVo submitVo, HttpServletRequest request) {
        Long currentUserId = LoginUserHolder.get().getId();

        // P0：接口稳定性（防刷）- 预约提交按 userId + IP 双维度限流
        String ip = IpUtil.getClientIp(request);

        boolean userAllowed = redisRateLimiter.tryAcquire(
                RedisRateLimiter.key("app:appointment", "userId", currentUserId),
                5,
                Duration.ofSeconds(60)
        );
        if (!userAllowed) {
            throw new LeaseException(ResultCodeEnum.APP_REQUEST_TOO_FREQUENT);
        }

        boolean ipAllowed = redisRateLimiter.tryAcquire(
                RedisRateLimiter.key("app:appointment", "ip", ip),
                60,
                Duration.ofSeconds(60)
        );
        if (!ipAllowed) {
            throw new LeaseException(ResultCodeEnum.APP_REQUEST_TOO_FREQUENT);
        }
 
        ViewAppointment viewAppointment = new ViewAppointment();
        BeanUtils.copyProperties(submitVo, viewAppointment);
 
        // userId 以当前登录用户为准，防止越权/伪造
        viewAppointment.setUserId(currentUserId);
 
        viewAppointmentService.saveOrUpdateForCurrentUser(viewAppointment, currentUserId);
        return Result.ok();
    }
 
    @Operation(summary = "查询个人预约看房列表")
    @GetMapping("listItem")
    public Result<List<AppointmentItemVo>> listItem() {
        List<AppointmentItemVo> result = viewAppointmentService.getDetailByUserId(LoginUserHolder.get().getId());
        return Result.ok(result);
    }
 
    @GetMapping("getDetailById")
    @Operation(summary = "根据ID查询预约详情信息")
    public Result<AppointmentDetailVo> getDetailById(@RequestParam @NotNull(message = "id不能为空")
                                                     @Min(value = 1, message = "id必须>=1") Long id) {
        ViewAppointment byId = viewAppointmentService.getById(id);
        if (byId == null) {
            throw new LeaseException(ResultCodeEnum.DATA_ERROR);
        }
        if (!LoginUserHolder.get().getId().equals(byId.getUserId())) {
            throw new LeaseException(ResultCodeEnum.ILLEGAL_REQUEST);
        }
 
        ApartmentItemVo itemById = apartmentInfoService.getItemById(byId.getApartmentId());
 
        AppointmentDetailVo appointmentDetailVo = new AppointmentDetailVo();
        BeanUtils.copyProperties(byId, appointmentDetailVo);
        appointmentDetailVo.setApartmentItemVo(itemById);
        return Result.ok(appointmentDetailVo);
    }
 
}
