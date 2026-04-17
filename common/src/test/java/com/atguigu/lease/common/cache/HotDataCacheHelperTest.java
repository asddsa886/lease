package com.atguigu.lease.common.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class HotDataCacheHelperTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private RedissonClient redissonClient;

    private HotDataCacheHelper hotDataCacheHelper;

    @BeforeEach
    void setUp() {
        hotDataCacheHelper = new HotDataCacheHelper();
        ReflectionTestUtils.setField(hotDataCacheHelper, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(hotDataCacheHelper, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(hotDataCacheHelper, "redissonClient", redissonClient);
    }

    @Test
    void getOrLoad_shouldBypassCacheWhenDisabled() {
        ReflectionTestUtils.setField(hotDataCacheHelper, "hotDataCacheEnabled", false);

        String result = hotDataCacheHelper.getOrLoad(
                "app:room:detail:1",
                new TypeReference<String>() {
                },
                () -> "db-result"
        );

        assertEquals("db-result", result);
        verifyNoInteractions(stringRedisTemplate, objectMapper, redissonClient);
    }

    @Test
    void getOrLoadWithLock_shouldBypassCacheAndLockWhenDisabled() {
        ReflectionTestUtils.setField(hotDataCacheHelper, "hotDataCacheEnabled", false);

        String result = hotDataCacheHelper.getOrLoadWithLock(
                "app:room:detail:1",
                new TypeReference<String>() {
                },
                () -> "db-result"
        );

        assertEquals("db-result", result);
        verifyNoInteractions(stringRedisTemplate, objectMapper, redissonClient);
    }
}
