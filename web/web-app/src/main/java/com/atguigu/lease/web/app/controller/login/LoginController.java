package com.atguigu.lease.web.app.controller.login;
 
 
import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.common.ratelimit.RedisRateLimiter;
import com.atguigu.lease.common.ratelimit.RateLimitProperties;
import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.utils.IpUtil;
import com.atguigu.lease.web.app.service.LoginService;
import com.atguigu.lease.web.app.service.UserInfoService;
import com.atguigu.lease.web.app.vo.user.LoginVo;
import com.atguigu.lease.web.app.vo.user.UserInfoVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
 
@Tag(name = "登录管理")
@RestController
@RequestMapping("/app/")
public class LoginController {
 
    @Autowired
    private LoginService loginService;
 
    @Autowired
    private UserInfoService userInfoService;

    @Autowired
    private RedisRateLimiter redisRateLimiter;

    @Autowired
    private RateLimitProperties rateLimitProperties;
 
    @GetMapping("login/getCode")
    @Operation(summary = "获取短信验证码")
    public Result getCode(@RequestParam String phone, HttpServletRequest request) {
        // P0：接口稳定性（防刷）- 短信验证码按 IP + phone 双维度限流
        String ip = IpUtil.getClientIp(request);

        RateLimitProperties.DimRule rule = rateLimitProperties.getApp().getSms();

        boolean ipAllowed = redisRateLimiter.tryAcquireSlidingWindow(
                RedisRateLimiter.key("app:sms", "ip", ip),
                rule.getIp().getLimit(),
                rule.getIp().getWindow()
        );
        if (!ipAllowed) {
            throw new LeaseException(ResultCodeEnum.APP_REQUEST_TOO_FREQUENT);
        }

        boolean phoneAllowed = redisRateLimiter.tryAcquireSlidingWindow(
                RedisRateLimiter.key("app:sms", "phone", phone),
                rule.getPhone().getLimit(),
                rule.getPhone().getWindow()
        );
        if (!phoneAllowed) {
            throw new LeaseException(ResultCodeEnum.APP_SEND_SMS_TOO_OFTEN);
        }

        loginService.getCode(phone);
        return Result.ok();
    }
 
    @PostMapping("login")
    @Operation(summary = "登录")
    public Result<String> login(@RequestBody LoginVo loginVo, HttpServletRequest request) {
        // P0：接口稳定性（防刷）- 登录按 IP + phone 双维度限流（防爆破/撞库）
        String ip = IpUtil.getClientIp(request);

        RateLimitProperties.DimRule rule = rateLimitProperties.getApp().getLogin();

        boolean ipAllowed = redisRateLimiter.tryAcquireSlidingWindow(
                RedisRateLimiter.key("app:login", "ip", ip),
                rule.getIp().getLimit(),
                rule.getIp().getWindow()
        );
        if (!ipAllowed) {
            throw new LeaseException(ResultCodeEnum.APP_REQUEST_TOO_FREQUENT);
        }

        // phone 为空时，后续业务会抛 APP_LOGIN_PHONE_EMPTY；这里不做强依赖，避免 NPE
        if (loginVo != null && loginVo.getPhone() != null && !loginVo.getPhone().isBlank()) {
            boolean phoneAllowed = redisRateLimiter.tryAcquireSlidingWindow(
                    RedisRateLimiter.key("app:login", "phone", loginVo.getPhone()),
                    rule.getPhone().getLimit(),
                    rule.getPhone().getWindow()
            );
            if (!phoneAllowed) {
                throw new LeaseException(ResultCodeEnum.APP_REQUEST_TOO_FREQUENT);
            }
        }

        String token = loginService.login(loginVo);
        return Result.ok(token);
    }
 
    @GetMapping("info")
    @Operation(summary = "获取登录用户信息")
    public Result<UserInfoVo> info() {
        Long userId = LoginUserHolder.get().getId();
        UserInfoVo infoVo = loginService.getLoginUserById(userId);
        return Result.ok(infoVo);
    }
}
 
