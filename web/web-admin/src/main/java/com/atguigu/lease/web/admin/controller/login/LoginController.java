package com.atguigu.lease.web.admin.controller.login;
 
 
import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.common.ratelimit.RedisRateLimiter;
import com.atguigu.lease.common.ratelimit.RateLimitProperties;
import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.utils.IpUtil;
import com.atguigu.lease.web.admin.service.LoginService;
import com.atguigu.lease.web.admin.vo.login.CaptchaVo;
import com.atguigu.lease.web.admin.vo.login.LoginVo;
import com.atguigu.lease.web.admin.vo.system.user.SystemUserInfoVo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
 
@Tag(name = "后台管理系统登录管理")
@RestController
@RequestMapping("/admin")
public class LoginController {
 
    @Autowired
    private LoginService loginService;
 
    @Autowired
    private RedisRateLimiter redisRateLimiter;

    @Autowired
    private RateLimitProperties rateLimitProperties;
 
    @Operation(summary = "获取图形验证码")
    @GetMapping("login/captcha")
    public Result<CaptchaVo> getCaptcha(HttpServletRequest request) {
        // P0：接口稳定性（防刷）- 按 IP 限流，避免恶意刷验证码拖垮服务
        String ip = IpUtil.getClientIp(request);
        String rlKey = RedisRateLimiter.key("admin:captcha", "ip", ip);
        RateLimitProperties.Rule rule = rateLimitProperties.getAdmin().getCaptcha();
        boolean allowed = redisRateLimiter.tryAcquireSlidingWindow(rlKey, rule.getLimit(), rule.getWindow());
        if (!allowed) {
            throw new LeaseException(ResultCodeEnum.ADMIN_REQUEST_TOO_FREQUENT);
        }
 
        CaptchaVo result = loginService.getCaptcha();
        return Result.ok(result);
    }
 
    @Operation(summary = "登录")
    @PostMapping("login")
    public Result<String> login(@RequestBody LoginVo loginVo, HttpServletRequest request) {
        // P0：接口稳定性（防刷）- 按 IP 限流，降低爆破风险
        String ip = IpUtil.getClientIp(request);
        String rlKey = RedisRateLimiter.key("admin:login", "ip", ip);
        RateLimitProperties.Rule rule = rateLimitProperties.getAdmin().getLogin();
        boolean allowed = redisRateLimiter.tryAcquireSlidingWindow(rlKey, rule.getLimit(), rule.getWindow());
        if (!allowed) {
            throw new LeaseException(ResultCodeEnum.ADMIN_REQUEST_TOO_FREQUENT);
        }
 
        String jwt = loginService.login(loginVo);
        return Result.ok(jwt);
    }
 
    @Operation(summary = "获取登陆用户个人信息")
    @GetMapping("info")
    public Result<SystemUserInfoVo> info() {
        Long userId = LoginUserHolder.get().getId();
        SystemUserInfoVo systemUserInfoVo = loginService.getLoginUserInfoById(userId);
        return Result.ok(systemUserInfoVo);
    }
}