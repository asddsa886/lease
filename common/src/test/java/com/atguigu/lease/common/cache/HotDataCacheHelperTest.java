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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HotDataCacheHelperTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
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
        ReflectionTestUtils.setField(hotDataCacheHelper, "localCacheEnabled", true);
        ReflectionTestUtils.setField(hotDataCacheHelper, "localCacheMaxSize", 128L);
        ReflectionTestUtils.setField(hotDataCacheHelper, "localCacheTtlSec", 30L);
        ReflectionTestUtils.setField(hotDataCacheHelper, "localNullCacheTtlSec", 10L);
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
        verifyNoInteractions(stringRedisTemplate, valueOperations, objectMapper, redissonClient);
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
        verifyNoInteractions(stringRedisTemplate, valueOperations, objectMapper, redissonClient);
    }

    @Test
    void getOrLoad_shouldHitLocalCacheBeforeRedis() throws Exception {
        ReflectionTestUtils.setField(hotDataCacheHelper, "hotDataCacheEnabled", true);
        AtomicInteger loaderCount = new AtomicInteger();
        String key = "app:room:detail:9";

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(null);
        when(objectMapper.writeValueAsString("db-result-1")).thenReturn("\"db-result-1\"");

        String first = hotDataCacheHelper.getOrLoad(
                key,
                new TypeReference<String>() {
                },
                () -> "db-result-" + loaderCount.incrementAndGet()
        );

        String second = hotDataCacheHelper.getOrLoad(
                key,
                new TypeReference<String>() {
                },
                () -> "db-result-" + loaderCount.incrementAndGet()
        );

        assertEquals("db-result-1", first);
        assertEquals("db-result-1", second);
        assertEquals(1, loaderCount.get());
        verify(valueOperations, times(1)).get(key);
        verify(valueOperations, times(1)).set(eq(key), eq("\"db-result-1\""), anyLong(), eq(TimeUnit.SECONDS));
        verifyNoInteractions(redissonClient);
    }

    @Test
    void evict_shouldClearLocalCacheAndRedisKey() throws Exception {
        ReflectionTestUtils.setField(hotDataCacheHelper, "hotDataCacheEnabled", true);
        String key = "app:apartment:detail:9";

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(key)).thenReturn(null);
        when(objectMapper.writeValueAsString("db-result")).thenReturn("\"db-result\"");

        String first = hotDataCacheHelper.getOrLoad(
                key,
                new TypeReference<String>() {
                },
                () -> "db-result"
        );

        hotDataCacheHelper.evict(key);

        String second = hotDataCacheHelper.getOrLoad(
                key,
                new TypeReference<String>() {
                },
                () -> "db-result-2"
        );

        assertEquals("db-result", first);
        assertEquals("db-result-2", second);
        verify(valueOperations, times(2)).get(key);
        verify(stringRedisTemplate, times(1)).delete(key);
    }
}
