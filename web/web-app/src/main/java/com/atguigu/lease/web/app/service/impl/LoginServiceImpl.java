package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.constant.RedisConstant.RedisConstant;
import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.common.utils.CodeUtil;
import com.atguigu.lease.common.utils.JwtUtil;
import com.atguigu.lease.model.entity.UserInfo;
import com.atguigu.lease.model.enums.BaseStatus;
import com.atguigu.lease.web.app.mapper.UserInfoMapper;
import com.atguigu.lease.web.app.service.LoginService;
import com.atguigu.lease.web.app.service.SmsService;
import com.atguigu.lease.web.app.vo.user.LoginVo;
import com.atguigu.lease.web.app.vo.user.UserInfoVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class LoginServiceImpl implements LoginService {

    /**
     * P0-安全：短信验证码“校验 + 一次性消费”原子化，避免并发复用窗口
     * <p>
     * 返回值：
     * -1: key 不存在/过期
     *  0: 验证码不匹配
     *  1: 匹配并删除成功
     */
    private static final DefaultRedisScript<Long> VERIFY_AND_DELETE_CODE_SCRIPT = new DefaultRedisScript<>(
            """
                    local v = redis.call('GET', KEYS[1])
                    if not v then
                      return -1
                    end
                    if v == ARGV[1] then
                      redis.call('DEL', KEYS[1])
                      return 1
                    end
                    return 0
                    """,
            Long.class
    );

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private SmsService smsService;

    @Override
    public void getCode(String phone) {
        String code = CodeUtil.getRandomCode(6);
        String key = RedisConstant.APP_LOGIN_PREFIX + phone;

        // P0-稳定：Boolean 可能为 null，避免自动拆箱 NPE
        Boolean exists = stringRedisTemplate.hasKey(key);
        if (Boolean.TRUE.equals(exists)) {
            Long expire = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
            // expire 可能为 null/-1/-2；仅在“能拿到剩余 TTL”时做频控判断
            if (expire != null && expire > 0) {
                long elapsed = RedisConstant.APP_LOGIN_CODE_TTL_SEC - expire;
                if (elapsed < RedisConstant.APP_LOGIN_CODE_RESEND_TIME_SEC) {
                    throw new LeaseException(ResultCodeEnum.APP_SEND_SMS_TOO_OFTEN);
                }
            }
        }

        smsService.sendCode(phone, code);
        stringRedisTemplate.opsForValue().set(key, code, RedisConstant.APP_LOGIN_CODE_TTL_SEC, TimeUnit.SECONDS);

        // 安全考虑：不打印验证码，仅记录发送动作（必要时可在 DEBUG 下排查）
        log.debug("Login SMS code sent, phone={}", phone);
    }

    @Override
    public String login(LoginVo loginVo) {
        if (loginVo.getPhone() == null) {
            throw new LeaseException(ResultCodeEnum.APP_LOGIN_PHONE_EMPTY);
        }
        if (loginVo.getCode() == null) {
            throw new LeaseException(ResultCodeEnum.APP_LOGIN_CODE_EMPTY);
        }

        String phone = loginVo.getPhone().trim();
        String inputCode = loginVo.getCode().trim();

        String key = RedisConstant.APP_LOGIN_PREFIX + phone;

        // P0-安全：验证码校验 + 删除 原子化（正确才删除；错误不删除，便于用户重试）
        Long verifyResult = stringRedisTemplate.execute(
                VERIFY_AND_DELETE_CODE_SCRIPT,
                Collections.singletonList(key),
                inputCode
        );
        if (verifyResult == null || verifyResult == -1) {
            throw new LeaseException(ResultCodeEnum.APP_LOGIN_CODE_EXPIRED);
        }
        if (verifyResult == 0) {
            throw new LeaseException(ResultCodeEnum.APP_LOGIN_CODE_ERROR);
        }

        LambdaQueryWrapper<UserInfo> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(UserInfo::getPhone, phone);
        UserInfo userInfo = userInfoMapper.selectOne(queryWrapper);
        if (userInfo == null) {
            userInfo = new UserInfo();
            userInfo.setPhone(phone);
            userInfo.setStatus(BaseStatus.ENABLE);
            userInfo.setNickname("用户-" + phone.substring(7));
            userInfoMapper.insert(userInfo);
        } else {
            if (userInfo.getStatus() == BaseStatus.DISABLE) {
                throw new LeaseException(ResultCodeEnum.APP_ACCOUNT_DISABLED_ERROR);
            }
        }

        return JwtUtil.creatToken(userInfo.getId(), userInfo.getPhone());
    }

    @Override
    public UserInfoVo getLoginUserById(Long userId) {
        UserInfo userInfo = userInfoMapper.selectById(userId);
        UserInfoVo userInfoVo = new UserInfoVo(userInfo.getNickname(), userInfo.getPhone());
        return userInfoVo;
    }
}
