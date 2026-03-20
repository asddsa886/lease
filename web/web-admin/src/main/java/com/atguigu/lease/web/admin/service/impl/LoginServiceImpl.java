package com.atguigu.lease.web.admin.service.impl;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.utils.JwtUtil;
import com.atguigu.lease.model.entity.SystemUser;
import com.atguigu.lease.model.enums.BaseStatus;
import com.atguigu.lease.web.admin.mapper.SystemUserMapper;
import com.atguigu.lease.web.admin.service.LoginService;
import com.atguigu.lease.web.admin.vo.login.CaptchaVo;
import com.atguigu.lease.web.admin.vo.login.LoginVo;
import com.atguigu.lease.web.admin.vo.system.user.SystemUserInfoVo;
import com.wf.captcha.SpecCaptcha;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class LoginServiceImpl implements LoginService {

    /**
     * P0-安全：限制验证码 key 的前缀，防止客户端伪造 captchaKey 删除 Redis 任意 key（key 注入）
     */
    private static final String CAPTCHA_KEY_PREFIX = "admin:login:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SystemUserMapper systemUserMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public CaptchaVo getCaptcha() {
        SpecCaptcha specCaptcha = new SpecCaptcha(130, 48, 5);

        // 获取验证码文本
        String code = specCaptcha.text().toLowerCase();
        String key = CAPTCHA_KEY_PREFIX + UUID.randomUUID();

        stringRedisTemplate.opsForValue().set(key, code, 60, TimeUnit.SECONDS);
        return new CaptchaVo(specCaptcha.toBase64(), key);
    }

    @Override
    public String login(LoginVo loginVo) {
        if (loginVo == null) {
            throw new LeaseException(ResultCodeEnum.ADMIN_CAPTCHA_CODE_NOT_FOUND);
        }

        // P0-安全：验证码 key/code 必填
        if (loginVo.getCaptchaKey() == null || loginVo.getCaptchaKey().isBlank()) {
            throw new LeaseException(ResultCodeEnum.ADMIN_CAPTCHA_CODE_NOT_FOUND);
        }
        if (loginVo.getCaptchaCode() == null || loginVo.getCaptchaCode().isBlank()) {
            throw new LeaseException(ResultCodeEnum.ADMIN_CAPTCHA_CODE_NOT_FOUND);
        }

        // P0-安全：验证码一次性消费 + key 前缀校验（防 key 注入）
        String captchaKey = loginVo.getCaptchaKey().trim();
        if (!captchaKey.startsWith(CAPTCHA_KEY_PREFIX)) {
            // 伪造 key 直接按过期处理，避免暴露实现细节
            throw new LeaseException(ResultCodeEnum.ADMIN_CAPTCHA_CODE_EXPIRED);
        }

        // 原子读取并删除，减少并发场景下验证码复用窗口
        String code = stringRedisTemplate.opsForValue().getAndDelete(captchaKey);
        if (code == null || code.isBlank()) {
            throw new LeaseException(ResultCodeEnum.ADMIN_CAPTCHA_CODE_EXPIRED);
        }

        if (!code.equals(loginVo.getCaptchaCode().toLowerCase())) {
            throw new LeaseException(ResultCodeEnum.ADMIN_CAPTCHA_CODE_ERROR);
        }

        SystemUser systemUser = systemUserMapper.selectOneByUsername(loginVo.getUsername());
        if (systemUser == null) {
            throw new LeaseException(ResultCodeEnum.ADMIN_ACCOUNT_NOT_EXIST_ERROR);
        }

        if (systemUser.getStatus() == BaseStatus.DISABLE) {
            throw new LeaseException(ResultCodeEnum.ADMIN_ACCOUNT_DISABLED_ERROR);
        }

        // P0-安全：密码哈希升级（BCrypt 优先）+ 兼容旧 MD5 自动升级
        String rawPassword = loginVo.getPassword();
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new LeaseException(ResultCodeEnum.ADMIN_ACCOUNT_ERROR);
        }

        String storedHash = systemUser.getPassword();
        boolean matched;
        if (isBcryptHash(storedHash)) {
            matched = passwordEncoder.matches(rawPassword, storedHash);
        } else {
            // 兼容旧 MD5
            String md5 = DigestUtils.md5Hex(rawPassword);
            matched = storedHash != null && storedHash.equals(md5);
            if (matched) {
                // 登录成功即升级为 BCrypt（平滑迁移）
                SystemUser update = new SystemUser();
                update.setId(systemUser.getId());
                update.setPassword(passwordEncoder.encode(rawPassword));
                systemUserMapper.updateById(update);
            }
        }

        if (!matched) {
            throw new LeaseException(ResultCodeEnum.ADMIN_ACCOUNT_ERROR);
        }

        return JwtUtil.creatToken(systemUser.getId(), systemUser.getUsername());
    }

    private static boolean isBcryptHash(String hash) {
        if (hash == null) {
            return false;
        }
        // $2a$ / $2b$ / $2y$ 常见 bcrypt 前缀
        return (hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$"));
    }

    @Override
    public SystemUserInfoVo getLoginUserInfoById(Long userId) {
        SystemUser systemUser = systemUserMapper.selectById(userId);
        SystemUserInfoVo systemUserInfoVo = new SystemUserInfoVo();
        systemUserInfoVo.setName(systemUser.getName());
        systemUserInfoVo.setAvatarUrl(systemUser.getAvatarUrl());
        return systemUserInfoVo;
    }
}
